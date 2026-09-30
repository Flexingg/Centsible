import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';
import { findPatterns } from './recurring-patterns.js';
import { BUDGET_FLOW, dateRange, lastDay } from './report-ops.js';

type Raw = Record<string, unknown>;
type Flow = {
  id: string;
  date: string;
  amount: number;
  payeeId: string | null;
  payeeName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  income: boolean;
};

const DAY = 86400_000;
const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const today = () => iso(Date.now());
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
const daysIn = (month: string) => Number(lastDay(month).slice(8));
const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid]! : (s[mid - 1]! + s[mid]!) / 2;
};
const pct = (now: number, before: number) => (before === 0 ? null : Math.round(((now - before) / Math.abs(before)) * 100));

/** Money in and out of the budget over [from, to]: on-budget, no transfers or starting balances, split children. */
async function flows(from: string, to: string): Promise<Flow[]> {
  const { data } = (await api.aqlQuery(
    api
      .q('transactions')
      .filter({ ...BUDGET_FLOW, ...dateRange(from, to) })
      .select(['id', 'date', 'amount', 'payee', 'payee.name', 'category', 'category.name', 'category.is_income']),
  )) as { data: Raw[] };
  return data.map((r) => ({
    id: String(r.id),
    date: String(r.date),
    amount: Number(r.amount),
    payeeId: (r.payee as string | null) ?? null,
    payeeName: (r['payee.name'] as string | null) ?? null,
    categoryId: (r.category as string | null) ?? null,
    categoryName: (r['category.name'] as string | null) ?? null,
    income: r['category.is_income'] === true || r['category.is_income'] === 1,
  }));
}

const isExpense = (f: Flow) => !f.income && f.amount < 0;

export type Insight = {
  id: string;
  kind: 'category-pace' | 'unusual-transaction' | 'new-merchant' | 'price-change';
  severity: 'good' | 'info' | 'warning';
  title: string;
  detail: string;
  amount: number;
  date: string;
  categoryId: string | null;
  transactionId: string | null;
  scheduleId: string | null;
};

