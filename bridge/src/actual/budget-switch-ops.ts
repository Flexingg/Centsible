import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import { toAccount } from '../mappers/index.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

/** Moving money into these doesn't spend it, so they live off budget (tracked, not budgeted). */
export const OFF_BUDGET_KINDS = ['investment', 'mortgage', 'loan'] as const;
export const belongsOffBudget = (kind: string | null | undefined) => (OFF_BUDGET_KINDS as readonly string[]).includes(kind ?? '');

/** One transfer between this account and an on-budget one: the on-budget side carries the category. */
type Link = { ownId: string; otherId: string; otherCategory: string | null };

/**
 * Moving an account on or off budget after it was made (Actual only asks when creating one).
 *
 * The account's own transactions stay as they are; what changes is how its transfers with
 * on-budget accounts count. Off budget, a transfer from checking leaves the budget, so it
 * needs a category (a 401k contribution in Investing, a mortgage payment in Housing); back on
 * budget, it's a move between two budgeted accounts again and can't have one.
 */
export class BudgetSwitchOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
  ) {}

  private keptKey = (budgetId: string) => `accounts.keptOnBudget.${budgetId}`;

  /** Accounts someone chose to keep on budget although their type usually lives off it. */
  keptOnBudget(budgetId: string): string[] {
    return this.store.getSetting<string[]>(this.keptKey(budgetId)) ?? [];
  }

  private setKept(budgetId: string, id: string, kept: boolean) {
    const now = new Set(this.keptOnBudget(budgetId));
    if (kept) now.add(id);
    else now.delete(id);
    this.store.setSetting(this.keptKey(budgetId), [...now]);
  }

  private async account(id: string) {
    const a = ((await api.getAccounts()) as Raw[]).find((x) => x.id === id);
    if (!a) throw ApiError.notFound(`Account ${id} not found`);
    return a;
  }

  /** Transfers between [id] and accounts that are on budget (other than itself). */
  private async links(id: string): Promise<Link[]> {
    const { data: own } = (await api.aqlQuery(
      api.q('transactions').filter({ account: id, transfer_id: { $ne: null } }).select(['id', 'transfer_id']),
    )) as { data: Raw[] };
    if (own.length === 0) return [];
    const { data: others } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ id: { $oneof: own.map((t) => String(t.transfer_id)) }, 'account.offbudget': false, account: { $ne: id } })
        .select(['id', 'category', 'transfer_id']),
    )) as { data: Raw[] };
    return others.map((o) => ({ ownId: String(o.transfer_id), otherId: String(o.id), otherCategory: o.category ? String(o.category) : null }));
  }

  /** What switching would do, so the app can ask the right question first. */
  preview(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const a = await this.account(id);
      const offBudget = Boolean(a.offbudget);
      const links = await this.links(id);
      // The category most of its transfers already use, to offer first.
      const counts = new Map<string, number>();
      for (const l of links) if (l.otherCategory) counts.set(l.otherCategory, (counts.get(l.otherCategory) ?? 0) + 1);
      const suggested = [...counts.entries()].sort((x, y) => y[1] - x[1])[0]?.[0] ?? null;
      // Back on budget, its own spending needs categories like any other budgeted account.
      let uncategorized = 0;
      if (offBudget) {
        const { data } = (await api.aqlQuery(
          api
            .q('transactions')
            .filter({ account: id, category: null, transfer_id: null, is_parent: false, starting_balance_flag: false })
            .calculate({ $count: '$id' }),
        )) as { data: number };
        uncategorized = data;
      }
      return {
        accountId: id,
        offBudget,
        transfers: links.length,
        categorized: links.filter((l) => l.otherCategory).length,
        suggestedCategoryId: suggested,
        uncategorized,
        keptOnBudget: this.keptOnBudget(budgetId).includes(id),
      };
    });
  }

  /**
   * Off budget: its transfers from on-budget accounts that have no category get [categoryId]
   * (those that already have one keep it). On budget: those transfers lose their categories.
   * Asking for the state it's already in (on budget) records that it stays there on purpose.
   */
  setOffBudget(budgetId: string, id: string, offBudget: boolean, categoryId?: string | null) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const a = await this.account(id);
      if (a.closed) throw ApiError.validation('Reopen the account first.');
      if (categoryId) {
        const cat = ((await api.getCategories()) as Raw[]).find((c) => c.id === categoryId);
        if (!cat) throw ApiError.validation(`Unknown category ${categoryId}`);
        if (cat.is_income) throw ApiError.validation('Choose a spending category: money moved into this account leaves the budget.');
      }
      this.setKept(budgetId, id, !offBudget);
      let categorized = 0;
      let cleared = 0;
      if (Boolean(a.offbudget) !== offBudget) {
        await api.updateAccount(id, { offbudget: offBudget });
        const links = await this.links(id);
        if (offBudget) {
          for (const l of links) {
            if (categoryId && !l.otherCategory) {
              await api.updateTransaction(l.otherId, { category: categoryId });
              categorized++;
            }
          }
        } else {
          // Between two budgeted accounts a transfer has no category (Actual's rule too).
          for (const l of links) {
            if (l.otherCategory) {
              const fields: Record<string, unknown> = { category: null };
              await api.updateTransaction(l.otherId, fields);
              cleared++;
            }
          }
        }
      }
      const after = await this.account(id);
      return {
        account: toAccount(after, await api.getAccountBalance(id), undefined, this.store.accountKinds(budgetId).get(id) ?? null),
        categorized,
        cleared,
      };
    });
  }
}
