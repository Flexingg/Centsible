import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import { toTransaction } from '../mappers/index.js';
import type { ActualHost, Lib } from './host.js';
import { TX_FIELDS } from './transaction-ops.js';

type Raw = Record<string, unknown>;
type Send = (name: string, args?: unknown) => Promise<unknown>;

/** How far apart the two sides of a payment can post (banks take a few days). */
const WINDOW_DAYS = 5;
/** How far back to look for pairs. */
const LOOKBACK_DAYS = 120;
const DAY = 86_400_000;

export type TransferPair = { from: Raw; to: Raw; days: number; confident: boolean };

const dismissedKey = (budgetId: string) => `transfers.dismissed.${budgetId}`;
const autoKey = (budgetId: string) => `transfers.auto.${budgetId}`;
const pairKey = (a: string, b: string) => [a, b].sort().join('|');
const days = (a: string, b: string) => Math.round(Math.abs(Date.parse(a) - Date.parse(b)) / DAY);

/**
 * Pairs that look like one payment seen from both sides: a credit card payment leaving
 * checking and arriving on the card, savings moves, and so on. Same amount, opposite
 * signs, different accounts, posted within a few days, neither already a transfer.
 * Linking them is what Actual's own "Make transfer" does: each side's payee becomes the
 * other account, the two point at each other, and they stop counting as spending.
 */
export class TransferOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly now: () => number = Date.now,
  ) {}

  auto(budgetId: string) {
    return this.store.getSetting<boolean>(autoKey(budgetId)) === true;
  }

  setAuto(budgetId: string, on: boolean) {
    this.store.setSetting(autoKey(budgetId), on ? true : null);
  }

  matches(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => ({
      pairs: (await this.find(budgetId)).map((p) => ({ from: toTransaction(p.from), to: toTransaction(p.to), days: p.days, confident: p.confident })),
      auto: this.auto(budgetId),
    }));
  }

  link(budgetId: string, fromId: string, toId: string) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const [a, b] = await Promise.all([one(fromId), one(toId)]);
      if (!a || !b) throw ApiError.notFound('Transaction not found');
      const problem = cantLink(a, b);
      if (problem) throw ApiError.validation(problem);
      await linkPair(lib, a, b);
      const [x, y] = await Promise.all([one(fromId), one(toId)]);
      return { from: toTransaction(x!), to: toTransaction(y!) };
    });
  }

  /**
   * The other side of [id], for linking by hand: transactions in other accounts, not
   * already transfers. The same amount going the other way comes first, then the closest
   * dates. [q] searches payee, notes and amount over the last year; without it, two months
   * either side.
   */
  candidates(budgetId: string, id: string, q: string | undefined, limit: number) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const t = await one(id);
      if (!t) throw ApiError.notFound('Transaction not found');
      const span = q ? 366 : 60;
      const at = Date.parse(String(t.date));
      const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({
            account: { $ne: t.account },
            transfer_id: null,
            is_parent: false,
            starting_balance_flag: false,
            date: { $gte: iso(at - span * DAY), $lte: iso(at + span * DAY) },
          })
          .options({ splits: 'inline' })
          .select([...TX_FIELDS]),
      )) as { data: Raw[] };
      const want = -Number(t.amount);
      const needle = q?.trim().toLowerCase().replace(/^\$/, '') ?? '';
      const cents = /^[\d,]+(\.\d{1,2})?$/.test(needle) ? Math.round(Number(needle.replace(/,/g, '')) * 100) : null;
      const matches = (r: Raw) => {
        if (!needle) return true;
        const text = [r['payee.name'], r.imported_payee, r.notes].filter(Boolean).join(' ').toLowerCase();
        return text.includes(needle) || (cents !== null && Math.abs(Number(r.amount)) === cents);
      };
      const items = data
        .filter((r) => r.id !== t.id && matches(r))
        .map((r) => ({ r, exact: Number(r.amount) === want, days: days(String(r.date), String(t.date)) }))
        .sort((a, b) => Number(b.exact) - Number(a.exact) || a.days - b.days)
        .slice(0, limit)
        .map(({ r, exact, days: d }) => ({ transaction: toTransaction(r), exact, days: d }));
      return { transaction: toTransaction(t), items };
    });
  }

  dismiss(budgetId: string, fromId: string, toId: string) {
    const list = this.store.getSetting<string[]>(dismissedKey(budgetId)) ?? [];
    const key = pairKey(fromId, toId);
    if (!list.includes(key)) this.store.setSetting(dismissedKey(budgetId), [...list, key].slice(-1000));
  }

  /**
   * After a bank sync, when turned on: links the pairs with no other candidate on
   * either side. Runs inside the sync's own budget session. Returns how many it linked.
   */
  async autoLink(budgetId: string, lib: Lib): Promise<number> {
    if (!this.auto(budgetId)) return 0;
    let linked = 0;
    for (const p of await this.find(budgetId)) {
      if (!p.confident) continue;
      await linkPair(lib, p.from, p.to);
      linked++;
    }
    return linked;
  }

  private async find(budgetId: string): Promise<TransferPair[]> {
    const since = new Date(this.now() - LOOKBACK_DAYS * DAY).toISOString().slice(0, 10);
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ date: { $gte: since }, transfer_id: null, is_parent: false, is_child: false, starting_balance_flag: false, 'account.closed': false, amount: { $ne: 0 } })
        .options({ splits: 'inline' })
        .select(TX_FIELDS),
    )) as { data: Raw[] };
    const dismissed = new Set(this.store.getSetting<string[]>(dismissedKey(budgetId)) ?? []);
    return pairUp(data, dismissed);
  }
}

