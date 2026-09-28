import { describe, expect, it } from 'vitest';
import { periodRange } from '../../src/actual/insights-ops.js';

describe('review periods', () => {
  it('weeks run Monday to Sunday', () => {
    expect(periodRange('week', '2026-09-27')).toEqual({ start: '2026-09-21', end: '2026-09-27', label: 'Week of Sep 21, 2026' });
    expect(periodRange('week', '2026-09-21').start).toBe('2026-09-21');
    expect(periodRange('week', '2027-01-01')).toMatchObject({ start: '2026-12-28', end: '2027-01-03' }); // across the new year
  });

  it('months, quarters and years', () => {
    expect(periodRange('month', '2024-02-10')).toEqual({ start: '2024-02-01', end: '2024-02-29', label: 'February 2024' });
    expect(periodRange('quarter', '2026-08-15')).toEqual({ start: '2026-07-01', end: '2026-09-30', label: 'Q3 2026' });
    expect(periodRange('quarter', '2026-12-31')).toMatchObject({ start: '2026-10-01', end: '2026-12-31', label: 'Q4 2026' });
    expect(periodRange('year', '2025-06-30')).toEqual({ start: '2025-01-01', end: '2025-12-31', label: '2025' });
  });
});
