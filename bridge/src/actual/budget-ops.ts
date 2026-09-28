import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import {
  budgetTypeFromPrefs,
  toPreferences,
  toAccount,
  toBudgetMonth,
  toCategoryGroup,
  toPayee,
} from '../mappers/index.js';
import { AccountOps } from './account-ops.js';
import type { ActualHost } from './host.js';

/**
 * Budget operations over @actual-app/api. Tier 1 = public API methods, tier 2 = AQL
 * queries, tier 3 = internal handlers via host.internal() (capability-gated).
 */
export class BudgetOps {
  constructor(private readonly host: ActualHost) {}

  accounts(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const accounts = await api.getAccounts(); // tier 1
      const sync = await AccountOps.syncInfo(); // tier 2: bank link status
      return Promise.all(accounts.map(async (a) => toAccount(a, await api.getAccountBalance(a.id), sync.get(a.id))));
    });
  }

  categoryGroups(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await api.getCategoryGroups()).map(toCategoryGroup));
  }

  payees(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await api.getPayees()).map(toPayee));
  }

  preferences(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => toPreferences((await api.getPreferences()) as Record<string, unknown>));
  }

  months(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', () => api.getBudgetMonths());
  }

  month(budgetId: string, month: string) {
    return this.host.withBudget(budgetId, 'read', () => this.readMonth(month));
  }

  updateCategoryBudget(budgetId: string, month: string, categoryId: string, patch: { budgeted?: number; carryover?: boolean }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.assertExpenseCategory(categoryId);
      if (patch.budgeted !== undefined) await api.setBudgetAmount(month, categoryId, patch.budgeted);
      if (patch.carryover !== undefined) await api.setBudgetCarryover(month, categoryId, patch.carryover);
      return this.readMonth(month);
    });
  }

  moveMoney(budgetId: string, month: string, t: { from: string; to: string; amount: number }) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      if (t.from === t.to) throw ApiError.validation('from and to must differ');
      for (const id of [t.from, t.to]) if (id !== 'to-budget') await this.assertExpenseCategory(id);
      const prefs = (await api.getPreferences()) as Record<string, unknown>;
      await this.host.internal('budget.moveMoney', lib, 'budget/transfer-category', {
        month,
        amount: t.amount,
        from: t.from,
        to: t.to,
        currencyCode: typeof prefs.defaultCurrencyCode === 'string' ? prefs.defaultCurrencyCode : '',
      });
      return this.readMonth(month);
    });
  }

  hold(budgetId: string, month: string, amount: number | null) {
    return this.host.withBudget(budgetId, 'write', async () => {
      if (amount === null) await api.resetBudgetHold(month);
      else await api.holdBudgetForNextMonth(month, amount);
      return this.readMonth(month);
    });
  }

  /**
   * Fills budgets from `#template` lines in category notes (tier 3). `overwrite` replaces
   * amounts already budgeted; otherwise only empty categories are filled.
   */
  applyTemplates(budgetId: string, month: string, overwrite: boolean) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const result = await this.host.internal<{ type?: string; message?: string; pre?: string }>(
        'budget.templates',
        lib,
        overwrite ? 'budget/overwrite-goal-template' : 'budget/apply-goal-template',
        { month },
      );
      const monthDto = await this.readMonth(month);
      return { month: monthDto, message: templateMessage(result) };
    });
  }

  /** Validates every #template line and says what's wrong (tier 3). */
  checkTemplates(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      const result = await this.host.internal<{ type?: string; message?: string; pre?: string }>('budget.templates', lib, 'budget/check-templates', {});
      return { ok: result?.type !== 'error', message: templateMessage(result) };
    });
  }

  /** The month as the app sees it. Other ops return it after changing budget amounts. */
  async readMonth(month: string) {
    const months = await api.getBudgetMonths();
    if (!months.includes(month)) throw ApiError.notFound(`No budget data for ${month}`);
    const [raw, prefs] = await Promise.all([api.getBudgetMonth(month), api.getPreferences()]);
    return toBudgetMonth(raw as Record<string, unknown>, budgetTypeFromPrefs(prefs as Record<string, unknown>));
  }

  private async assertExpenseCategory(categoryId: string) {
    const cat = (await api.getCategories()).find((c) => c.id === categoryId);
    if (!cat) throw ApiError.validation(`Unknown category ${categoryId}`);
    if (cat.is_income) throw ApiError.validation('Income categories are not budgeted in envelope mode');
  }
}

function templateMessage(r: { type?: string; message?: string; pre?: string } | null | undefined): string {
  const known: Record<string, string> = {
    'templates-applied': 'Goals applied',
    'templates-check-passed': 'All goal templates look good',
    'no-templates': 'No categories have #template notes yet',
  };
  const base = r?.message ? (known[r.message] ?? r.message) : 'Done';
  return r?.pre ? `${base}: ${r.pre}` : base;
}