const money = (cents: number) => {
  const a = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}$${Math.floor(a / 100).toLocaleString('en-US')}${a % 100 ? `.${String(a % 100).padStart(2, '0')}` : ''}`;
};

/**
 * Monarch-style insights computed from Actual's transactions: spending pace per
 * category, unusual charges, recurring-payment discovery and price changes, and a
 * year in review. Everything reads; the only writes are schedules the app creates
 * through the normal schedules endpoint.
 */
export class InsightsOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
  ) {}

  // ── #10 Trends and alerts ──────────────────────────────────────────────

  insights(budgetId: string, month = today().slice(0, 7)) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const now = today();
      const current = now.slice(0, 7);
      if (month > current) throw ApiError.validation('Choose this month or an earlier one.');
      const days = daysIn(month);
      const elapsed = month === current ? Number(now.slice(8)) : days;
      const since = `${addMonths(month, -12)}-01`;
      const all = await flows(since, month === current ? now : lastDay(month));
      const inMonth = all.filter((f) => f.date.startsWith(month));
      const typicalMonths = [1, 2, 3].map((i) => addMonths(month, -i));

      // Spending by category: this month (projected to month end) against the last three months.
      const byCategory = new Map<string, { name: string; spent: number; history: number[] }>();
      const key = (f: Flow) => f.categoryId ?? 'uncategorized';
      for (const f of all) {
        if (f.income) continue;
        const k = key(f);
        const entry = byCategory.get(k) ?? { name: f.categoryName ?? 'Uncategorized', spent: 0, history: [0, 0, 0] };
        if (f.date.startsWith(month)) entry.spent -= f.amount;
        const i = typicalMonths.findIndex((m) => f.date.startsWith(m));
        if (i >= 0) entry.history[i]! -= f.amount;
        byCategory.set(k, entry);
      }
      const categories = [...byCategory.entries()]
        .map(([id, c]) => {
          const typical = Math.round(c.history.reduce((s, x) => s + x, 0) / 3);
          // Early in the month a straight-line projection is too jumpy; wait a week.
          const projected = elapsed >= 7 ? Math.round((c.spent * days) / elapsed) : c.spent;
          return { categoryId: id === 'uncategorized' ? null : id, name: c.name, spent: c.spent, typical, projected, changePct: pct(projected, typical) };
        })
        .filter((c) => c.spent !== 0 || c.typical !== 0)
        .sort((a, b) => b.spent - a.spent);

      const alerts: Insight[] = [];
      for (const c of categories) {
        if (c.typical <= 0 || elapsed < 7) continue;
        const diff = c.projected - c.typical;
        const change = c.changePct ?? 0;
        const soFar = month === current ? `${money(c.spent)} so far, on pace for ${money(c.projected)}` : `${money(c.spent)} this month`;
        if (change >= 30 && diff >= 5000) {
          alerts.push({
            id: `pace:${c.categoryId}:${month}`, kind: 'category-pace', severity: 'warning',
            title: `${c.name} is ${change}% above usual`,
            detail: `${soFar}. You usually spend about ${money(c.typical)}.`,
            amount: diff, date: now, categoryId: c.categoryId, transactionId: null, scheduleId: null,
          });
        } else if (change <= -30 && -diff >= 5000 && elapsed >= 14) {
          alerts.push({
            id: `pace:${c.categoryId}:${month}`, kind: 'category-pace', severity: 'good',
            title: `${c.name} is ${-change}% below usual`,
            detail: `${soFar}, against about ${money(c.typical)} normally. Nice.`,
            amount: diff, date: now, categoryId: c.categoryId, transactionId: null, scheduleId: null,
          });
        }
      }

      // Unusual charges: well above what that merchant usually costs, or a big first visit.
      const recentFrom = month === current ? iso(Date.parse(now) - 30 * DAY) : `${month}-01`;
      const expenses = all.filter(isExpense);
      const bigThreshold = Math.max(10000, percentile(expenses.filter((f) => f.date >= iso(Date.parse(now) - 90 * DAY)).map((f) => -f.amount), 0.9));
      for (const f of expenses.filter((x) => x.date >= recentFrom && (month === current || x.date.startsWith(month)))) {
        if (!f.payeeId) continue;
        const earlier = expenses.filter((x) => x.payeeId === f.payeeId && x.date < f.date);
        if (earlier.length >= 3) {
          const usual = median(earlier.map((x) => -x.amount));
          if (-f.amount > usual * 2 && -f.amount - usual >= 2000) {
            alerts.push({
              id: `unusual:${f.id}`, kind: 'unusual-transaction', severity: 'warning',
              title: `Bigger than usual at ${f.payeeName ?? 'a merchant'}`,
              detail: `${money(-f.amount)} on ${f.date}. It's usually about ${money(Math.round(usual))}.`,
              amount: f.amount, date: f.date, categoryId: f.categoryId, transactionId: f.id, scheduleId: null,
            });
          }
        } else if (earlier.length === 0 && -f.amount >= bigThreshold && !(await seenBefore(f.payeeId, since))) {
          alerts.push({
            id: `new:${f.id}`, kind: 'new-merchant', severity: 'info',
            title: `First time at ${f.payeeName ?? 'a new merchant'}`,
            detail: `${money(-f.amount)} on ${f.date}. Worth a look if you don't recognize it.`,
            amount: f.amount, date: f.date, categoryId: f.categoryId, transactionId: f.id, scheduleId: null,
          });
        }
      }

      for (const p of await priceChanges()) {
        if (p.date < iso(Date.parse(now) - 45 * DAY)) continue;
        alerts.push({
          id: `price:${p.scheduleId}:${p.date}`, kind: 'price-change', severity: p.latest < p.previous ? 'warning' : 'good',
          title: `${p.name} ${p.latest < p.previous ? 'went up' : 'went down'}`,
          detail: `${money(-p.latest)} now, was ${money(-p.previous)} (${p.changePct > 0 ? '+' : ''}${p.changePct}%).`,
          amount: p.latest - p.previous, date: p.date, categoryId: null, transactionId: null, scheduleId: p.scheduleId,
        });
      }

      const rank = { warning: 0, info: 1, good: 2 } as const;
      alerts.sort((a, b) => rank[a.severity] - rank[b.severity] || b.date.localeCompare(a.date));
      const spent = inMonth.filter((f) => !f.income).reduce((s, f) => s - f.amount, 0);
      const typicalTotal = categories.reduce((s, c) => s + c.typical, 0);
      return {
        month,
        daysElapsed: elapsed,
        daysInMonth: days,
        spent,
        typical: typicalTotal,
        projected: elapsed >= 7 ? Math.round((spent * days) / elapsed) : spent,
        categories,
        alerts,
      };
    });
  }

  // ── #11 Subscriptions and recurring payments ────────────────────────────

  /**
   * Recurring payments Actual can see in the history but that have no schedule yet
   * (Actual's own discovery, the one behind "Find schedules"), plus price changes on
   * existing schedules.
   */
  subscriptions(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      const found = await this.host.internal<Raw[]>('schedules.discover', lib, 'schedule/discover', {});
      const [payees, accounts] = await Promise.all([api.getPayees(), api.getAccounts()]);
      const dismissed = new Set(this.store.getSetting<string[]>(`subscriptions.dismissed.${budgetId}`) ?? []);
      const candidates = [];
      for (const s of found ?? []) {
        const payeeId = String(s.payee);
        if (dismissed.has(payeeId)) continue;
        const payee = payees.find((p) => p.id === payeeId);
        if (payee?.transfer_acct) continue;
        const config = s.date as Raw;
        const conds = (s._conditions as Raw[] | undefined) ?? [];
        const amount = Number(s.amount);
        const frequency = String(config.frequency);
        const interval = Number(config.interval ?? 1);
        const perYear = frequency === 'weekly' ? 52 / interval : frequency === 'monthly' ? 12 / interval : frequency === 'yearly' ? 1 / interval : 365 / interval;
        const { data: last } = (await api.aqlQuery(
          api.q('transactions').filter({ payee: payeeId, account: String(s.account) }).select(['date', 'amount']).orderBy({ date: 'desc' }).limit(1),
        )) as { data: Raw[] };
        candidates.push({
          payeeId,
          payeeName: payee?.name ?? 'Unknown',
          accountId: String(s.account),
          accountName: accounts.find((a) => a.id === s.account)?.name ?? null,
          amount,
          approximateAmount: conds.some((c) => c.field === 'amount' && c.op === 'isapprox'),
          recurrence: { frequency, interval, start: String(config.start), ...(config.patterns ? { patterns: config.patterns } : {}) },
          lastDate: (last[0]?.date as string | undefined) ?? null,
          yearlyAmount: Math.round(amount * perYear),
          income: amount > 0,
        });
      }
      candidates.sort((a, b) => a.yearlyAmount - b.yearlyAmount);

      // Bills that vary and business-day pay, which Actual's discovery misses.
      const now = today();
      const since = `${Number(now.slice(0, 4)) - 2}${now.slice(4, 7)}-01`;
      const { data: history } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter({ date: { $gte: since }, transfer_id: null, is_child: false, starting_balance_flag: false, payee: { $ne: null }, 'account.closed': false })
          .options({ splits: 'inline' })
          .select(['date', 'amount', 'payee', 'account']),
      )) as { data: Raw[] };
      const scheduled = new Set(((await api.getSchedules()) as unknown as Raw[]).filter((s) => !s.completed).map((s) => String(s.payee)));
      const patterns = [];
      for (const p of findPatterns(
        history.map((r) => ({ date: String(r.date), amount: Number(r.amount), payee: String(r.payee), account: String(r.account) })),
        now,
      )) {
        const payee = payees.find((x) => x.id === p.payeeId);
        if (!payee || payee.transfer_acct || dismissed.has(p.payeeId) || scheduled.has(p.payeeId)) continue;
        const actualFound = candidates.findIndex((c) => c.payeeId === p.payeeId);
        // A steady amount on a plain day of the month: Actual's own suggestion covers it.
        if (actualFound >= 0 && !p.varies && !p.firstWeekday && p.days.length === 1) continue;
        if (actualFound >= 0) candidates.splice(actualFound, 1);
        patterns.push({ ...p, payeeName: payee.name, accountName: accounts.find((a) => a.id === p.accountId)?.name ?? null });
      }
      return { candidates, patterns, priceChanges: await priceChanges() };
    });
  }

  /** Hides a suggestion for good (per budget). */
  dismissSubscription(budgetId: string, payeeId: string) {
    const k = `subscriptions.dismissed.${budgetId}`;
    const list = new Set(this.store.getSetting<string[]>(k) ?? []);
    list.add(payeeId);
    this.store.setSetting(k, [...list]);
  }

  // ── #12 Year in review ──────────────────────────────────────────────────

  /** The year's story; see [review]. */
  yearInReview(budgetId: string, year: number) {
    return this.review(budgetId, 'year', `${year}-01-01`);
  }

  /**
   * A period's story (week, month, quarter or year), Spotify Wrapped style: totals,
   * top categories and merchants, the biggest purchase, spending over the period in
   * buckets (days for a week or month, weeks for a quarter, months for a year),
   * no-spend days, and the change against the period before (the same number of
   * elapsed days, while the period is still going). [anchor] is any date in the
   * period; without one, the most recently finished period.
   */
  review(budgetId: string, period: ReviewPeriod, anchor?: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const now = today();
      const range = periodRange(period, anchor ?? defaultAnchor(period, now));
      if (range.start > now) throw ApiError.validation('That period hasn’t started yet.');
      const complete = range.end < now;
      const end = complete ? range.end : now;
      const elapsedDays = daysBetween(range.start, end) + 1;
      const prev = periodRange(period, iso(Date.parse(`${range.start}T00:00:00Z`) - DAY));
      const prevEnd = complete ? prev.end : minDate(prev.end, iso(Date.parse(`${prev.start}T00:00:00Z`) + (elapsedDays - 1) * DAY));
      const [all, previous, { data: months0 }] = await Promise.all([
        flows(range.start, end),
        flows(prev.start, prevEnd),
        api.aqlQuery(api.q('transactions').filter(BUDGET_FLOW).groupBy([{ $month: '$date' }]).select([{ month: { $month: '$date' } }])) as Promise<{ data: Raw[] }>,
      ]);
      const availableYears = [...new Set(months0.map((r) => Number(String(r.month).slice(0, 4))))].filter(Number.isFinite).sort((a, b) => b - a);
      const nextRange = periodRange(period, iso(Date.parse(`${range.end}T00:00:00Z`) + DAY));
      const base = {
        period,
        start: range.start,
        end: range.end,
        label: range.label,
        previousStart: prev.start,
        nextStart: nextRange.start <= now ? nextRange.start : null,
        year: Number(range.start.slice(0, 4)),
        complete,
        availableYears,
      };
      if (!all.length) return { ...base, empty: true, ...emptyReview() };

      const income = all.filter((f) => f.income).reduce((s, f) => s + f.amount, 0);
      const spending = -all.filter((f) => !f.income).reduce((s, f) => s + f.amount, 0);
      const expenses = all.filter(isExpense);

      const sumBy = <K extends string>(rows: Flow[], key: (f: Flow) => K | null) => {
        const m = new Map<K, { amount: number; count: number; name: string }>();
        for (const f of rows) {
          const k = key(f);
          if (k === null) continue;
          const e = m.get(k) ?? { amount: 0, count: 0, name: '' };
          e.amount -= f.amount;
          e.count++;
          m.set(k, e);
        }
        return m;
      };
      const cats = sumBy(all.filter((f) => !f.income), (f) => f.categoryId ?? 'uncategorized');
      for (const [k, v] of cats) v.name = all.find((f) => (f.categoryId ?? 'uncategorized') === k)?.categoryName ?? 'Uncategorized';
      const topCategories = [...cats.entries()]
        .filter(([, v]) => v.amount > 0)
        .sort((a, b) => b[1].amount - a[1].amount)
        .slice(0, 5)
        .map(([k, v]) => ({ categoryId: k === 'uncategorized' ? null : k, name: v.name, amount: v.amount, share: spending > 0 ? v.amount / spending : 0 }));

      const merchants = sumBy(expenses, (f) => f.payeeId);
      for (const [k, v] of merchants) v.name = expenses.find((f) => f.payeeId === k)?.payeeName ?? 'Unknown';
      const merchantList = [...merchants.entries()].map(([k, v]) => ({ payeeId: k, name: v.name, amount: v.amount, visits: v.count }));
      const topMerchants = [...merchantList].sort((a, b) => b.amount - a.amount).slice(0, 5);
      const mostVisited = [...merchantList].sort((a, b) => b.visits - a.visits || b.amount - a.amount)[0] ?? null;
      const biggest = expenses.reduce<Flow | null>((m, f) => (!m || f.amount < m.amount ? f : m), null);

      const buckets = bucketsFor(period, range).map((b) => {
        const rows = all.filter((f) => f.date >= b.start && f.date <= b.end);
        return {
          key: b.key,
          label: b.label,
          start: b.start,
          spending: -rows.filter((f) => !f.income).reduce((s, f) => s + f.amount, 0),
          income: rows.filter((f) => f.income).reduce((s, f) => s + f.amount, 0),
        };
      });
      const active = buckets.filter((b) => b.start <= end && (b.spending !== 0 || b.income !== 0));
      const biggestBucket = active.reduce<(typeof buckets)[number] | null>((m, x) => (!m || x.spending > m.spending ? x : m), null);
      const smallestBucket = active.reduce<(typeof buckets)[number] | null>((m, x) => (!m || x.spending < m.spending ? x : m), null);
      // Kept for the year view (and older apps): buckets are months there.
      const months = period === 'year' ? buckets.map((b) => ({ month: b.key, spending: b.spending, income: b.income })) : [];
      const asMonth = (b: (typeof buckets)[number] | null) => (b && period === 'year' ? { month: b.key, spending: b.spending, income: b.income } : null);

      // No-spend days: days in the period so far without a single purchase.
      const spendDays = new Set(expenses.map((f) => f.date));
      let noSpendDays = 0;
      let streak = 0;
      let longest = { days: 0, start: null as string | null, end: null as string | null };
      let streakStart: string | null = null;
      // A year starts counting from the first transaction (so a budget begun in March isn't "60 no-spend days").
      const firstDay = period === 'year' ? Math.max(Date.parse(`${range.start}T00:00:00Z`), Date.parse(`${all.reduce((m, f) => (f.date < m ? f.date : m), end)}T00:00:00Z`)) : Date.parse(`${range.start}T00:00:00Z`);
      for (let t = firstDay; t <= Date.parse(`${end}T00:00:00Z`); t += DAY) {
        const d = iso(t);
        if (spendDays.has(d)) {
          streak = 0;
          streakStart = null;
          continue;
        }
        noSpendDays++;
        streak++;
        streakStart ??= d;
        if (streak > longest.days) longest = { days: streak, start: streakStart, end: d };
      }
      const daysCovered = Math.round((Date.parse(`${end}T00:00:00Z`) - firstDay) / DAY) + 1;

      // New: merchants with no transaction at all before this period.
      const { data: before } = (await api.aqlQuery(
        api.q('transactions').filter({ date: { $lt: range.start }, payee: { $oneof: merchantList.map((m) => m.payeeId) } }).groupBy(['payee']).select(['payee']),
      )) as { data: Raw[] };
      const known = new Set(before.map((r) => String(r.payee)));
      const newMerchants = merchantList.filter((m) => !known.has(m.payeeId)).length;
      const prevSpending = -previous.filter((f) => !f.income).reduce((s, f) => s + f.amount, 0);
      const prevIncome = previous.filter((f) => f.income).reduce((s, f) => s + f.amount, 0);
      const previousPeriod = previous.length ? { spending: prevSpending, income: prevIncome, spendingChangePct: pct(spending, prevSpending), label: prev.label } : null;

      return {
        ...base,
        empty: false,
        income,
        spending,
        saved: income - spending,
        savingsRate: income > 0 ? Math.round(((income - spending) / income) * 100) : null,
        purchases: expenses.length,
        dailyAverage: Math.round(spending / Math.max(1, daysCovered)),
        topCategories,
        topMerchants,
        mostVisited,
        biggestPurchase: biggest && { transactionId: biggest.id, date: biggest.date, payeeName: biggest.payeeName, categoryName: biggest.categoryName, amount: -biggest.amount },
        buckets,
        biggestBucket,
        smallestBucket,
        months,
        biggestMonth: asMonth(biggestBucket),
        smallestMonth: asMonth(smallestBucket),
        noSpendDays,
        longestNoSpendStreak: longest,
        newMerchants,
        merchantsVisited: merchantList.length,
        previousPeriod,
        previousYear: period === 'year' ? previousPeriod : null,
      };
    });
  }
}

