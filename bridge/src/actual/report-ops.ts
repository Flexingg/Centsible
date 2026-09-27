import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';

type Row = Record<string, unknown>;

const monthRange = (start: string, end: string) => {
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
const lastDay = (month: string) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return `${month}-${String(new Date(Date.UTC(y, m, 0)).getUTCDate()).padStart(2, '0')}`;
};

/**
 * Money that actually came in or went out of the budget: on-budget accounts, no
 * transfers, no starting balances, split children instead of parents (AQL's default).
 */
const BUDGET_FLOW = { 'account.offbudget': false, transfer_id: null, starting_balance_flag: false };

/** Tier 2: reports are AQL aggregations, computed by Actual's query engine. */
export class ReportOps {
  constructor(private readonly host: ActualHost) {}

  cashFlow(budgetId: string, start: string, end: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const months = monthRange(start, end);
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({ ...BUDGET_FLOW, date: { $gte: `${start}-01`, $lte: lastDay(end) } })
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
            .filter({ ...BUDGET_FLOW, date: { $gte: `${start}-01`, $lte: lastDay(end) }, $or: [{ category: null }, { 'category.is_income': false }] })
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

  /** End-of-month balances across every account, including off-budget ones. */
  netWorth(budgetId: string, months: number) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const now = new Date();
      const endMonth = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
      const startDate = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (months - 1), 1));
      const startMonth = startDate.toISOString().slice(0, 7);
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .options({ splits: 'none' })
          .groupBy(['account', { $month: '$date' }])
          .select(['account', { month: { $month: '$date' } }, { amount: { $sum: '$amount' } }]),
      )) as { data: Row[] };
      const balances = new Map<string, number>();
      // Everything before the window becomes the opening balance.
      for (const r of data) if (String(r.month) < startMonth) balances.set(String(r.account), (balances.get(String(r.account)) ?? 0) + Number(r.amount));
      return {
        points: monthRange(startMonth, endMonth).map((month) => {
          for (const r of data) if (r.month === month) balances.set(String(r.account), (balances.get(String(r.account)) ?? 0) + Number(r.amount));
          let assets = 0;
          let liabilities = 0;
          for (const v of balances.values()) v >= 0 ? (assets += v) : (liabilities += v);
          return { month, assets, liabilities, netWorth: assets + liabilities };
        }),
      };
    });
  }
}
