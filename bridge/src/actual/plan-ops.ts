import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { BudgetOps } from './budget-ops.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;
type MonthCategory = { id: string; name: string; groupName: string; budgeted: number; spent: number; balance: number };

export const AVERAGE_BASES = [3, 6, 12] as const;
export type Basis = (typeof AVERAGE_BASES)[number];

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return d.toISOString().slice(0, 7);
};
const monthsBetween = (from: string, to: string) => {
  const [fy, fm] = from.split('-').map(Number) as [number, number];
  const [ty, tm] = to.split('-').map(Number) as [number, number];
  return (ty - fy) * 12 + (tm - fm);
};
const today = () => new Date().toISOString().slice(0, 10);
const currentMonth = () => today().slice(0, 7);
const addDays = (date: string, n: number) => new Date(Date.parse(`${date}T00:00:00Z`) + n * 86400_000).toISOString().slice(0, 10);
/** Suggestions round up to whole currency units: budgets are easier to read that way. */
const ceilUnit = (cents: number) => Math.ceil(cents / 100) * 100;

/** Expense categories of one month, flattened. Hidden ones are left out, like Actual's averages. */
function expenseCategories(raw: Raw): MonthCategory[] {
  const groups = (raw.categoryGroups as Raw[] | undefined) ?? [];
  return groups
    .filter((g) => !g.is_income && !g.hidden)
    .flatMap((g) =>
      ((g.categories as Raw[] | undefined) ?? [])
        .filter((c) => !c.hidden)
        .map((c) => ({ id: String(c.id), name: String(c.name ?? ''), groupName: String(g.name ?? ''), budgeted: num(c.budgeted), spent: num(c.spent), balance: num(c.balance) })),
    );
}

export type CoverMove = { from: string; fromName: string; to: string; toName: string; amount: number };

/**
 * Monarch-style helpers on top of Actual's envelope budget: suggested amounts from past
 * spending, covering overspending, savings goals and a cash-flow forecast. Everything is
 * computed from Actual's own numbers; writes go through Actual's budget handlers.
 */
export class PlanOps {
  constructor(
    private readonly host: ActualHost,
    private readonly budgets: BudgetOps,
  ) {}

  // ── Autopilot ────────────────────────────────────────────────────────────

  autopilot(budgetId: string, month: string) {
    return this.host.withBudget(budgetId, 'read', () => this.readAutopilot(month));
  }

