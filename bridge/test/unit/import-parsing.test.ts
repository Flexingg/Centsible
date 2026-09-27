import { describe, expect, it } from 'vitest';
import { detectCsvMapping, parseAmount, parseDate } from '../../src/actual/account-ops.js';

describe('parseAmount', () => {
  it('reads the formats banks actually export', () => {
    expect(parseAmount('1,234.56')).toBe(123456);
    expect(parseAmount('-$12.00')).toBe(-1200);
    expect(parseAmount('(45.10)')).toBe(-4510);
    expect(parseAmount('12,50')).toBe(1250); // decimal comma
    expect(parseAmount('7-')).toBe(-700); // trailing minus
    expect(parseAmount(-3.333)).toBe(-333);
    expect(parseAmount('')).toBeNull();
    expect(parseAmount('n/a')).toBeNull();
  });
});

describe('parseDate', () => {
  it('handles ISO, OFX and both day/month orders', () => {
    expect(parseDate('2026-09-03', 'MM/dd/yyyy')).toBe('2026-09-03');
    expect(parseDate('20260903120000[-5:EST]', 'MM/dd/yyyy')).toBe('2026-09-03');
    expect(parseDate('09/03/2026', 'MM/dd/yyyy')).toBe('2026-09-03');
    expect(parseDate('03/09/2026', 'dd/MM/yyyy')).toBe('2026-09-03');
    expect(parseDate('9/3/26', 'MM/dd/yy')).toBe('2026-09-03');
  });

  it('rejects impossible dates instead of rolling them over', () => {
    expect(parseDate('02/30/2026', 'MM/dd/yyyy')).toBeNull();
    expect(parseDate('13/01/2026', 'MM/dd/yyyy')).toBeNull();
    expect(parseDate('yesterday', 'MM/dd/yyyy')).toBeNull();
  });
});

describe('detectCsvMapping', () => {
  it('maps common bank headers', () => {
    expect(detectCsvMapping(['Transaction Date', 'Description', 'Amount', 'Memo'])).toMatchObject({
      date: 'Transaction Date',
      payee: 'Description',
      amount: 'Amount',
      notes: 'Memo',
    });
    expect(detectCsvMapping(['Date', 'Payee', 'Money Out', 'Money In'])).toMatchObject({ outflow: 'Money Out', inflow: 'Money In' });
  });
});
