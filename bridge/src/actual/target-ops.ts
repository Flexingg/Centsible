import { randomUUID } from 'node:crypto';
import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

/**
 * Goals Actual has no place for, kept by the bridge (per budget, shared by the household):
 * - `account`: grow an account to an amount (optionally by a month), e.g. savings.
 * - `spend-under`: keep a category's (or group's) spending under an amount each month.
 * - `spend-at-least`: put at least an amount, or a share of the month's income, into a
 *   category each month (giving, investing).
 * Savings goals on a category stay in Actual itself (see PlanOps.goals).
 */
export type TargetKind = 'account' | 'spend-under' | 'spend-at-least';
export type TargetInput = {
  kind: TargetKind;
  name?: string | null;
  accountId?: string | null;
  categoryId?: string | null;
  groupId?: string | null;
  amount?: number | null;
  percentOfIncome?: number | null;
  targetMonth?: string | null;
};
export type TargetDef = TargetInput & { id: string; createdAt: string };

type Status = 'reached' | 'on-track' | 'behind' | 'over' | 'stalled';
const HISTORY = 6;

const key = (budgetId: string) => `targets.${budgetId}`;
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
const monthsBetween = (from: string, to: string) => {
  const [fy, fm] = from.split('-').map(Number) as [number, number];
  const [ty, tm] = to.split('-').map(Number) as [number, number];
  return (ty - fy) * 12 + (tm - fm);
};
const lastDay = (month: string) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
};

export class TargetOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly today: () => Date = () => new Date(),
  ) {}

  private defs(budgetId: string) {
    return this.store.getSetting<TargetDef[]>(key(budgetId)) ?? [];
  }

  list(budgetId: string, month?: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const now = this.today();
      const thisMonth = now.toISOString().slice(0, 7);
      const at = month ?? thisMonth;
      const defs = this.defs(budgetId);
      if (!defs.length) return { month: at, items: [] };
      const months = await api.getBudgetMonths();
      const window = Array.from({ length: HISTORY }, (_, i) => addMonths(at, i - HISTORY + 1)).filter((m) => months.includes(m));
      const budgets = new Map<string, Raw>();
      for (const m of window) budgets.set(m, (await api.getBudgetMonth(m)) as Raw);
      const accounts = (await api.getAccounts()) as Raw[];
      // How far through the chosen month we are (1 for a past month).
      const [y, mo] = at.split('-').map(Number) as [number, number];
      const days = new Date(Date.UTC(y, mo, 0)).getUTCDate();
      const pace = at < thisMonth ? 1 : at > thisMonth ? 0 : now.getUTCDate() / days;
      const items = [];
      for (const d of defs) {
        try {
          items.push(d.kind === 'account' ? await this.account(d, at, accounts) : this.spending(d, at, window, budgets, pace));
        } catch {
          // The account or category was deleted: show it as such rather than failing the list.
          items.push({ ...base(d), current: 0, goal: d.amount ?? 0, progress: 0, status: 'stalled' as Status, missing: true, history: [] });
        }
      }
      return { month: at, items };
    });
  }

  create(budgetId: string, input: TargetInput) {
    return this.host.withBudget(budgetId, 'read', async () => {
      await validate(input);
      const def: TargetDef = { ...clean(input), id: randomUUID(), createdAt: new Date().toISOString() };
      this.store.setSetting(key(budgetId), [...this.defs(budgetId), def]);
      return def;
    });
  }

  update(budgetId: string, id: string, input: TargetInput) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const defs = this.defs(budgetId);
      const i = defs.findIndex((d) => d.id === id);
      if (i < 0) throw ApiError.notFound('Goal not found');
      await validate(input);
      const def: TargetDef = { ...clean(input), id, createdAt: defs[i]!.createdAt };
      defs[i] = def;
      this.store.setSetting(key(budgetId), defs);
      return def;
    });
  }

  remove(budgetId: string, id: string) {
    const defs = this.defs(budgetId);
    if (!defs.some((d) => d.id === id)) throw ApiError.notFound('Goal not found');
    this.store.setSetting(key(budgetId), defs.filter((d) => d.id !== id));
  }

  private async account(d: TargetDef, at: string, accounts: Raw[]) {
    const acct = accounts.find((a) => a.id === d.accountId);
    if (!acct) throw new Error('missing');
    const end = lastDay(at);
    const balance = await sum({ account: d.accountId, date: { $lte: end } });
    // Recent pace: the average change over the three months before this one, and this one so far.
    const changes = [];
    for (let i = 0; i < 4; i++) {
      const m = addMonths(at, -i);
      changes.push(await sum({ account: d.accountId, date: { $gte: `${m}-01`, $lte: lastDay(m) } }));
    }
    const avg = Math.round(changes.reduce((s, x) => s + x, 0) / changes.length);
    const target = d.amount ?? 0;
    const remaining = Math.max(0, target - balance);
    const monthsLeft = d.targetMonth ? Math.max(1, monthsBetween(at, d.targetMonth) + 1) : null;
    const monthlyNeeded = monthsLeft ? Math.ceil(remaining / monthsLeft) : null;
    const status: Status = remaining === 0 ? 'reached' : avg <= 0 ? 'stalled' : monthlyNeeded !== null && avg < monthlyNeeded ? 'behind' : 'on-track';
    return {
      ...base(d),
      name: d.name || String(acct.name ?? 'Account'),
      current: balance,
      goal: target,
      progress: target > 0 ? Math.min(1, Math.max(0, balance / target)) : 1,
      status,
      remaining,
      monthlyNeeded,
      avgChange: avg,
      projectedMonth: remaining === 0 ? at : avg > 0 ? addMonths(at, Math.ceil(remaining / avg)) : null,
      history: [],
    };
  }

  private spending(d: TargetDef, at: string, window: string[], budgets: Map<string, Raw>, pace: number) {
    const measure = (m: string) => {
      const raw = budgets.get(m);
      if (!raw) return null;
      const groups = (raw.categoryGroups as Raw[] | undefined) ?? [];
      let spent: number | null = null;
      let name = '';
      for (const g of groups) {
        if (d.groupId && g.id === d.groupId) {
          spent = -num(g.spent);
          name = String(g.name ?? '');
        }
        for (const c of (g.categories as Raw[] | undefined) ?? []) {
          if (d.categoryId && c.id === d.categoryId) {
            spent = -num(c.spent);
            name = String(c.name ?? '');
          }
        }
      }
      if (spent === null) return null;
      const income = num(raw.totalIncome);
      const goal = d.percentOfIncome ? Math.round((income * d.percentOfIncome) / 100) : (d.amount ?? 0);
      return { month: m, value: spent, goal, income, name };
    };
    const now = measure(at);
    if (!now) throw new Error('missing');
    const history = window.map(measure).filter((x): x is NonNullable<typeof x> => x !== null).map(({ month, value, goal }) => ({ month, value, goal }));
    const share = now.goal > 0 ? now.value / now.goal : now.value > 0 ? Infinity : 0;
    let status: Status;
    if (d.kind === 'spend-under') status = share > 1 ? 'over' : pace < 1 && share > pace + 0.1 ? 'behind' : pace >= 1 ? 'reached' : 'on-track';
    else status = now.goal > 0 && share >= 1 ? 'reached' : pace >= 1 || share < pace - 0.1 ? 'behind' : 'on-track';
    const kept = history.filter((h) => (d.kind === 'spend-under' ? h.value <= h.goal : h.value >= h.goal && h.goal > 0)).length;
    return {
      ...base(d),
      name: d.name || now.name,
      current: now.value,
      goal: now.goal,
      income: now.income,
      progress: now.goal > 0 ? Math.min(1, Math.max(0, now.value / now.goal)) : 0,
      status,
      remaining: Math.max(0, d.kind === 'spend-under' ? now.goal - now.value : now.goal - now.value),
      pace,
      monthsKept: kept,
      history,
    };
  }
}

