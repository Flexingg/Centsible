import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

/**
 * A yearly amount for a category (insurance, memberships, gifts), budgeted a twelfth at a
 * time. What isn't spent stays in the category (Actual carries it), so a quiet January
 * leaves twice as much for February. When a bill comes, the month's budget grows to cover
 * it from what's left of the year; once the year's amount is used up, later months get
 * nothing. The last month of the year budgets whatever is left.
 */
export type AnnualDef = { categoryId: string; amount: number; startMonth: number };

const key = (budgetId: string) => `annual.${budgetId}`;
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
const thisMonth = () => new Date().toISOString().slice(0, 7);

/** The first month of the budget year that [month] is in. */
export function yearStart(month: string, startMonth: number) {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return `${m >= startMonth ? y : y - 1}-${String(startMonth).padStart(2, '0')}`;
}

/**
 * What to budget this month. [index] is the month's place in the budget year (0 to 11),
 * [budgetedBefore] what went in earlier this year, [carryIn] what the category had left
 * coming into the month, [spent] this month's spending (positive).
 */
export function annualAmount(amount: number, index: number, budgetedBefore: number, carryIn: number, spent: number) {
  const remaining = Math.max(0, amount - budgetedBefore);
  if (index >= 11) return remaining;
  const share = Math.round(amount / 12);
  // Enough for this month's bills, from what's left of the year, but at least the usual share.
  const needed = Math.max(share, spent - Math.max(0, carryIn));
  return Math.min(remaining, needed);
}

export class AnnualOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
  ) {}

  private defs(budgetId: string) {
    return this.store.getSetting<AnnualDef[]>(key(budgetId)) ?? [];
  }

  list(budgetId: string, month = thisMonth()) {
    return this.host.withBudget(budgetId, 'read', async () => ({ month, items: await this.plan(budgetId, month) }));
  }

  set(budgetId: string, categoryId: string, amount: number, startMonth: number) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const cat = (await api.getCategories()).find((c) => c.id === categoryId);
      if (!cat) throw ApiError.notFound(`Category ${categoryId} not found`);
      if (cat.is_income) throw ApiError.validation('Income categories can’t have a yearly budget.');
      const defs = this.defs(budgetId).filter((d) => d.categoryId !== categoryId);
      this.store.setSetting(key(budgetId), [...defs, { categoryId, amount, startMonth }]);
      // This month follows right away.
      await this.applyIn(budgetId, thisMonth(), [categoryId]);
      return (await this.plan(budgetId, thisMonth())).find((p) => p.categoryId === categoryId)!;
    });
  }

  remove(budgetId: string, categoryId: string) {
    const defs = this.defs(budgetId);
    if (!defs.some((d) => d.categoryId === categoryId)) throw ApiError.notFound('No yearly budget on that category');
    this.store.setSetting(key(budgetId), defs.filter((d) => d.categoryId !== categoryId));
  }

  /** Sets this month's budget for every yearly category. */
  apply(budgetId: string, month = thisMonth()) {
    return this.host.withBudget(budgetId, 'write', () => this.applyIn(budgetId, month));
  }

  /** For the bank sync hook and the scheduler: inside an open budget session. */
  async applyIn(budgetId: string, month: string, only?: string[]) {
    const changes = [];
    for (const p of await this.plan(budgetId, month)) {
      if (only && !only.includes(p.categoryId)) continue;
      if (p.budgeted === p.suggested) continue;
      await api.setBudgetAmount(month, p.categoryId, p.suggested);
      changes.push({ categoryId: p.categoryId, from: p.budgeted, to: p.suggested });
    }
    if (changes.length) await new Promise<void>((r) => setTimeout(r, 0));
    return { month, changes };
  }

  private lastTick = 0;

  /**
   * Called every minute by the scheduler; once an hour it brings this month's yearly
   * budgets up to date, so a bill entered by hand is covered without opening the app.
   */
  async tick(now = Date.now()) {
    if (now - this.lastTick < 3600_000 || !this.host.connected) return false;
    this.lastTick = now;
    for (const b of await this.host.listBudgets()) {
      if (!this.defs(b.id).length) continue;
      await this.host.withBudget(b.id, 'write', () => this.applyIn(b.id, thisMonth())).catch(() => undefined);
    }
    return true;
  }

  private async plan(budgetId: string, month: string) {
    const defs = this.defs(budgetId);
    if (!defs.length) return [];
    const months = new Map<string, Raw>();
    const load = async (m: string) => {
      if (!months.has(m)) months.set(m, (await api.getBudgetMonth(m).catch(() => null)) as Raw);
      return months.get(m);
    };
    const find = (raw: Raw | null | undefined, id: string) => {
      for (const g of ((raw?.categoryGroups as Raw[] | undefined) ?? [])) for (const c of (g.categories as Raw[] | undefined) ?? []) if (c.id === id) return c;
      return null;
    };
    const out = [];
    for (const d of defs) {
      const start = yearStart(month, d.startMonth);
      const index = (Number(month.slice(0, 4)) - Number(start.slice(0, 4))) * 12 + Number(month.slice(5)) - Number(start.slice(5));
      let before = 0;
      for (let i = 0; i < index; i++) before += num(find(await load(addMonths(start, i)), d.categoryId)?.budgeted);
      const now = find(await load(month), d.categoryId);
      if (!now) continue; // deleted or hidden category
      const prev = find(await load(addMonths(month, -1)), d.categoryId);
      const carryIn = num(prev?.balance);
      const spent = -num(now.spent);
      out.push({
        categoryId: d.categoryId,
        name: String(now.name ?? ''),
        amount: d.amount,
        startMonth: d.startMonth,
        monthIndex: index,
        budgetedBefore: before,
        remaining: Math.max(0, d.amount - before),
        spentThisMonth: spent,
        carryIn,
        budgeted: num(now.budgeted),
        suggested: annualAmount(d.amount, index, before, carryIn, spent),
      });
    }
    return out;
  }
}
