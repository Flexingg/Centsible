import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import { toTransaction } from '../mappers/index.js';
import type { ActualHost } from './host.js';
import { TX_FIELDS } from './transaction-ops.js';

type Raw = Record<string, unknown>;

/** How far apart a duplicate can be dated (banks and sync providers disagree by a day or two). */
const WINDOW_DAYS = 3;
const LOOKBACK_DAYS = 120;
const DAY = 86_400_000;

const dismissedKey = (budgetId: string) => `duplicates.dismissed.${budgetId}`;
const pairKey = (a: string, b: string) => [a, b].sort().join('|');
const days = (a: string, b: string) => Math.round(Math.abs(Date.parse(a) - Date.parse(b)) / DAY);

/** Where a transaction came from: Plaid, Teller (before it closed), another import (SimpleFIN, a file), or typed in. */
function source(t: Raw): 'plaid' | 'teller' | 'bank' | 'manual' {
  const id = typeof t.imported_id === 'string' ? t.imported_id : '';
  return id.startsWith('plaid:') ? 'plaid' : id.startsWith('teller:') ? 'teller' : id ? 'bank' : 'manual';
}

const norm = (s: unknown) => (typeof s === 'string' ? s.toLowerCase().replace(/[^a-z0-9]/g, '') : '');
const samePayee = (a: Raw, b: Raw) => {
  const names = (t: Raw) => [norm(t['payee.name']), norm(t.imported_payee)].filter(Boolean);
  return names(a).some((n) => names(b).includes(n));
};

/**
 * Transactions that are probably the same one twice in an account: same account and
 * amount, dated within a few days, and either from different sources (a bank sync that was
 * reconnected or switched provider, plus a manual entry) or with the same payee. Merging
 * uses Actual's own mergeTransactions (26.10+), which keeps the bank-imported one and
 * carries over the category, notes and cleared state from either.
 */
export class DuplicateOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly now: () => number = Date.now,
  ) {}

  find(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const since = new Date(this.now() - LOOKBACK_DAYS * DAY).toISOString().slice(0, 10);
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({ date: { $gte: since }, transfer_id: null, is_child: false, starting_balance_flag: false, 'account.closed': false, amount: { $ne: 0 } })
          .options({ splits: 'grouped' })
          .select([...TX_FIELDS, 'imported_id']),
      )) as { data: Raw[] };
      const dismissed = new Set(this.store.getSetting<string[]>(dismissedKey(budgetId)) ?? []);
      const groups = new Map<string, Raw[]>();
      for (const t of data) {
        const k = `${t.account}|${t.amount}`;
        groups.set(k, [...(groups.get(k) ?? []), t]);
      }
      const candidates: { a: Raw; b: Raw; days: number }[] = [];
      for (const list of groups.values()) {
        if (list.length < 2) continue;
        for (let i = 0; i < list.length; i++) {
          for (let j = i + 1; j < list.length; j++) {
            const a = list[i]!;
            const b = list[j]!;
            const d = days(String(a.date), String(b.date));
            if (d > WINDOW_DAYS || dismissed.has(pairKey(String(a.id), String(b.id)))) continue;
            // Plaid's (and Teller's) ids are stable, so two from the same one are two real transactions.
            if (source(a) === source(b) && (source(a) === 'plaid' || source(a) === 'teller')) continue;
            if (source(a) !== source(b) || samePayee(a, b)) candidates.push({ a, b, days: d });
          }
        }
      }
      // One pair per transaction, closest dates first.
      const used = new Set<string>();
      const pairs = candidates
        .sort((x, y) => x.days - y.days)
        .filter(({ a, b }) => {
          if (used.has(String(a.id)) || used.has(String(b.id))) return false;
          used.add(String(a.id));
          used.add(String(b.id));
          return true;
        })
        .sort((x, y) => String(y.a.date).localeCompare(String(x.a.date)));
      return { pairs: pairs.map((p) => ({ a: toTransaction(p.a), b: toTransaction(p.b), days: p.days })) };
    });
  }

  merge(budgetId: string, ids: [string, string]) {
    const merge = (api as { mergeTransactions?: (ids: [string, string]) => Promise<string> }).mergeTransactions;
    if (typeof merge !== 'function') throw ApiError.featureUnavailable('transactions.merge');
    return this.host.withBudget(budgetId, 'write', async () => {
      let kept: string;
      try {
        kept = await merge(ids);
      } catch (err) {
        // Actual explains why two can't merge (different accounts or amounts, a missing one).
        throw ApiError.validation(err instanceof Error ? err.message : String(err));
      }
      await new Promise<void>((r) => setTimeout(r, 0));
      const { data } = (await api.aqlQuery(api.q('transactions').filter({ id: kept }).options({ splits: 'grouped' }).select(TX_FIELDS))) as { data: Raw[] };
      if (!data[0]) throw new Error('Merged transaction not found');
      return toTransaction(data[0]);
    });
  }

  dismiss(budgetId: string, a: string, b: string) {
    const list = this.store.getSetting<string[]>(dismissedKey(budgetId)) ?? [];
    const key = pairKey(a, b);
    if (!list.includes(key)) this.store.setSetting(dismissedKey(budgetId), [...list, key].slice(-1000));
  }
}