export const REVIEW_PERIODS = ['week', 'month', 'quarter', 'year'] as const;
export type ReviewPeriod = (typeof REVIEW_PERIODS)[number];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const LONG_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const utc = (d: string) => new Date(`${d}T00:00:00Z`);
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / DAY);
const minDate = (a: string, b: string) => (a < b ? a : b);
const short = (d: string) => `${MONTHS[utc(d).getUTCMonth()]} ${utc(d).getUTCDate()}`;

/** The week (Monday to Sunday), month, quarter or year containing [date]. */
export function periodRange(period: ReviewPeriod, date: string): { start: string; end: string; label: string } {
  const d = utc(date);
  const y = d.getUTCFullYear();
  const m = d.getUTCMonth();
  switch (period) {
    case 'week': {
      const start = iso(d.getTime() - ((d.getUTCDay() + 6) % 7) * DAY);
      return { start, end: iso(Date.parse(`${start}T00:00:00Z`) + 6 * DAY), label: `Week of ${short(start)}, ${start.slice(0, 4)}` };
    }
    case 'month':
      return { start: iso(Date.UTC(y, m, 1)), end: iso(Date.UTC(y, m + 1, 0)), label: `${LONG_MONTHS[m]} ${y}` };
    case 'quarter': {
      const q = Math.floor(m / 3);
      return { start: iso(Date.UTC(y, q * 3, 1)), end: iso(Date.UTC(y, q * 3 + 3, 0)), label: `Q${q + 1} ${y}` };
    }
    case 'year':
      return { start: `${y}-01-01`, end: `${y}-12-31`, label: String(y) };
  }
}