/** Every outflow with its best inflow (closest date), each transaction used once. */
export function pairUp(rows: Raw[], dismissed: Set<string> = new Set()): TransferPair[] {
  const inflows = new Map<number, Raw[]>();
  for (const r of rows) if (Number(r.amount) > 0) inflows.set(Number(r.amount), [...(inflows.get(Number(r.amount)) ?? []), r]);
  const candidates: { from: Raw; to: Raw; days: number }[] = [];
  for (const out of rows) {
    const amount = Number(out.amount);
    if (amount >= 0) continue;
    for (const inn of inflows.get(-amount) ?? []) {
      if (inn.account === out.account) continue;
      const d = days(String(out.date), String(inn.date));
      if (d > WINDOW_DAYS || dismissed.has(pairKey(String(out.id), String(inn.id)))) continue;
      candidates.push({ from: out, to: inn, days: d });
    }
  }
  // How many candidates each transaction has: one each way is a confident match.
  const count = new Map<unknown, number>();
  for (const c of candidates) {
    count.set(c.from.id, (count.get(c.from.id) ?? 0) + 1);
    count.set(c.to.id, (count.get(c.to.id) ?? 0) + 1);
  }
  candidates.sort((a, b) => a.days - b.days || String(b.from.date).localeCompare(String(a.from.date)));
  const used = new Set<unknown>();
  const pairs: TransferPair[] = [];
  for (const c of candidates) {
    if (used.has(c.from.id) || used.has(c.to.id)) continue;
    used.add(c.from.id);
    used.add(c.to.id);
    pairs.push({ ...c, confident: count.get(c.from.id) === 1 && count.get(c.to.id) === 1 });
  }
  return pairs.sort((a, b) => String(b.from.date).localeCompare(String(a.from.date)));
}

function cantLink(a: Raw, b: Raw): string | null {
  if (a.account === b.account) return 'Both are in the same account.';
  if (Number(a.amount) !== -Number(b.amount)) return "The amounts don't cancel out.";
  if (a.transfer_id || b.transfer_id) return 'One of them is already a transfer.';
  if (a.is_parent || b.is_parent) return "A split transaction can't be a transfer.";
  return null;
}

async function one(id: string): Promise<Raw | undefined> {
  const { data } = (await api.aqlQuery(api.q('transactions').filter({ id }).options({ splits: 'inline' }).select(TX_FIELDS))) as { data: Raw[] };
  return data[0];
}

/** The same change Actual's web app makes for "Make transfer". */
async function linkPair(lib: Lib, a: Raw, b: Raw) {
  const payees = (await api.getPayees()) as { id: string; transfer_acct?: string | null }[];
  const payeeFor = (account: unknown) => payees.find((p) => p.transfer_acct === account)?.id;
  const toB = payeeFor(b.account);
  const toA = payeeFor(a.account);
  if (!toA || !toB) throw ApiError.validation("One of the accounts can't take transfers.");
  // Actual copies one side's notes onto the other; keep whichever has some.
  const notes = (a.notes as string | null) || (b.notes as string | null) || null;
  await (lib.send as Send)('transactions-batch-update', {
    updated: [
      { id: a.id, payee: toB, transfer_id: b.id, notes },
      { id: b.id, payee: toA, transfer_id: a.id, notes },
    ],
  });
  await new Promise<void>((r) => setTimeout(r, 0));
}
