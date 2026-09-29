import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { toTransaction } from '../mappers/index.js';
import type { ActualHost } from './host.js';
import { TX_FIELDS } from './transaction-ops.js';

/** How far back a person's inbox starts the first time they open it. */
const FIRST_LOOK_MS = 14 * 24 * 3600 * 1000;
/** Enough to show and count; an inbox longer than this is "500+". */
const SCAN = 500;

type Raw = Record<string, unknown>;

/**
 * Each person's review inbox: transactions added since they last caught up (by Actual's
 * sort_order, which is the creation time in ms) plus anything still uncategorized,
 * minus what they've reviewed one by one. Kept by the bridge; Actual has no such flag.
 */
export class ReviewOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly now: () => number = Date.now,
  ) {}

  private since(memberId: string, budgetId: string) {
    let since = this.store.reviewSince(memberId, budgetId);
    if (since === null) {
      since = this.now() - FIRST_LOOK_MS;
      this.store.setReviewSince(memberId, budgetId, since);
    }
    return since;
  }

  private async pending(memberId: string, budgetId: string) {
    const since = this.since(memberId, budgetId);
    const reviewed = this.store.reviewedIds(memberId, budgetId);
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({
          starting_balance_flag: false,
          $or: [
            { sort_order: { $gt: since } },
            // Needs a category: on-budget, not a transfer, not a split parent.
            { $and: [{ category: null }, { transfer_id: null }, { is_parent: false }, { 'account.offbudget': false }] },
          ],
        })
        .options({ splits: 'grouped' })
        .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
        .limit(SCAN + reviewed.size)
        .select([...TX_FIELDS, 'sort_order']),
    )) as { data: Raw[] };
    return { since, rows: data.filter((r) => !reviewed.has(String(r.id))) };
  }

  inbox(budgetId: string, memberId: string, limit: number) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const { since, rows } = await this.pending(memberId, budgetId);
      return {
        items: rows.slice(0, limit).map(toTransaction),
        total: Math.min(rows.length, SCAN),
        more: rows.length > SCAN,
        since: new Date(since).toISOString(),
      };
    });
  }

  count(budgetId: string, memberId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await this.pending(memberId, budgetId)).rows.length);
  }

  markReviewed(budgetId: string, memberId: string, ids: string[]) {
    this.since(memberId, budgetId);
    this.store.markReviewed(memberId, budgetId, ids);
  }

  /** Caught up: the watermark moves to now, and what's still uncategorized is marked too. */
  reviewAll(budgetId: string, memberId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const { rows } = await this.pending(memberId, budgetId);
      const { data } = (await api.aqlQuery(
        api.q('transactions').orderBy([{ sort_order: 'desc' }]).limit(1).select(['sort_order']),
      )) as { data: Raw[] };
      const newest = Math.max(this.now(), Number(data[0]?.sort_order ?? 0));
      const stillUncategorized = rows.filter((r) => r.category == null).map((r) => String(r.id));
      this.store.setReviewSince(memberId, budgetId, newest);
      this.store.pruneReviewed(memberId, budgetId, []);
      this.store.markReviewed(memberId, budgetId, stillUncategorized);
      return { remaining: 0 };
    });
  }
}
