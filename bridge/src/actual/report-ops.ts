import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { OffBooks } from './off-books.js';
import type { ActualHost } from './host.js';

type Row = Record<string, unknown>;

export const monthRange = (start: string, end: string) => {
  const out: string[] = [];
  let [y, m] = start.split('-').map(Number) as [number, number];
  const [ey, em] = end.split('-').map(Number) as [number, number];
  while (y < ey || (y === ey && m <= em)) {
    out.push(`${y}-${String(m).padStart(2, '0')}`);
    if (++m > 12) [y, m] = [y + 1, 1];
    if (out.length > 120) throw ApiError.validation('Ranges are limited to 10 years');
  }
  return out;
};
export const lastDay = (month: string) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return `${month}-${String(new Date(Date.UTC(y, m, 0)).getUTCDate()).padStart(2, '0')}`;
};

/**
 * Money that actually came in or went out of the budget: on-budget accounts, no
 * transfers, no starting balances, split children instead of parents (AQL's default).
 */
/**
 * A date range for AQL. Actual's query engine only applies the first operator of
 * `date: { $gte, $lte }`, so each bound gets its own condition.
 */
export const dateRange = (from: string, to: string, inclusive = true) => ({
  $and: [{ date: { $gte: from } }, { date: inclusive ? { $lte: to } : { $lt: to } }],
});

export const BUDGET_FLOW = { 'account.offbudget': false, transfer_id: null, starting_balance_flag: false };

/** Tier 2: reports are AQL aggregations, computed by Actual's query engine. */
export class ReportOps {
  constructor(
    private readonly host: ActualHost,
    private readonly offBooks?: OffBooks,
  ) {}

  /** [range] plus "not off the books" (both are $and lists, so they're joined, not overwritten). */
  private scoped(budgetId: string, range: Record<string, unknown>) {
    const hidden = (this.offBooks?.filter(budgetId).$and as unknown[] | undefined) ?? [];
    return { $and: [...((range.$and as unknown[] | undefined) ?? []), ...hidden] };
  }

  cashFlow(budgetId: string, start: string, end: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const months = monthRange(start, end);
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({ ...BUDGET_FLOW, ...this.scoped(budgetId, dateRange(`${start}-01`, lastDay(end))) })
          .groupBy([{ $month: '$date' }, 'category.is_income'])
          .select([{ month: { $month: '$date' } }, { isIncome: 'category.is_income' }, { amount: { $sum: '$amount' } }]),
      )) as { data: Row[] };
      return {
        months: months.map((month) => {
          const rows = data.filter((r) => r.month === month);
          const income = rows.filter((r) => r.isIncome === true || r.isIncome === 1).reduce((s, r) => s + Number(r.amount), 0);
          const expenses = rows.filter((r) => !(r.isIncome === true || r.isIncome === 1)).reduce((s, r) => s + Number(r.amount), 0);
          return { month, income, expenses, net: income + expenses };
        }),
      };
    });
  }

  spending(budgetId: string, start: string, end: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const [{ data }, groups] = await Promise.all([
        api.aqlQuery(
          api
            .q('transactions')
            .filter({ ...BUDGET_FLOW, ...this.scoped(budgetId, dateRange(`${start}-01`, lastDay(end))), $or: [{ category: null }, { 'category.is_income': false }] })
            .groupBy(['category'])
            .select(['category', { amount: { $sum: '$amount' } }]),
        ) as Promise<{ data: Row[] }>,
        api.getCategoryGroups(),
      ]);
      const names = new Map(groups.flatMap((g) => (g.categories ?? []).map((c) => [c.id, { name: c.name, group: g.name, groupId: g.id }] as const)));
      const categories = data
        .map((r) => {
          const id = (r.category as string | null) ?? null;
          const meta = id ? names.get(id) : undefined;
          return { categoryId: id, name: meta?.name ?? 'Uncategorized', groupId: meta?.groupId ?? null, groupName: meta?.group ?? null, amount: Number(r.amount) };
        })
        .filter((c) => c.amount !== 0)
        .sort((a, b) => a.amount - b.amount);
      return { start, end, total: categories.reduce((s, c) => s + c.amount, 0), categories };
    });
  }

  /**
   * Spending day by day from [from] to [to] (YYYY-MM-DD, at most 400 days): the same money
   * as the spending report (on budget, no transfers, not income), as positive amounts, with
   * refunds netted. Every day in the range is listed, zero when nothing was spent.
   */
  dailySpending(budgetId: string, from: string, to: string) {
    const days = Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
    if (!(days >= 1)) throw ApiError.validation('from must not be after to');
    if (days > 400) throw ApiError.validation('Ranges are limited to 400 days');
    return this.host.withBudget(budgetId, 'read', async () => {
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({ ...BUDGET_FLOW, ...this.scoped(budgetId, dateRange(from, to)), $or: [{ category: null }, { 'category.is_income': false }] })
          .groupBy(['date'])
          .select(['date', { amount: { $sum: '$amount' } }]),
      )) as { data: Row[] };
      const byDate = new Map(data.map((r) => [String(r.date), -Number(r.amount)]));
      const out: { date: string; amount: number }[] = [];
      for (let i = 0; i < days; i++) {
        const date = new Date(Date.parse(`${from}T00:00:00Z`) + i * 86_400_000).toISOString().slice(0, 10);
        out.push({ date, amount: byDate.get(date) ?? 0 });
      }
      return { from, to, total: out.reduce((s, d) => s + d.amount, 0), days: out };
    });
  }

  /**
   * End-of-month balances across every account, including off-budget ones, in total
   * and per account (accounts that were closed and empty the whole time are left out).
   */
  netWorth(budgetId: string, months: number) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const now = new Date();
      const endMonth = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
      const startDate = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (months - 1), 1));
      const startMonth = startDate.toISOString().slice(0, 7);
      const [{ data }, accounts] = await Promise.all([
        api.aqlQuery(
          api
            .q('transactions')
            // Like account balances: as of today, so an entry dated later this month isn't counted yet.
            .filter({ date: { $lte: now.toISOString().slice(0, 10) } })
            .options({ splits: 'none' })
            .groupBy(['account', { $month: '$date' }])
            .select(['account', { month: { $month: '$date' } }, { amount: { $sum: '$amount' } }]),
        ) as Promise<{ data: Row[] }>,
        api.getAccounts(),
      ]);
      const balances = new Map<string, number>();
      // Everything before the window becomes the opening balance.
      for (const r of data) if (String(r.month) < startMonth) balances.set(String(r.account), (balances.get(String(r.account)) ?? 0) + Number(r.amount));
      const series = new Map<string, number[]>(accounts.map((a) => [a.id, []]));
      const points = monthRange(startMonth, endMonth).map((month) => {
        for (const r of data) if (r.month === month) balances.set(String(r.account), (balances.get(String(r.account)) ?? 0) + Number(r.amount));
        let assets = 0;
        let liabilities = 0;
        for (const v of balances.values()) v >= 0 ? (assets += v) : (liabilities += v);
        for (const [id, list] of series) list.push(balances.get(id) ?? 0);
        return { month, assets, liabilities, netWorth: assets + liabilities };
      });
      return {
        points,
        accounts: accounts
          .map((a) => ({ accountId: a.id, name: a.name, offBudget: Boolean(a.offbudget), closed: Boolean(a.closed), balances: series.get(a.id) ?? [] }))
          .filter((a) => !(a.closed && a.balances.every((b) => b === 0))),
      };
    });
  }
}
