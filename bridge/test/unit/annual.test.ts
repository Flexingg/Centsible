import { describe, expect, it } from 'vitest';
import { annualAmount, yearStart } from '../../src/actual/annual-ops.js';

// $1,200 a year: $100 a month.
const A = 120_000;

/** Walks a year: each month's budget given the spending, with Actual carrying what's left. */
function year(spending: number[]) {
  let before = 0;
  let balance = 0;
  return spending.map((spent, i) => {
    const b = annualAmount(A, i, before, balance, spent);
    before += b;
    balance = Math.max(0, balance + b - spent); // leftovers carry; overspending doesn't
    return b;
  });
}

describe('yearly budgets', () => {
  it('budgets a twelfth a month when nothing is spent, and the year adds up', () => {
    const b = year(Array(12).fill(0));
    expect(b.slice(0, 11)).toEqual(Array(11).fill(10_000));
    expect(b.reduce((s, x) => s + x, 0)).toBe(A);
  });

  it('carries a quiet January into February (the envelope doubles)', () => {
    const b = year([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    // January's 100 stays in the category, so February has 200 to spend.
    expect(b[0]! + b[1]!).toBe(20_000);
  });

  it('covers a yearly bill in March from the rest of the year, then budgets nothing', () => {
    const b = year([0, 0, 120_000, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    expect(b.slice(0, 3)).toEqual([10_000, 10_000, 100_000]);
    expect(b.slice(3)).toEqual(Array(9).fill(0));
  });

  it('never budgets more than the year allows', () => {
    const b = year([0, 200_000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    expect(b.reduce((s, x) => s + x, 0)).toBe(A);
  });

  it('knows which budget year a month is in', () => {
    expect(yearStart('2026-09', 1)).toBe('2026-01');
    expect(yearStart('2026-03', 7)).toBe('2025-07');
    expect(yearStart('2026-07', 7)).toBe('2026-07');
  });
});
