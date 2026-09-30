import { describe, expect, it } from 'vitest';
import { estimateFor, findPatterns, firstWeekday, onBusinessDay, type PatternRow } from '../../src/actual/recurring-patterns.js';

const months = (from: string, n: number) =>
  Array.from({ length: n }, (_, i) => {
    const [y, m] = from.split('-').map(Number) as [number, number];
    return new Date(Date.UTC(y, m - 1 + i, 1)).toISOString().slice(0, 7);
  });

describe('recurring patterns', () => {
  it('finds a utility bill that varies, and projects from the same month last year', () => {
    // Electric: around the 12th, more in summer.
    const amounts = [-9000, -8500, -8000, -9500, -12000, -16000, -18000, -17500, -13000, -9800, -8800, -9100, -8700];
    const rows: PatternRow[] = months('2025-09', 13).map((m, i) => ({ date: `${m}-${i % 3 === 0 ? 11 : i % 3 === 1 ? 12 : 14}`, amount: amounts[i]!, payee: 'power', account: 'chk' }));
    const [p] = findPatterns(rows, '2026-09-30');
    expect(p).toMatchObject({ payeeId: 'power', income: false, days: [12], firstWeekday: false, varies: true });
    expect(p!.description).toBe('Due around the 12th of each month');
    // October 2026: last October was -8500.
    expect(p!.next).toEqual({ date: '2026-10-12', amount: -8500, basis: 'last-year' });
    expect(p!.min).toBe(-8700);
    expect(p!.max).toBe(-18000);
  });

  it('finds pay on the first weekday and around the 15th, whatever the amount', () => {
    const rows: PatternRow[] = [];
    for (const [i, m] of months('2026-03', 7).entries()) {
      rows.push({ date: firstWeekday(m), amount: 410000 + i * 3700, payee: 'job', account: 'chk' });
      rows.push({ date: onBusinessDay(m, 15, 'before'), amount: 395000 - i * 2100, payee: 'job', account: 'chk' });
    }
    const [p] = findPatterns(rows, '2026-09-20');
    expect(p).toMatchObject({ income: true, days: [1, 15], firstWeekday: true });
    expect(p!.description).toBe('Paid the first weekday and around the 15th of each month');
    // October 1st 2026 is a Thursday.
    expect(p!.next?.date).toBe('2026-10-01');
    expect(p!.next?.basis).toBe('average');
  });

  it('ignores one-offs and things that come weekly', () => {
    const rows: PatternRow[] = [
      { date: '2026-05-03', amount: -5000, payee: 'shop', account: 'chk' },
      { date: '2026-08-20', amount: -7000, payee: 'shop', account: 'chk' },
      ...months('2026-04', 6).flatMap((m) => [1, 8, 15, 22].map((d) => ({ date: `${m}-${String(d).padStart(2, '0')}`, amount: -1200, payee: 'coffee', account: 'chk' }))),
    ];
    expect(findPatterns(rows, '2026-09-30')).toEqual([]);
  });

  it('estimates a bill from last year, else the last three', () => {
    const h = [
      { date: '2025-10-12', amount: -8000 },
      { date: '2026-07-12', amount: -15000 },
      { date: '2026-08-12', amount: -16000 },
      { date: '2026-09-12', amount: -11000 },
    ];
    expect(estimateFor(h, '2026-10-12')).toEqual({ amount: -8000, basis: 'last-year' });
    expect(estimateFor(h, '2026-11-12')).toEqual({ amount: -14000, basis: 'average' });
  });
});
