/**
 * Finds what Actual's own "Find schedules" misses: payments from the same payee on (about)
 * the same day each month whose amount moves around (utilities), and pay that lands on
 * business days (the first weekday of the month, and around the 15th), whether or not the
 * amount changes. Pure functions over transactions, so they're easy to test.
 */

export type PatternRow = { date: string; amount: number; payee: string; account: string };

export type FoundPattern = {
  payeeId: string;
  accountId: string;
  income: boolean;
  /** Days of the month (1 = first weekday when [firstWeekday]). */
  days: number[];
  firstWeekday: boolean;
  /** Moved off weekends: 'after' (to Monday) or 'before' (to Friday). */
  weekend: 'before' | 'after';
  description: string;
  occurrences: { date: string; amount: number }[];
  /** Amounts are signed like Actual's (negative for bills). */
  min: number;
  max: number;
  average3: number;
  varies: boolean;
  next: { date: string; amount: number; basis: 'last-year' | 'average' } | null;
};

const MONTHS_BACK = 13;
const MIN_MONTHS = 3;
const DAY_SPREAD = 3;

const ym = (d: string) => d.slice(0, 7);
const day = (d: string) => Number(d.slice(8, 10));
const addMonths = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
const weekday = (d: string) => new Date(`${d}T00:00:00Z`).getUTCDay();
const isWeekend = (d: string) => weekday(d) === 0 || weekday(d) === 6;
const daysIn = (month: string) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
};
const at = (month: string, d: number) => `${month}-${String(Math.min(d, daysIn(month))).padStart(2, '0')}`;
const shift = (date: string, n: number) => new Date(Date.parse(`${date}T00:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);

/** The first Monday-to-Friday of the month. */
export function firstWeekday(month: string) {
  let d = `${month}-01`;
  while (isWeekend(d)) d = shift(d, 1);
  return d;
}

/** A day of the month moved off the weekend. */
export function onBusinessDay(month: string, d: number, mode: 'before' | 'after') {
  let date = at(month, d);
  while (isWeekend(date)) date = shift(date, mode === 'after' ? 1 : -1);
  return date;
}

const median = (xs: number[]) => {
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid]! : Math.round((s[mid - 1]! + s[mid]!) / 2);
};
const ordinal = (n: number) => `${n}${n % 10 === 1 && n !== 11 ? 'st' : n % 10 === 2 && n !== 12 ? 'nd' : n % 10 === 3 && n !== 13 ? 'rd' : 'th'}`;

export function findPatterns(rows: PatternRow[], today: string): FoundPattern[] {
  const thisMonth = ym(today);
  const since = addMonths(thisMonth, -MONTHS_BACK);
  const groups = new Map<string, PatternRow[]>();
  for (const r of rows) {
    if (r.amount === 0 || ym(r.date) < since || r.date > today) continue;
    const k = `${r.payee}|${r.account}|${r.amount > 0 ? '+' : '-'}`;
    groups.set(k, [...(groups.get(k) ?? []), r]);
  }
  const found: FoundPattern[] = [];
  for (const list of groups.values()) {
    const p = patternOf(list, today);
    if (p) found.push(p);
  }
  return found.sort((a, b) => Math.abs(b.average3) - Math.abs(a.average3));
}

function patternOf(list: PatternRow[], today: string): FoundPattern | null {
  const thisMonth = ym(today);
  const byMonth = new Map<string, PatternRow[]>();
  for (const r of list) byMonth.set(ym(r.date), [...(byMonth.get(ym(r.date)) ?? []), r]);
  // Recent and regular: in at least three of the last six months (not counting this one).
  const recent = Array.from({ length: 6 }, (_, i) => addMonths(thisMonth, -1 - i)).filter((m) => byMonth.has(m));
  if (recent.length < MIN_MONTHS) return null;
  const full = [...byMonth.entries()].filter(([m]) => m < thisMonth);
  const perMonth = median(full.map(([, rs]) => rs.length));
  if (perMonth !== 1 && perMonth !== 2) return null; // weekly and daily ones are Actual's to find
  const regular = full.filter(([, rs]) => rs.length === perMonth);
  if (regular.length < MIN_MONTHS) return null;

  const clusters: string[][] = Array.from({ length: perMonth }, () => []);
  for (const [, rs] of regular) [...rs].sort((a, b) => a.date.localeCompare(b.date)).forEach((r, i) => clusters[i]!.push(r.date));

  const days: number[] = [];
  let first = false;
  const modes: ('before' | 'after')[] = [];
  for (const [i, dates] of clusters.entries()) {
    // Always the first weekday of the month (paid on the 1st, or the Monday after).
    if (i === 0 && dates.every((d) => d === firstWeekday(ym(d)))) {
      first = true;
      days.push(1);
      continue;
    }
    const d = median(dates.map(day));
    // Allow a few days either way (weekends, bank processing), for most of the months.
    const close = dates.filter((x) => Math.abs(day(x) - d) <= DAY_SPREAD).length;
    if (close / dates.length < 0.8) return null;
    days.push(d);
    for (const x of dates) {
      const planned = at(ym(x), d);
      if (isWeekend(planned) && x !== planned) modes.push(x > planned ? 'after' : 'before');
    }
  }
  if (days.length === 2 && days[1]! - days[0]! < 7) return null;
  const weekend: 'before' | 'after' = first ? 'after' : modes.filter((m) => m === 'before').length > modes.length / 2 ? 'before' : 'after';

  const sorted = [...list].sort((a, b) => a.date.localeCompare(b.date));
  const amounts = sorted.map((r) => r.amount);
  const lastSix = sorted.filter((r) => ym(r.date) >= addMonths(thisMonth, -6)).map((r) => r.amount);
  const range = lastSix.length ? lastSix : amounts;
  const min = Math.min(...range.map(Math.abs));
  const max = Math.max(...range.map(Math.abs));
  const sign = sorted[0]!.amount > 0 ? 1 : -1;
  const lastThree = sorted.slice(-3);
  const average3 = Math.round(lastThree.reduce((s, r) => s + r.amount, 0) / lastThree.length);
  const varies = max > min * 1.1;

  // The next one: this month's if it hasn't come yet, else next month's first.
  const upcoming: string[] = [];
  for (const m of [thisMonth, addMonths(thisMonth, 1)]) {
    for (const [i, d] of days.entries()) upcoming.push(i === 0 && first ? firstWeekday(m) : onBusinessDay(m, d, weekend));
  }
  const seenThisMonth = (byMonth.get(thisMonth) ?? []).length;
  const nextDate = upcoming.filter((d, i) => d > today && (ym(d) !== thisMonth || i >= seenThisMonth))[0] ?? null;
  let next: FoundPattern['next'] = null;
  if (nextDate) {
    const lastYear = byMonth.get(addMonths(ym(nextDate), -12));
    const index = days.length === 2 && day(nextDate) > days[0]! + 3 ? 1 : 0;
    const ly = lastYear ? [...lastYear].sort((a, b) => a.date.localeCompare(b.date))[Math.min(index, lastYear.length - 1)] : undefined;
    next = ly ? { date: nextDate, amount: ly.amount, basis: 'last-year' } : { date: nextDate, amount: average3, basis: 'average' };
  }

  const describeDay = (d: number, i: number) => (i === 0 && first ? 'the first weekday' : `around the ${ordinal(d)}`);
  const description = `${sign > 0 ? 'Paid' : 'Due'} ${days.map(describeDay).join(' and ')} of each month`;
  return {
    payeeId: list[0]!.payee,
    accountId: list[0]!.account,
    income: sign > 0,
    days,
    firstWeekday: first,
    weekend,
    description,
    occurrences: sorted.slice(-12).map((r) => ({ date: r.date, amount: r.amount })),
    min: sign * min,
    max: sign * max,
    average3,
    varies,
    next,
  };
}

/**
 * What a variable bill will likely be on [date]: the same month last year when there's
 * one, else the average of the last three. [history] is past amounts with their dates.
 */
export function estimateFor(history: { date: string; amount: number }[], date: string): { amount: number; basis: 'last-year' | 'average' } | null {
  if (!history.length) return null;
  const month = addMonths(ym(date), -12);
  const lastYear = history.filter((h) => ym(h.date) === month);
  if (lastYear.length) return { amount: Math.round(lastYear.reduce((s, h) => s + h.amount, 0) / lastYear.length), basis: 'last-year' };
  const recent = [...history].sort((a, b) => a.date.localeCompare(b.date)).slice(-3);
  return { amount: Math.round(recent.reduce((s, h) => s + h.amount, 0) / recent.length), basis: 'average' };
}
