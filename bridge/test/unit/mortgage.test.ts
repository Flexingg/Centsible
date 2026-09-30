import { describe, expect, it } from 'vitest';
import { addMonthsDay, amortize, monthlyPayment } from '../../src/actual/mortgage-ops.js';

describe('mortgage math', () => {
  it('works out the standard payment', () => {
    // $300,000 at 6% over 30 years: $1,798.65 a month.
    expect(monthlyPayment(30_000_000, 6, 360)).toBe(179_865);
    expect(monthlyPayment(1_200_00, 0, 12)).toBe(10_000);
  });

  it('pays off on the last payment, interest first', () => {
    const rows = amortize(30_000_000, 6, 179_865, 0, '2026-01-01');
    expect(rows).toHaveLength(360);
    expect(rows[0]).toMatchObject({ n: 1, date: '2026-01-01', interest: 150_000, principal: 29_865, balance: 29_970_135 });
    expect(rows.at(-1)!.balance).toBe(0);
    expect(rows.at(-1)!.date).toBe('2055-12-01');
  });

  it('pays off years sooner with extra principal', () => {
    const rows = amortize(30_000_000, 6, 179_865, 20_000, '2026-01-01');
    expect(rows.length).toBeLessThan(300);
    expect(rows[0]).toMatchObject({ extra: 20_000, balance: 29_950_135 });
  });

  it('keeps the day of the month, or the last day', () => {
    expect(addMonthsDay('2026-01-31', 1)).toBe('2026-02-28');
    expect(addMonthsDay('2026-01-15', 13)).toBe('2027-02-15');
  });
});
