import { describe, expect, it } from 'vitest';
import { HouseholdStore } from '../../src/auth/store.js';
import { CategoryTree } from '../../src/actual/category-tree.js';
import { toBudgetMonth, toCategoryGroup } from '../../src/mappers/index.js';

const b = 'budget-1';
const detail = (f: () => unknown) => {
  try {
    f();
  } catch (e) {
    return String((e as { detail?: string }).detail);
  }
  return 'no error';
};
const cat = (id: string, group = 'g1', is_income = false) => ({ id, group_id: group, is_income });

function tree(links: Record<string, string>) {
  const store = new HouseholdStore(':memory:', { accessSec: 60, refreshSec: 3600 }, () => 0);
  store.setSetting(`category-parents.${b}`, links);
  return new CategoryTree(store);
}

describe('CategoryTree', () => {
  const cats = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => cat(id));

  it('keeps links that hold, and drops ones to deleted categories or other groups', () => {
    const t = tree({ b: 'a', c: 'b', x: 'a', d: 'gone', e: 'a' });
    const parents = t.parents(b, [...cats.filter((c) => c.id !== 'e'), cat('e', 'g2')]);
    expect(Object.fromEntries(parents)).toEqual({ b: 'a', c: 'b' });
  });

  it('cuts loops and anything below level 5', () => {
    // a(2) > b(3) > c(4) > d(5) > e(6): e is cut, and f pointing at itself is ignored.
    const t = tree({ b: 'a', c: 'b', d: 'c', e: 'd', f: 'f' });
    expect(Object.fromEntries(t.parents(b, cats))).toEqual({ b: 'a', c: 'b', d: 'c' });
    const loop = tree({ a: 'b', b: 'a', c: 'a' });
    expect(loop.parents(b, cats).size).toBe(0);
  });

  it('checks depth for a whole branch, loops and income', () => {
    const t = tree({ b: 'a', c: 'b' }); // a > b > c
    expect(detail(() => t.check(b, cats, 'a', 'c'))).toMatch(/under itself/);
    // d > e is two deep; under c (level 4) it would reach level 6.
    const t2 = tree({ b: 'a', c: 'b', e: 'd' });
    expect(detail(() => t2.check(b, cats, 'd', 'c'))).toMatch(/5 levels/);
    expect(t2.check(b, cats, 'd', 'b').branch).toEqual(['d', 'e']);
    expect(detail(() => t.check(b, [...cats, cat('i', 'g1', true)], 'i', 'a'))).toMatch(/Income/);
  });

  it('moves children up when their parent is removed', () => {
    const t = tree({ b: 'a', c: 'b', d: 'b' });
    t.removed(b, cats, 'b');
    expect(Object.fromEntries(t.parents(b, cats.filter((c) => c.id !== 'b')))).toEqual({ c: 'a', d: 'a' });
    const top = tree({ b: 'a' });
    top.removed(b, cats, 'a');
    expect(top.parents(b, cats.filter((c) => c.id !== 'a')).size).toBe(0);
  });

  it('feeds parentId into the mappers', () => {
    const parents = new Map([['c2', 'c1']]);
    const g = toCategoryGroup({ id: 'g', name: 'G', categories: [{ id: 'c1', name: 'A', group_id: 'g' }, { id: 'c2', name: 'B', group_id: 'g' }] }, parents);
    expect(g.categories.map((c) => c.parentId)).toEqual([null, 'c1']);
    const m = toBudgetMonth({ month: '2026-11', categoryGroups: [{ id: 'g', categories: [{ id: 'c2' }] }] }, 'envelope', parents);
    expect(m.groups[0]!.categories[0]!.parentId).toBe('c1');
  });
});