  /** Sets the chosen categories (default: every one whose suggestion differs) to the suggestion. */
  applyAutopilot(budgetId: string, month: string, basis: Basis, categoryIds?: string[]) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const plan = await this.readAutopilot(month);
      if (categoryIds?.some((id) => !plan.suggestions.some((s) => s.categoryId === id))) throw ApiError.validation('Choose budgeted expense categories.');
      const chosen = plan.suggestions.filter((s) => (categoryIds ? categoryIds.includes(s.categoryId) : s.suggested[`avg${basis}`] !== s.budgeted));
      for (const s of chosen) await api.setBudgetAmount(month, s.categoryId, s.suggested[`avg${basis}`]);
      await settle();
      return { month: await this.budgets.readMonth(month), changed: chosen.length };
    });
  }

  /** Moves money into every overspent category: first from To Budget, then from the categories with the most left. */
  coverOverspending(budgetId: string, month: string) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const plan = await this.readAutopilot(month);
      const prefs = (await api.getPreferences()) as Raw;
      const currencyCode = typeof prefs.defaultCurrencyCode === 'string' ? prefs.defaultCurrencyCode : '';
      for (const m of plan.cover.moves) {
        await this.host.internal('budget.moveMoney', lib, 'budget/cover-overspending', { month, to: m.to, from: m.from, amount: m.amount, currencyCode });
      }
      await settle();
      return { month: await this.budgets.readMonth(month), moves: plan.cover.moves, uncovered: plan.cover.uncovered };
    });
  }

  private async readAutopilot(month: string) {
    const months = await api.getBudgetMonths();
    if (!months.includes(month)) throw ApiError.notFound(`No budget data for ${month}`);
    // Averages look back from last month, never past the current month (as Actual's do).
    const anchor = month > currentMonth() ? currentMonth() : month;
    const past = [...Array(12).keys()].map((i) => addMonths(anchor, -(i + 1))).filter((m) => months.includes(m));
    const [current, ...history] = (await Promise.all([month, ...past].map((m) => api.getBudgetMonth(m)))) as Raw[];
    const cats = expenseCategories(current!);
    const historyCats = history.map(expenseCategories);
    const toBudget = num(current!.toBudget);

    const suggestions = cats.map((c) => {
      const spentByMonth = historyCats.map((h) => h.find((x) => x.id === c.id)).map((x) => (x ? { spent: -x.spent, budgeted: x.budgeted } : { spent: 0, budgeted: 0 }));
      // Months before the category's first activity don't count (a new category isn't "0 a month").
      let active = spentByMonth.length;
      while (active > 0 && spentByMonth[active - 1]!.spent === 0 && spentByMonth[active - 1]!.budgeted === 0) active--;
      const avg = (n: number) => {
        const slice = spentByMonth.slice(0, Math.min(n, active));
        return slice.length ? Math.round(slice.reduce((s, x) => s + x.spent, 0) / slice.length) : 0;
      };
      return {
        categoryId: c.id,
        name: c.name,
        groupName: c.groupName,
        budgeted: c.budgeted,
        balance: c.balance,
        lastMonthSpent: spentByMonth[0]?.spent ?? 0,
        average: { avg3: avg(3), avg6: avg(6), avg12: avg(12) },
        suggested: { avg3: Math.max(0, ceilUnit(avg(3))), avg6: Math.max(0, ceilUnit(avg(6))), avg12: Math.max(0, ceilUnit(avg(12))) },
        monthsOfHistory: active,
      };
    });

    // Cover plan: To Budget first, then the categories with the most left over.
    const overspent = cats.filter((c) => c.balance < 0).sort((a, b) => a.balance - b.balance);
    const sources = [
      ...(toBudget > 0 ? [{ id: 'to-budget', name: 'To Budget', left: toBudget }] : []),
      ...cats.filter((c) => c.balance > 0).sort((a, b) => b.balance - a.balance).map((c) => ({ id: c.id, name: c.name, left: c.balance })),
    ];
    const moves: CoverMove[] = [];
    let uncovered = 0;
    for (const o of overspent) {
      let need = -o.balance;
      for (const s of sources) {
        if (need <= 0) break;
        const take = Math.min(need, s.left);
        if (take <= 0) continue;
        moves.push({ from: s.id, fromName: s.name, to: o.id, toName: o.name, amount: take });
        s.left -= take;
        need -= take;
      }
      uncovered += need;
    }
    return {
      month,
      toBudget,
      suggestions,
      overspent: overspent.map((o) => ({ categoryId: o.id, name: o.name, amount: -o.balance })),
      cover: { moves, uncovered },
    };
  }

  // ── Goals ────────────────────────────────────────────────────────────────

  goals(budgetId: string, month = currentMonth()) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const months = await api.getBudgetMonths();
      const at = months.includes(month) ? month : months.filter((m) => m <= month).pop() ?? months[months.length - 1]!;
      const past = [1, 2, 3].map((i) => addMonths(at, -i)).filter((m) => months.includes(m));
      const [current, ...history] = (await Promise.all([at, ...past].map((m) => api.getBudgetMonth(m)))) as Raw[];
      const cats = expenseCategories(current!);
      const historyCats = history.map(expenseCategories);
      const items = [];
      for (const c of cats) {
        const note = ((await api.getNote(c.id)) as { note?: string | null } | null)?.note ?? '';
        const goal = parseGoal(note);
        if (!goal) continue;
        // What's been going in lately: the average budgeted over up to three earlier months, and this one.
        const contributions = [c.budgeted, ...historyCats.map((h) => h.find((x) => x.id === c.id)?.budgeted ?? 0)];
        const avgContribution = Math.round(contributions.reduce((s, x) => s + x, 0) / contributions.length);
        items.push(describeGoal(c, goal, avgContribution, at));
      }
      return { month: at, items };
    });
  }

  /** Writes the goal as a line in the category's notes (where Actual keeps templates); other lines stay. */
  setGoal(budgetId: string, categoryId: string, goal: GoalInput | null) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const cat = (await api.getCategories()).find((c) => c.id === categoryId);
      if (!cat) throw ApiError.notFound(`Category ${categoryId} not found`);
      if (cat.is_income) throw ApiError.validation('Income categories can’t have savings goals.');
      if (goal?.kind === 'by') {
        if (!goal.targetMonth) throw ApiError.validation('Choose the month to reach the goal by.');
        if (goal.targetMonth < currentMonth()) throw ApiError.validation('Choose a month from now on.');
      }
      const note = ((await api.getNote(categoryId)) as { note?: string | null } | null)?.note ?? '';
      const kept = note.split('\n').filter((line) => !GOAL_LINE.test(line) && !BY_LINE.test(line));
      while (kept.length && kept[kept.length - 1]!.trim() === '') kept.pop();
      if (goal) kept.push(goal.kind === 'balance' ? `#goal ${units(goal.target)}` : `#template ${units(goal.target)} by ${goal.targetMonth}`);
      await api.updateNote(categoryId, kept.join('\n'));
      await settle();
    });
  }

  // ── Forecast and bill calendar ───────────────────────────────────────────

  /**
   * Day-by-day projected balance of the chosen accounts (default: open on-budget
   * accounts): today's balance, plus every scheduled occurrence, plus (optionally) the
   * everyday money that isn't in a schedule, as a daily average of the last 90 days.
   */
  forecast(budgetId: string, opts: { days: number; accountIds?: string[]; includeTypical: boolean }) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      const from = today();
      const to = addDays(from, opts.days);
      const allAccounts = await api.getAccounts();
      const accounts = opts.accountIds?.length
        ? allAccounts.filter((a) => opts.accountIds!.includes(a.id))
        : allAccounts.filter((a) => !a.closed && !a.offbudget);
      if (opts.accountIds?.some((id) => !allAccounts.some((a) => a.id === id))) throw ApiError.validation('Unknown account');
      const ids = new Set(accounts.map((a) => a.id));
      const accountName = new Map(allAccounts.map((a) => [a.id, a.name]));
      const balances = await Promise.all(accounts.map((a) => api.getAccountBalance(a.id)));
      const startingBalance = balances.reduce((s, b) => s + b, 0);

      const payees = await api.getPayees();
      const payee = new Map(payees.map((p) => [p.id, p]));
      const schedules = ((await api.getSchedules()) as unknown as Raw[]).filter((s) => !s.completed && typeof s.next_date === 'string');
      const events: ForecastEvent[] = [];
      for (const s of schedules) {
        const account = typeof s.account === 'string' ? s.account : null;
        if (account && !ids.has(account)) continue; // another account's bill
        const p = typeof s.payee === 'string' ? payee.get(s.payee) : undefined;
        const transferTo = p?.transfer_acct ?? null;
        const amt = s.amount;
        const amount = typeof amt === 'number' ? amt : amt && typeof amt === 'object' ? Math.round((num((amt as Raw).num1) + num((amt as Raw).num2)) / 2) : 0;
        let dates = [String(s.next_date)];
        if (s.date && typeof s.date === 'object') {
          dates = await this.host
            .internal<string[]>('schedules.upcoming', lib, 'schedule/get-upcoming-dates', { config: { ...(s.date as Raw), start: s.next_date }, count: opts.days + 1 })
            .catch(() => [String(s.next_date)]);
          // Actual lists occurrences from today on; an overdue one hasn't been paid yet.
          if (String(s.next_date) < from && !dates.includes(String(s.next_date))) dates.unshift(String(s.next_date));
        }
        for (const date of dates) {
          // Overdue occurrences still count: the money hasn't moved yet.
          const day = date < from ? from : date;
          if (day > to) continue;
          events.push({
            date: day,
            scheduleId: String(s.id),
            name: (typeof s.name === 'string' && s.name) || p?.name || 'Scheduled',
            payeeName: p?.name ?? null,
            accountId: account,
            accountName: account ? (accountName.get(account) ?? null) : null,
            amount,
            // Moving money between two accounts in the forecast doesn't change the total.
            internalTransfer: !!transferTo && ids.has(transferTo) && !!account,
            overdue: date < from,
          });
        }
      }
      events.sort((a, b) => a.date.localeCompare(b.date) || a.amount - b.amount);

      let typicalDaily = 0;
      if (opts.includeTypical && ids.size) {
        const since = addDays(from, -90);
        const { data } = (await api.aqlQuery(
          api
            .q('transactions')
            .filter({ account: { $oneof: [...ids] }, date: { $gte: since, $lt: from }, schedule: null, starting_balance_flag: false })
            .select(['amount', 'transfer_id', 'payee.transfer_acct']),
        )) as { data: Raw[] };
        const flow = data.filter((r) => !(r['payee.transfer_acct'] && ids.has(String(r['payee.transfer_acct'])))).reduce((s, r) => s + num(r.amount), 0);
        typicalDaily = Math.round(flow / 90);
      }

      const days = [];
      let balance = startingBalance;
      let lowest = { date: from, balance };
      for (let i = 0; i <= opts.days; i++) {
        const date = addDays(from, i);
        const scheduled = events.filter((e) => e.date === date && !e.internalTransfer).reduce((s, e) => s + e.amount, 0);
        const typical = i === 0 ? 0 : typicalDaily;
        balance += scheduled + typical;
        days.push({ date, balance, scheduled, typical });
        if (balance < lowest.balance) lowest = { date, balance };
      }
      return { from, to, accountIds: [...ids], startingBalance, typicalDaily, events, days, lowest };
    });
  }
}

