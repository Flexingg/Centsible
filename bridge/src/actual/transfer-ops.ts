import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import { toTransaction } from '../mappers/index.js';
import type { ActualHost, Lib } from './host.js';
import { TX_FIELDS } from './transaction-ops.js';

type Raw = Record<string, unknown>;
type Send = (name: string, args?: unknown) => Promise<unknown>;

/** Default posting gap for general transfer matching (credit card ACH often takes 6–10 days over weekends/holidays). */
const DEFAULT_WINDOW_DAYS = 10;
/** Default posting gap for focused/fuzzy searches between specific accounts. */
const FUZZY_WINDOW_DAYS = 21;
/** Default lookback when allTime is not enabled. */
const LOOKBACK_DAYS = 120;
const DAY = 86_400_000;

export type TransferMatchOptions = {
  allTime?: boolean;
  fromAccountId?: string;
  toAccountId?: string;
  windowDays?: number;
  fuzzy?: boolean;
};

export type TransferPair = {
  from: Raw;
  to: Raw;
  days: number;
  confident: boolean;
  amountDiff: number;
  reason: string | null;
};

const dismissedKey = (budgetId: string) => `transfers.dismissed.${budgetId}`;
const autoKey = (budgetId: string) => `transfers.auto.${budgetId}`;
const pairKey = (a: string, b: string) => [a, b].sort().join('|');
const days = (a: string, b: string) => Math.round(Math.abs(Date.parse(a) - Date.parse(b)) / DAY);

const GENERIC_ACCOUNT_WORDS = new Set([
  'the', 'and', 'for', 'my', 'our', 'new', 'old', 'main', 'primary', 'personal', 'joint',
  'bank', 'account', 'acct', 'card', 'credit', 'debit', 'checking', 'savings', 'cash', 'money',
]);

const PAYMENT_KEYWORD_RE =
  /\b(payment|pmt|autopay|auto[- ]?pay|epay|e-pay|credit\s*crd|credit\s*card|card\s*pmt|online\s*pmt|thank\s*you|transfer|xfer|ach|bill\s*pay|payoff|chase|amex|american\s*express|citi|capital\s*one|discover|barclays|wells\s*fargo|bofa|bank\s*of\s*america|us\s*bank|apple\s*card|bilt|sofi|venmo|zelle)\b/i;

/**
 * Pairs that look like one payment seen from both sides: a credit card payment leaving
 * checking and arriving on the card, savings moves, and so on. Same amount (or close amount
 * in fuzzy mode), opposite signs, different accounts, posted within the date window,
 * neither already a transfer.
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

  matches(budgetId: string, opts: TransferMatchOptions = {}) {
    return this.host.withBudget(budgetId, 'read', async () => ({
      pairs: (await this.find(budgetId, opts)).map((p) => ({
        from: toTransaction(p.from),
        to: toTransaction(p.to),
        days: p.days,
        confident: p.confident,
        amountDiff: p.amountDiff,
        reason: p.reason,
      })),
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
      if (!p.confident || p.amountDiff !== 0) continue;
      await linkPair(lib, p.from, p.to);
      linked++;
    }
    return linked;
  }

  private async find(budgetId: string, opts: TransferMatchOptions = {}): Promise<TransferPair[]> {
    const hasAccountPair = Boolean(opts.fromAccountId && opts.toAccountId && opts.fromAccountId !== opts.toAccountId);
    const filter: Raw = {
      transfer_id: null,
      is_parent: false,
      is_child: false,
      starting_balance_flag: false,
      amount: { $ne: 0 },
    };
    if (!opts.allTime) {
      filter.date = { $gte: new Date(this.now() - LOOKBACK_DAYS * DAY).toISOString().slice(0, 10) };
    }
    if (hasAccountPair) {
      // Explicit 2-account search includes closed accounts too (e.g. paying off a closed card).
      filter.account = { $oneof: [opts.fromAccountId, opts.toAccountId] };
    } else {
      filter['account.closed'] = false;
    }

    const [{ data }, accounts] = await Promise.all([
      api.aqlQuery(api.q('transactions').filter(filter).options({ splits: 'inline' }).select([...TX_FIELDS, 'imported_payee'])) as Promise<{ data: Raw[] }>,
      api.getAccounts(),
    ]);
    const accountNames = new Map(accounts.map((a) => [a.id, a.name]));
    const dismissed = new Set(this.store.getSetting<string[]>(dismissedKey(budgetId)) ?? []);
    const defaultWindow = opts.fuzzy || hasAccountPair ? FUZZY_WINDOW_DAYS : DEFAULT_WINDOW_DAYS;
    const windowDays = opts.windowDays ?? defaultWindow;

    const pairs = pairUp(data, dismissed, {
      windowDays,
      fuzzy: opts.fuzzy === true,
      accountNames,
      onlyAccountId: !hasAccountPair ? (opts.fromAccountId ?? opts.toAccountId) : undefined,
    });
    return pairs;
  }
}

export type PairUpOptions = {
  windowDays?: number;
  fuzzy?: boolean;
  accountNames?: Map<string, string>;
  onlyAccountId?: string;
};

type Candidate = {
  from: Raw;
  to: Raw;
  days: number;
  amountDiff: number;
  score: number;
  accountMatch: boolean;
  reason: string | null;
};

/**
 * Every outflow with its best inflow (exact amounts first, then payment/account-name
 * signals, then closest date), each transaction used once.
 */
