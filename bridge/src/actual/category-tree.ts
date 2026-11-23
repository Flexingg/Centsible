import { ApiError } from '../errors.js';
import type { HouseholdStore } from '../auth/store.js';

/**
 * Sub-categories. Actual has two levels: a group (level 1) and its categories (level 2).
 * Centsible lets a category sit under another category of the same group, down to level 5,
 * so a category can be a parent and still hold its own transactions and envelope. Actual
 * knows nothing of this: every sub-category is an ordinary category in the group, and the
 * bridge keeps the parent links (per budget). Budgets, rules and sync are untouched.
 */
export const MAX_LEVEL = 5;
/** A group's own categories are level 2. */
const ROOT_LEVEL = 2;

/** The bits of an Actual category the tree needs. */
export type TreeCategory = { id: string; group_id?: unknown; is_income?: unknown };

export class CategoryTree {
  constructor(private readonly store: HouseholdStore) {}

  private key(budgetId: string) {
    return `category-parents.${budgetId}`;
  }

  /** What's stored, links to categories that no longer exist included. */
  private raw(budgetId: string): Record<string, string> {
    return this.store.getSetting<Record<string, string>>(this.key(budgetId)) ?? {};
  }

  private save(budgetId: string, links: Record<string, string>) {
    this.store.setSetting(this.key(budgetId), links);
  }

  /**
   * child id -> parent id, only for links that still hold: both categories exist, in the
   * same group, with no loop, and no deeper than level 5. Anything else (a category deleted
   * or moved in Actual's own app, say) reads as a top-level category of its group.
   */
  parents(budgetId: string, categories: TreeCategory[]): Map<string, string> {
    const byId = new Map(categories.map((c) => [String(c.id), c]));
    const stored = this.raw(budgetId);
    const valid = new Map<string, string>();
    for (const [child, parent] of Object.entries(stored)) {
      const c = byId.get(child);
      const p = byId.get(parent);
      if (!c || !p || child === parent) continue;
      if (String(c.group_id) !== String(p.group_id)) continue;
      valid.set(child, parent);
    }
    // Break loops and cut anything deeper than allowed (keeping the link nearest the top).
    const out = new Map<string, string>();
    const level = (id: string): number => {
      const seen = new Set<string>([id]);
      let n = ROOT_LEVEL;
      let at = valid.get(id);
      while (at !== undefined) {
        if (seen.has(at)) return Infinity;
        seen.add(at);
        n++;
        at = valid.get(at);
      }
      return n;
    };
    for (const [child, parent] of valid) if (level(child) <= MAX_LEVEL) out.set(child, parent);
    // A child whose parent was cut is cut with it.
    let changed = true;
    while (changed) {
      changed = false;
      for (const [child, parent] of out) {
        if (valid.has(parent) && !out.has(parent)) {
          out.delete(child);
          changed = true;
        }
      }
    }
    return out;
  }

  parentOf(budgetId: string, categories: TreeCategory[], id: string): string | null {
    return this.parents(budgetId, categories).get(id) ?? null;
  }

  /** [id] and everything under it, [id] first. */
  subtree(budgetId: string, categories: TreeCategory[], id: string): string[] {
    const parents = this.parents(budgetId, categories);
    const kids = childrenOf(parents);
    const out: string[] = [];
    const walk = (at: string) => {
      out.push(at);
      for (const k of kids.get(at) ?? []) walk(k);
    };
    walk(id);
    return out;
  }

  /**
   * Checks that [id] may go under [parentId] (null = top of its group) and returns the
   * group the whole branch ends up in. Throws a validation error otherwise.
   */
  check(budgetId: string, categories: TreeCategory[], id: string, parentId: string | null): { groupId: string; branch: string[] } {
    const byId = new Map(categories.map((c) => [String(c.id), c]));
    const me = byId.get(id);
    if (!me) throw ApiError.notFound(`Category ${id} not found`);
    const branch = this.subtree(budgetId, categories, id);
    if (parentId === null) return { groupId: String(me.group_id), branch };
    const parent = byId.get(parentId);
    if (!parent) throw ApiError.validation(`Unknown category ${parentId}`);
    if (parentId === id || branch.includes(parentId)) throw ApiError.validation('A category can’t go under itself or one of its own sub-categories.');
    if (!!parent.is_income !== !!me.is_income) throw ApiError.validation('Income and spending categories can’t be nested in each other.');
    const parents = this.parents(budgetId, categories);
    const parentLevel = levelOf(parents, parentId);
    const depth = heightOf(childrenOf(parents), id); // 1 for a category with no sub-categories
    if (parentLevel + depth > MAX_LEVEL) {
      throw ApiError.validation(`Categories go ${MAX_LEVEL} levels deep at most (the group is level 1).`);
    }
    return { groupId: String(parent.group_id), branch };
  }

  /** Stores the link ([parentId] null removes it). Call [check] first. */
  setParent(budgetId: string, id: string, parentId: string | null) {
    const links = this.raw(budgetId);
    if (parentId === null) delete links[id];
    else links[id] = parentId;
    this.save(budgetId, links);
  }

  /** A deleted category's sub-categories move up to its parent (or the top of the group). */
  removed(budgetId: string, categories: TreeCategory[], id: string) {
    const parents = this.parents(budgetId, categories);
    const up = parents.get(id) ?? null;
    const links = this.raw(budgetId);
    delete links[id];
    for (const [child, parent] of parents) {
      if (parent !== id) continue;
      if (up === null) delete links[child];
      else links[child] = up;
    }
    this.save(budgetId, links);
  }
}

function childrenOf(parents: Map<string, string>) {
  const kids = new Map<string, string[]>();
  for (const [child, parent] of parents) kids.set(parent, [...(kids.get(parent) ?? []), child]);
  return kids;
}

function levelOf(parents: Map<string, string>, id: string) {
  let n = ROOT_LEVEL;
  let at = parents.get(id);
  while (at !== undefined) {
    n++;
    at = parents.get(at);
  }
  return n;
}

function heightOf(kids: Map<string, string[]>, id: string): number {
  const below = (kids.get(id) ?? []).map((k) => heightOf(kids, k));
  return 1 + (below.length ? Math.max(...below) : 0);
}
