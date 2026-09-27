import { describe, expect, it } from 'vitest';
import { compareVersions } from '../../src/actual/versions.js';
import { budgetTypeFromPrefs, toBudgetMonth, toTransaction } from '../../src/mappers/index.js';

describe('budgetTypeFromPrefs', () => {
  it('maps current and legacy names, defaulting to envelope', () => {
    expect(budgetTypeFromPrefs({})).toBe('envelope');
    expect(budgetTypeFromPrefs({ budgetType: 'rollover' })).toBe('envelope');
    expect(budgetTypeFromPrefs({ budgetType: 'report' })).toBe('tracking');
    expect(budgetTypeFromPrefs({ budgetType: 'something-new' })).toBe('unknown');
  });
});

describe('toBudgetMonth', () => {
  it('normalizes totalBudgeted to positive and fills missing numbers', () => {
    const m = toBudgetMonth(
      {
        month: '2026-09',
        toBudget: 100,
        totalBudgeted: -40000,
        categoryGroups: [{ id: 'g', name: 'Income', is_income: true, categories: [{ id: 'c', name: 'Pay', received: 5000 }] }],
      },
      'envelope',
    );
    expect(m.totalBudgeted).toBe(40000);
    expect(m.incomeAvailable).toBe(0);
    expect(m.groups[0]!.categories[0]).toEqual({
      id: 'c',
      name: 'Pay',
      hidden: false,
      budgeted: 0,
      spent: 0,
      balance: 0,
      received: 5000,
      carryover: false,
    });
  });

  it('ignores fields it does not know (forward compatible)', () => {
    const m = toBudgetMonth({ month: '2026-09', brandNewField: 1, categoryGroups: [] }, 'envelope');
    expect(m).not.toHaveProperty('brandNewField');
  });
});

describe('toTransaction', () => {
  it('maps AQL rows and nested splits', () => {
    const t = toTransaction({
      id: 'p',
      account: 'a',
      date: '2026-09-05',
      amount: -100,
      'payee.name': 'Costco',
      is_parent: true,
      cleared: 1,
      subtransactions: [{ id: 'c1', account: 'a', date: '2026-09-05', amount: -100, parent_id: 'p', category: 'food' }],
    });
    expect(t).toMatchObject({ payeeName: 'Costco', isParent: true, cleared: true, reconciled: false });
    expect(t.subtransactions[0]).toMatchObject({ parentId: 'p', categoryId: 'food', subtransactions: [] });
  });
});

describe('compareVersions', () => {
  it('compares Actual release lines', () => {
    expect(compareVersions('26.9.0', '26.9.3')).toBe('ok');
    expect(compareVersions('26.10.0', '26.9.0')).toBe('api_newer');
    expect(compareVersions('26.8.1', '26.9.0')).toBe('api_older');
    expect(compareVersions('26.12.0', '27.1.0')).toBe('api_older');
    expect(compareVersions('26.9.0', null)).toBe('unknown');
    expect(compareVersions('26.9.0', 'v26.9.0')).toBe('ok');
  });
});