type ForecastEvent = {
  date: string;
  scheduleId: string;
  name: string;
  payeeName: string | null;
  accountId: string | null;
  accountName: string | null;
  amount: number;
  internalTransfer: boolean;
  overdue: boolean;
};

export type GoalInput = { kind: 'balance' | 'by'; target: number; targetMonth?: string | null };
type ParsedGoal = { kind: 'balance' | 'by'; target: number; targetMonth: string | null; line: string };

/** `#goal 5000`: keep a balance of at least this. */
const GOAL_LINE = /^\s*#goal\s+\$?([\d,]+(?:\.\d+)?)\s*$/i;
/** `#template 1200 by 2027-06` (optionally `repeat every ...`): save this much by then. */
const BY_LINE = /^\s*#template(?:-\d+)?\s+\$?([\d,]+(?:\.\d+)?)\s+by\s+(\d{4}-\d{2})\b.*$/i;

function parseGoal(note: string): ParsedGoal | null {
  for (const line of note.split('\n')) {
    const g = GOAL_LINE.exec(line);
    if (g) return { kind: 'balance', target: toCents(g[1]!), targetMonth: null, line: line.trim() };
    const b = BY_LINE.exec(line);
    if (b) return { kind: 'by', target: toCents(b[1]!), targetMonth: b[2]!, line: line.trim() };
  }
  return null;
}

