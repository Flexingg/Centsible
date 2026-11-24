import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { toTransaction } from '../mappers/index.js';
import type { ActualHost } from './host.js';
import { OffBooks } from './off-books.js';
import { TX_FIELDS } from './transaction-ops.js';

/** How far back a person's inbox starts the first time they open it. */
const FIRST_LOOK_MS = 14 * 24 * 3600 * 1000;
/** Enough to show and count; an inbox longer than this is "500+". */
const SCAN = 500;

type Raw = Record<string, unknown>;

/**
 * What a person's inbox holds:
 * - `uncategorized` (default): on-budget transactions still waiting for a category.
 * - `uncleared`: anything not yet cleared (to check against the bank).
 * - `new`: everything added since they last caught up, plus anything uncategorized,
 *   minus what they've reviewed one by one.
 *
 * The first two are a state, not a history: a transaction leaves when it gets a category
 * (or is cleared), the same for everyone, so "reviewed" marks only count in `new`.
 */
export const REVIEW_MODES = ['uncategorized', 'uncleared', 'new'] as const;
export type ReviewMode = (typeof REVIEW_MODES)[number];

/** Needs a category: on-budget, not a transfer, not a split parent. */
const NEEDS_CATEGORY = { $and: [{ category: null }, { transfer_id: null }, { is_parent: false }, { 'account.offbudget': false }] };

/**
 * Each person's review inbox (see [ReviewMode]), minus what they've reviewed one by one.
 * Kept by the bridge; Actual has no such flag. "New" uses Actual's sort_order, which is
 * the creation time in ms.
 */
export class ReviewOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly now: () => number = Date.now,
  ) {}

  private get offBooks() {
    return new OffBooks(this.store);
  }

  private since(memberId: string, budgetId: string) {
    let since = this.store.reviewSince(memberId, budgetId);
    if (since === null) {
      since = this.now() - FIRST_LOOK_MS;
      this.store.setReviewSince(memberId, budgetId, since);
    }
    return since;
  }

  mode(memberId: string, budgetId: string): ReviewMode {
    const m = this.store.getSetting<string>(`review.mode.${memberId}.${budgetId}`);
    return (REVIEW_MODES as readonly string[]).includes(m ?? '') ? (m as ReviewMode) : 'uncategorized';
  }

  setMode(memberId: string, budgetId: string, mode: ReviewMode) {
    this.store.setSetting(`review.mode.${memberId}.${budgetId}`, mode);
    return mode;
  }

  private async pending(memberId: string, budgetId: string) {
    const since = this.since(memberId, budgetId);
    const mode = this.mode(memberId, budgetId);
    const reviewed = mode === 'new' ? this.store.reviewedIds(memberId, budgetId) : new Set<string>();
    const which =
      mode === 'uncategorized'
        ? NEEDS_CATEGORY
        : mode === 'uncleared'
          ? { cleared: false }
          : { $or: [{ sort_order: { $gt: since } }, NEEDS_CATEGORY] };
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ starting_balance_flag: false, ...which })
        .options({ splits: 'grouped' })
        .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
        .limit(SCAN + reviewed.size)
        .select([...TX_FIELDS, 'sort_order']),
    )) as { data: Raw[] };
    // Off the books: set aside on purpose, so not for review.
    const offBooks = new Set(this.offBooks.ids(budgetId));
    return { since, mode, rows: data.filter((r) => !reviewed.has(String(r.id)) && !offBooks.has(String(r.category))) };
  }

  inbox(budgetId: string, memberId: string, limit: number) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const { since, mode, rows } = await this.pending(memberId, budgetId);
      return {
        mode,
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

  /**
   * Caught up: the watermark moves to now, and what's still uncategorized is marked too.
   * Only `new` has anything to catch up on; the other modes empty as the work gets done.
   */
  reviewAll(budgetId: string, memberId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const { rows, mode } = await this.pending(memberId, budgetId);
      if (mode !== 'new') return { remaining: rows.length };
      const { data } = (await api.aqlQuery(
        api.q('transactions').orderBy([{ sort_order: 'desc' }]).limit(1).select(['sort_order']),
      )) as { data: Raw[] };
      const newest = Math.max(this.now(), Number(data[0]?.sort_order ?? 0));
      // The watermark doesn't cover the uncategorized (whatever their age): mark them one by one.
      const left = rows.filter((r) => r.category == null).map((r) => String(r.id));
      this.store.setReviewSince(memberId, budgetId, newest);
      this.store.pruneReviewed(memberId, budgetId, []);
      this.store.markReviewed(memberId, budgetId, left);
      return { remaining: 0 };
    });
  }
}