/** Without a date: the last finished period. A year is "wrapped" from November on, like the app's card. */
function defaultAnchor(period: ReviewPeriod, now: string): string {
  if (period === 'year') return utc(now).getUTCMonth() >= 10 ? now : `${Number(now.slice(0, 4)) - 1}-06-30`;
  return iso(Date.parse(`${periodRange(period, now).start}T00:00:00Z`) - DAY);
}

/** Days for a week or month, weeks for a quarter, months for a year. */
function bucketsFor(period: ReviewPeriod, r: { start: string; end: string }) {
  const out: { key: string; label: string; start: string; end: string }[] = [];
  if (period === 'week' || period === 'month') {
    for (let t = Date.parse(`${r.start}T00:00:00Z`); t <= Date.parse(`${r.end}T00:00:00Z`); t += DAY) {
      const d = iso(t);
      out.push({ key: d, label: period === 'week' ? DAYS[(utc(d).getUTCDay() + 6) % 7]! : String(utc(d).getUTCDate()), start: d, end: d });
    }
  } else if (period === 'quarter') {
    let start = periodRange('week', r.start).start;
    while (start <= r.end) {
      const end = iso(Date.parse(`${start}T00:00:00Z`) + 6 * DAY);
      const s = start < r.start ? r.start : start;
      out.push({ key: s, label: short(s), start: s, end: minDate(end, r.end) });
      start = iso(Date.parse(`${start}T00:00:00Z`) + 7 * DAY);
    }
  } else {
    for (let i = 0; i < 12; i++) {
      const s = iso(Date.UTC(Number(r.start.slice(0, 4)), i, 1));
      out.push({ key: s.slice(0, 7), label: MONTHS[i]!, start: s, end: iso(Date.UTC(Number(r.start.slice(0, 4)), i + 1, 0)) });
    }
  }
  return out;
}