const toCents = (s: string) => Math.round(Number(s.replace(/,/g, '')) * 100);
const units = (cents: number) => (cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2));

function describeGoal(c: MonthCategory, g: ParsedGoal, avgContribution: number, month: string) {
  const remaining = Math.max(0, g.target - c.balance);
  const progress = g.target > 0 ? Math.min(1, Math.max(0, c.balance / g.target)) : 1;
  const monthsLeft = g.targetMonth ? Math.max(1, monthsBetween(month, g.targetMonth) + 1) : null;
  const monthlyNeeded = monthsLeft ? Math.ceil(remaining / monthsLeft) : null;
  const projectedMonth = remaining === 0 ? month : avgContribution > 0 ? addMonths(month, Math.ceil(remaining / avgContribution)) : null;
  const status =
    remaining === 0
      ? 'reached'
      : avgContribution <= 0
        ? 'stalled'
        : monthlyNeeded !== null && avgContribution < monthlyNeeded
          ? 'behind'
          : 'on-track';
  return {
    categoryId: c.id,
    name: c.name,
    groupName: c.groupName,
    kind: g.kind,
    target: g.target,
    targetMonth: g.targetMonth,
    balance: c.balance,
    budgetedThisMonth: c.budgeted,
    progress,
    remaining,
    monthlyNeeded,
    avgContribution,
    projectedMonth,
    status,
    line: g.line,
  };
}

const settle = () => new Promise<void>((r) => setTimeout(r, 0));