function base(d: TargetDef) {
  return {
    id: d.id,
    kind: d.kind,
    name: d.name ?? '',
    accountId: d.accountId ?? null,
    categoryId: d.categoryId ?? null,
    groupId: d.groupId ?? null,
    amount: d.amount ?? null,
    percentOfIncome: d.percentOfIncome ?? null,
    targetMonth: d.targetMonth ?? null,
  };
}

async function sum(filter: Raw): Promise<number> {
  const { data } = (await api.aqlQuery(api.q('transactions').filter(filter).options({ splits: 'inline' }).calculate({ $sum: '$amount' }))) as { data: unknown };
  return num(data);
}

function clean(i: TargetInput): TargetInput {
  const name = i.name?.trim() || null;
  if (i.kind === 'account') return { kind: i.kind, name, accountId: i.accountId, amount: i.amount, targetMonth: i.targetMonth ?? null };
  return {
    kind: i.kind,
    name,
    categoryId: i.categoryId ?? null,
    groupId: i.categoryId ? null : (i.groupId ?? null),
    amount: i.percentOfIncome ? null : i.amount,
    percentOfIncome: i.kind === 'spend-at-least' ? (i.percentOfIncome ?? null) : null,
  };
}

async function validate(i: TargetInput) {
  if (i.kind === 'account') {
    if (!i.accountId) throw ApiError.validation('Choose the account.');
    if (!((await api.getAccounts()) as Raw[]).some((a) => a.id === i.accountId)) throw ApiError.validation('That account no longer exists.');
    if (!i.amount || i.amount <= 0) throw ApiError.validation('Set the amount to reach.');
    if (i.targetMonth && !/^\d{4}-\d{2}$/.test(i.targetMonth)) throw ApiError.validation('The month should look like 2027-06.');
    return;
  }
  if (!i.categoryId && !i.groupId) throw ApiError.validation('Choose a category or group.');
  const groups = (await api.getCategoryGroups()) as Raw[];
  const found = i.categoryId
    ? groups.some((g) => ((g.categories as Raw[] | undefined) ?? []).some((c) => c.id === i.categoryId))
    : groups.some((g) => g.id === i.groupId);
  if (!found) throw ApiError.validation('That category no longer exists.');
  if (i.kind === 'spend-at-least' && i.percentOfIncome != null) {
    if (!(i.percentOfIncome > 0 && i.percentOfIncome <= 100)) throw ApiError.validation('The share of income should be between 1 and 100%.');
    return;
  }
  if (!i.amount || i.amount <= 0) throw ApiError.validation('Set the monthly amount.');
}