function emptyReview() {
  return {
    income: 0, spending: 0, saved: 0, savingsRate: null, purchases: 0, dailyAverage: 0, topCategories: [], topMerchants: [], mostVisited: null,
    biggestPurchase: null, buckets: [], biggestBucket: null, smallestBucket: null, months: [], biggestMonth: null, smallestMonth: null,
    noSpendDays: 0, longestNoSpendStreak: { days: 0, start: null, end: null }, newMerchants: 0, merchantsVisited: 0, previousPeriod: null, previousYear: null,
  };
}

function percentile(xs: number[], p: number) {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))]!;
}

async function seenBefore(payeeId: string, before: string) {
  const { data } = (await api.aqlQuery(api.q('transactions').filter({ payee: payeeId, date: { $lt: before } }).select(['id']).limit(1))) as { data: Raw[] };
  return data.length > 0;
}

/** Schedules whose latest payment differs from the one before by at least 5% (and $1). */
async function priceChanges() {
  const schedules = ((await api.getSchedules()) as unknown as Raw[]).filter((s) => !s.completed);
  const payees = await api.getPayees();
  const out = [];
  for (const s of schedules) {
    const { data } = (await api.aqlQuery(
      api.q('transactions').filter({ schedule: String(s.id) }).select(['date', 'amount']).orderBy([{ date: 'desc' }, { sort_order: 'desc' }]).limit(2),
    )) as { data: Raw[] };
    if (data.length < 2) continue;
    const latest = Number(data[0]!.amount);
    const previous = Number(data[1]!.amount);
    if (latest >= 0 || previous >= 0) continue; // bills only; a raise isn't a price change
    const changePct = pct(-latest, -previous) ?? 0;
    if (Math.abs(latest - previous) < 100 || Math.abs(changePct) < 5) continue;
    const payee = payees.find((p) => p.id === s.payee);
    out.push({
      scheduleId: String(s.id),
      name: (typeof s.name === 'string' && s.name) || payee?.name || 'Recurring payment',
      previous,
      latest,
      changePct,
      date: String(data[0]!.date),
    });
  }
  return out;
}
