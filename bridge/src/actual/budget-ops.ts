import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import {
  budgetTypeFromPrefs,
  toAccount,
  toBudgetMonth,
  toCategoryGroup,
  toPayee,
  toTransaction,
  type TransactionDto,
} from '../mappers/index.js';
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
      return Promise.all(accounts.map(async (a) => toAccount(a, await api.getAccountBalance(a.id))));
    });
  }

  categoryGroups(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await api.getCategoryGroups()).map(toCategoryGroup));
  }

  payees(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await api.getPayees()).map(toPayee));
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

  transactions(
    budgetId: string,
    f: { accountId?: string; categoryId?: string; since?: string; until?: string; limit: number; offset: number },
  ) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const filter: Record<string, unknown> = {};
      if (f.accountId) filter.account = f.accountId;
      if (f.categoryId) filter.category = f.categoryId;
      const date: Record<string, string> = {};
      if (f.since) date.$gte = f.since;
      if (f.until) date.$lte = f.until;
      if (Object.keys(date).length) filter.date = date;

      // tier 2: AQL. Fetch one extra row to know whether another page exists.
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter(filter)
          .options({ splits: 'grouped' })
          .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
          .limit(f.limit + 1)
          .offset(f.offset)
          .select(TX_FIELDS),
      )) as { data: Record<string, unknown>[] };

      const items = data.slice(0, f.limit).map(toTransaction);
      return { items, hasMore: data.length > f.limit };
    });
  }

  /**
   * Idempotent create: the client supplies the UUID, so an outbox replay of the same
   * transaction finds the existing row instead of creating a duplicate.
   */
  createTransaction(budgetId: string, tx: NewTransaction): Promise<{ created: boolean; transaction: TransactionDto }> {
    return this.host.withBudget(budgetId, 'write', async () => {
      const existing = await findTransaction(tx.id);
      if (existing) return { created: false, transaction: existing };

      const accounts = await api.getAccounts();
      if (!accounts.some((a) => a.id === tx.accountId)) throw ApiError.validation(`Unknown account ${tx.accountId}`);
      if (tx.subtransactions?.length) {
        const sum = tx.subtransactions.reduce((s, x) => s + x.amount, 0);
        if (sum !== tx.amount) throw ApiError.validation(`Split amounts (${sum}) must add up to the total (${tx.amount})`);
      }

      await api.addTransactions(
        tx.accountId,
        [
          {
            id: tx.id,
            date: tx.date,
            amount: tx.amount,
            payee: tx.payeeId,
            payee_name: tx.payeeId ? undefined : tx.payeeName,
            category: tx.categoryId,
            notes: tx.notes,
            cleared: tx.cleared,
            subtransactions: tx.subtransactions?.map((s) => ({ amount: s.amount, category: s.categoryId, notes: s.notes })),
          } as Parameters<typeof api.addTransactions>[1][number],
        ],
        { learnCategories: true, runTransfers: true },
      );

      const created = await findTransaction(tx.id);
      if (!created) throw new Error(`Transaction ${tx.id} was not found after creation`);
      return { created: true, transaction: created };
    });
  }

  private async readMonth(month: string) {
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

export type NewTransaction = {
  id: string;
  accountId: string;
  date: string;
  amount: number;
  payeeId?: string;
  payeeName?: string;
  categoryId?: string;
  notes?: string;
  cleared?: boolean;
  subtransactions?: { amount: number; categoryId?: string; notes?: string }[];
};

const TX_FIELDS = [
  'id',
  'account',
  'date',
  'amount',
  'payee',
  'payee.name',
  'imported_payee',
  'category',
  'notes',
  'cleared',
  'reconciled',
  'transfer_id',
  'is_parent',
  'parent_id',
];

async function findTransaction(id: string): Promise<TransactionDto | null> {
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ id }).options({ splits: 'grouped' }).select(TX_FIELDS),
  )) as { data: Record<string, unknown>[] };
  return data[0] ? toTransaction(data[0]) : null;
}