export function pairUp(rows: Raw[], dismissed: Set<string> = new Set(), opts: PairUpOptions = {}): TransferPair[] {
  const windowDays = opts.windowDays ?? DEFAULT_WINDOW_DAYS;
  const fuzzy = opts.fuzzy === true;
  const accountNames = opts.accountNames ?? new Map<string, string>();
  const accountTokens = new Map<string, string[]>();
  for (const [id, name] of accountNames) {
    accountTokens.set(id, significantTokens(name));
  }

  const inflowsByAmount = new Map<number, Raw[]>();
  const allInflows: Raw[] = [];
  for (const r of rows) {
    const amt = Number(r.amount);
    if (amt > 0) {
      allInflows.push(r);
      const list = inflowsByAmount.get(amt);
      if (list) list.push(r);
      else inflowsByAmount.set(amt, [r]);
    }
  }

  const candidates: Candidate[] = [];
  const exactPairIds = new Set<string>();

  const evalCandidate = (out: Raw, inn: Raw, amountDiff: number): Candidate | null => {
    if (inn.account === out.account) return null;
    if (opts.onlyAccountId && out.account !== opts.onlyAccountId && inn.account !== opts.onlyAccountId) return null;
    const d = days(String(out.date), String(inn.date));
    if (d > windowDays || dismissed.has(pairKey(String(out.id), String(inn.id)))) return null;

    const outText = txText(out);
    const innText = txText(inn);
    const toTokens = accountTokens.get(String(inn.account)) ?? [];
    const fromTokens = accountTokens.get(String(out.account)) ?? [];
    const accountMatch = hasTokenOverlap(outText, toTokens) || hasTokenOverlap(innText, fromTokens);
    const outPay = PAYMENT_KEYWORD_RE.test(outText);
    const innPay = PAYMENT_KEYWORD_RE.test(innText);
    const score = (accountMatch ? 3 : 0) + (outPay ? 1 : 0) + (innPay ? 1 : 0);

    const reason =
      amountDiff > 0
        ? accountMatch
          ? 'Account name · near amount'
          : outPay || innPay
            ? 'Card payment · near amount'
            : 'Near amount'
        : accountMatch
          ? 'Account name match'
          : outPay || innPay
            ? 'Card payment'
            : null;

    return { from: out, to: inn, days: d, amountDiff, score, accountMatch, reason };
  };

  for (const out of rows) {
    const amount = Number(out.amount);
    if (amount >= 0) continue;
    for (const inn of inflowsByAmount.get(-amount) ?? []) {
      const c = evalCandidate(out, inn, 0);
      if (c) {
        candidates.push(c);
        exactPairIds.add(pairKey(String(out.id), String(inn.id)));
      }
    }
  }

  if (fuzzy) {
    for (const out of rows) {
      const outAbs = -Number(out.amount);
      if (outAbs <= 0) continue;
      for (const inn of allInflows) {
        if (inn.account === out.account) continue;
        const innAbs = Number(inn.amount);
        const diff = Math.abs(outAbs - innAbs);
        if (diff === 0) continue;
        // Near-amount tolerance: within $5.00 or 2% of the larger side.
        const tolerance = Math.max(500, Math.round(Math.max(outAbs, innAbs) * 0.02));
        if (diff > tolerance) continue;
        if (exactPairIds.has(pairKey(String(out.id), String(inn.id)))) continue;
        const c = evalCandidate(out, inn, diff);
        if (c) candidates.push(c);
      }
    }
  }

  // Count exact-amount candidates per transaction to determine uniqueness / confidence.
  const exactCount = new Map<unknown, number>();
  const acctMatchCount = new Map<unknown, number>();
  for (const c of candidates) {
    if (c.amountDiff !== 0) continue;
    exactCount.set(c.from.id, (exactCount.get(c.from.id) ?? 0) + 1);
    exactCount.set(c.to.id, (exactCount.get(c.to.id) ?? 0) + 1);
    if (c.accountMatch) {
      acctMatchCount.set(c.from.id, (acctMatchCount.get(c.from.id) ?? 0) + 1);
      acctMatchCount.set(c.to.id, (acctMatchCount.get(c.to.id) ?? 0) + 1);
    }
  }

  candidates.sort(
    (a, b) =>
      Number(a.amountDiff > 0) - Number(b.amountDiff > 0) ||
      b.score - a.score ||
      a.amountDiff - b.amountDiff ||
      a.days - b.days ||
      String(b.from.date).localeCompare(String(a.from.date)),
  );

  const used = new Set<unknown>();
  const pairs: TransferPair[] = [];
  for (const c of candidates) {
    if (used.has(c.from.id) || used.has(c.to.id)) continue;
    used.add(c.from.id);
    used.add(c.to.id);
    const uniqueExact = c.amountDiff === 0 && exactCount.get(c.from.id) === 1 && exactCount.get(c.to.id) === 1;
    const uniqueAccountMatch = c.amountDiff === 0 && c.accountMatch && acctMatchCount.get(c.from.id) === 1 && acctMatchCount.get(c.to.id) === 1;
    const confident = c.amountDiff === 0 && ((uniqueExact && (c.days <= 7 || c.score >= 1)) || uniqueAccountMatch);
    pairs.push({ from: c.from, to: c.to, days: c.days, confident, amountDiff: c.amountDiff, reason: c.reason });
  }
  return pairs.sort((a, b) => String(b.from.date).localeCompare(String(a.from.date)));
}

function txText(r: Raw): string {
  return [r['payee.name'], r.imported_payee, r.notes].filter(Boolean).join(' ').toLowerCase();
}

function significantTokens(name: string): string[] {
  return name
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 3 && !GENERIC_ACCOUNT_WORDS.has(w));
}

function hasTokenOverlap(text: string, tokens: string[]): boolean {
  if (!text || !tokens.length) return false;
  return tokens.some((t) => text.includes(t));
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
