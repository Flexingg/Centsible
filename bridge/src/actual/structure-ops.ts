import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import { toAccount, toCategory, toCategoryGroup } from '../mappers/index.js';
import type { HouseholdStore } from '../auth/store.js';
import type { AccountKind } from './account-kinds.js';
import type { ActualHost } from './host.js';
import { MAX_LEVEL, type CategoryTree, type TreeCategory } from './category-tree.js';

/** Accounts, categories and category groups (all tier 1). */
export class StructureOps {
  constructor(
    private readonly host: ActualHost,
    /** Where account types live (Actual has none). */
    private readonly store?: HouseholdStore,
    /** Sub-categories (Actual has two levels). */
    private readonly tree?: CategoryTree,
  ) {}

  createAccount(budgetId: string, input: { name: string; offBudget: boolean; initialBalance: number; kind?: AccountKind | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const id = await api.createAccount({ name: input.name.trim(), offbudget: input.offBudget }, input.initialBalance);
      if (input.kind) this.store?.setAccountKind(budgetId, id, input.kind);
      return this.account(id, budgetId);
    });
  }

  updateAccount(budgetId: string, id: string, patch: { name?: string; kind?: AccountKind | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.account(id);
      if (patch.name !== undefined) await api.updateAccount(id, { name: patch.name.trim() });
      if (patch.kind !== undefined) this.store?.setAccountKind(budgetId, id, patch.kind);
      return this.account(id, budgetId);
    });
  }

  closeAccount(budgetId: string, id: string, opts: { transferAccountId?: string; transferCategoryId?: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const account = await this.account(id);
      if (account.balance !== 0 && !opts.transferAccountId) {
        throw ApiError.validation('This account has a balance. Choose an account to move it to.');
      }
      await api.closeAccount(id, opts.transferAccountId, opts.transferCategoryId);
      // Actual deletes an account outright when it has no transactions.
      const after = (await api.getAccounts()).find((a) => a.id === id);
      if (!after) this.store?.setAccountKind(budgetId, id, null);
      return after ? toAccount(after, await api.getAccountBalance(id), undefined, this.kindOf(budgetId, id)) : null;
    });
  }

  reopenAccount(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.account(id);
      await api.reopenAccount(id);
      return this.account(id, budgetId);
    });
  }

  /**
   * [parentId] makes it a sub-category (it goes in the parent's group, and [groupId] may be
   * left out). Without one it's a top-level category of [groupId].
   */
  createCategory(budgetId: string, input: { name: string; groupId?: string; parentId?: string | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const cats = await this.allCategories();
      let groupId = input.groupId;
      if (input.parentId) {
        if (!this.tree) throw ApiError.validation('Sub-categories aren’t available');
        const parent = cats.find((c) => c.id === input.parentId);
        if (!parent) throw ApiError.validation(`Unknown category ${input.parentId}`);
        if (levelBelow(this.tree, budgetId, cats, input.parentId) > MAX_LEVEL) {
          throw ApiError.validation(`Categories go ${MAX_LEVEL} levels deep at most (the group is level 1).`);
        }
        groupId = String(parent.group_id);
      }
      if (!groupId) throw ApiError.validation('Choose a group or a parent category');
      const group = (await api.getCategoryGroups()).find((g) => g.id === groupId);
      if (!group) throw ApiError.validation(`Unknown category group ${groupId}`);
      const id = await api.createCategory({ name: input.name.trim(), group_id: groupId, is_income: !!group.is_income, hidden: false });
      if (input.parentId) this.tree!.setParent(budgetId, id, input.parentId);
      return this.category(id, budgetId);
    });
  }

  /**
   * [parentId] moves it (and everything under it) under another category, into that
   * category's group; null brings it to the top of its group. A new [groupId] alone moves
   * the whole branch to the top of that group.
   */
  updateCategory(budgetId: string, id: string, patch: { name?: string; hidden?: boolean; groupId?: string; parentId?: string | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.category(id, budgetId);
      const fields: Record<string, unknown> = {};
      if (patch.name !== undefined) fields.name = patch.name.trim();
      if (patch.hidden !== undefined) fields.hidden = patch.hidden;
      let branch: string[] = [];
      let targetGroup: string | undefined;
      if (patch.parentId !== undefined) {
        if (!this.tree) throw ApiError.validation('Sub-categories aren’t available');
        const checked = this.tree.check(budgetId, (await this.allCategories()) as TreeCategory[], id, patch.parentId);
        if (patch.parentId === null && patch.groupId !== undefined) checked.groupId = patch.groupId;
        branch = checked.branch;
        targetGroup = checked.groupId;
      } else if (patch.groupId !== undefined) {
        const cats = (await this.allCategories()) as TreeCategory[];
        branch = this.tree ? this.tree.subtree(budgetId, cats, id) : [id];
        targetGroup = patch.groupId;
        // Leaving the group means leaving the parent too.
        const current = cats.find((c) => c.id === id);
        if (current && String(current.group_id) !== patch.groupId) this.tree?.setParent(budgetId, id, null);
      }
      if (Object.keys(fields).length) await api.updateCategory(id, fields);
      if (targetGroup !== undefined) {
        const cats = await this.allCategories();
        for (const member of branch) {
          const c = cats.find((x) => x.id === member);
          if (c && String(c.group_id) !== targetGroup) await api.updateCategory(member, { group_id: targetGroup });
        }
      }
      if (patch.parentId !== undefined) this.tree!.setParent(budgetId, id, patch.parentId);
      return this.category(id, budgetId);
    });
  }

  deleteCategory(budgetId: string, id: string, transferCategoryId?: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.category(id, budgetId);
      if (transferCategoryId === id) throw ApiError.validation('Pick a different category to move transactions to');
      const before = (await this.allCategories()) as TreeCategory[];
      await api.deleteCategory(id, transferCategoryId);
      // Its sub-categories move up a level.
      this.tree?.removed(budgetId, before, id);
    });
  }

  createGroup(budgetId: string, input: { name: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const id = await api.createCategoryGroup({ name: input.name.trim(), is_income: false, hidden: false });
      return this.group(id, budgetId);
    });
  }

  updateGroup(budgetId: string, id: string, patch: { name?: string; hidden?: boolean }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.group(id, budgetId);
      const fields: Record<string, unknown> = {};
      if (patch.name !== undefined) fields.name = patch.name.trim();
      if (patch.hidden !== undefined) fields.hidden = patch.hidden;
      if (Object.keys(fields).length) await api.updateCategoryGroup(id, fields);
      return this.group(id, budgetId);
    });
  }

  deleteGroup(budgetId: string, id: string, transferCategoryId?: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const group = await this.group(id, budgetId);
      if (group.isIncome) throw ApiError.validation("The income group can't be deleted");
      await api.deleteCategoryGroup(id, transferCategoryId);
    });
  }

  private async account(id: string, budgetId?: string) {
    const a = (await api.getAccounts()).find((x) => x.id === id);
    if (!a) throw ApiError.notFound(`Account ${id} not found`);
    return toAccount(a, await api.getAccountBalance(id), undefined, budgetId ? this.kindOf(budgetId, id) : null);
  }

  private kindOf(budgetId: string, id: string) {
    return this.store?.accountKinds(budgetId).get(id) ?? null;
  }

  private async allCategories() {
    return (await api.getCategories()) as (TreeCategory & Record<string, unknown>)[];
  }

  private async category(id: string, budgetId: string) {
    const cats = await this.allCategories();
    const c = cats.find((x) => x.id === id);
    if (!c) throw ApiError.notFound(`Category ${id} not found`);
    return toCategory(c, this.tree?.parents(budgetId, cats) ?? new Map());
  }

  private async group(id: string, budgetId: string) {
    const g = (await api.getCategoryGroups()).find((x) => x.id === id);
    if (!g) throw ApiError.notFound(`Category group ${id} not found`);
    return toCategoryGroup(g as Record<string, unknown>, this.tree?.parents(budgetId, await this.allCategories()) ?? new Map());
  }
}

/** The level a new sub-category of [parentId] would be at. */
function levelBelow(tree: CategoryTree, budgetId: string, cats: TreeCategory[], parentId: string) {
  const parents = tree.parents(budgetId, cats);
  let level = 3; // parent is at least level 2, so its child is at least 3
  let at = parents.get(parentId);
  while (at !== undefined) {
    level++;
    at = parents.get(at);
  }
  return level;
}
