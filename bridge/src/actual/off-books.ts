import type { HouseholdStore } from '../auth/store.js';

type Raw = Record<string, unknown>;

/**
 * Categories kept "off the books": a holding place for transactions that are still to be
 * matched or sorted out. Actual has no such flag, so the bridge keeps the list (per budget)
 * and leaves those transactions out of the review queue, reports, insights, reviews,
 * subscriptions and autopilot. In Actual they are ordinary categories with rollover on,
 * so their balance waits there instead of coming out of next month's To Budget.
 */
export class OffBooks {
  constructor(private readonly store: HouseholdStore) {}

  private key(budgetId: string) {
    return `offbooks.${budgetId}`;
  }

  ids(budgetId: string): string[] {
    return this.store.getSetting<string[]>(this.key(budgetId)) ?? [];
  }

  has(budgetId: string, categoryId: string | null | undefined) {
    return !!categoryId && this.ids(budgetId).includes(categoryId);
  }

  set(budgetId: string, categoryId: string, on: boolean) {
    const now = new Set(this.ids(budgetId));
    if (on) now.add(categoryId);
    else now.delete(categoryId);
    this.store.setSetting(this.key(budgetId), [...now]);
    return [...now];
  }

  /** An AQL filter fragment leaving those categories out (uncategorized stays in). */
  filter(budgetId: string): Raw {
    const ids = this.ids(budgetId);
    if (!ids.length) return {};
    return { $and: ids.map((id) => ({ $or: [{ category: null }, { category: { $ne: id } }] })) };
  }
}
