import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import { toAccount, toCategory, toCategoryGroup } from '../mappers/index.js';
import type { ActualHost } from './host.js';

/** Accounts, categories and category groups (all tier 1). */
export class StructureOps {
  constructor(private readonly host: ActualHost) {}

  createAccount(budgetId: string, input: { name: string; offBudget: boolean; initialBalance: number }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const id = await api.createAccount({ name: input.name.trim(), offbudget: input.offBudget }, input.initialBalance);
      return this.account(id);
    });
  }

  updateAccount(budgetId: string, id: string, patch: { name?: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.account(id);
      if (patch.name !== undefined) await api.updateAccount(id, { name: patch.name.trim() });
      return this.account(id);
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
      return after ? toAccount(after, await api.getAccountBalance(id)) : null;
    });
  }

  reopenAccount(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.account(id);
      await api.reopenAccount(id);
      return this.account(id);
    });
  }

  createCategory(budgetId: string, input: { name: string; groupId: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const group = (await api.getCategoryGroups()).find((g) => g.id === input.groupId);
      if (!group) throw ApiError.validation(`Unknown category group ${input.groupId}`);
      const id = await api.createCategory({ name: input.name.trim(), group_id: input.groupId, is_income: !!group.is_income, hidden: false });
      return this.category(id);
    });
  }

  updateCategory(budgetId: string, id: string, patch: { name?: string; hidden?: boolean; groupId?: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.category(id);
      const fields: Record<string, unknown> = {};
      if (patch.name !== undefined) fields.name = patch.name.trim();
      if (patch.hidden !== undefined) fields.hidden = patch.hidden;
      if (patch.groupId !== undefined) fields.group_id = patch.groupId;
      if (Object.keys(fields).length) await api.updateCategory(id, fields);
      return this.category(id);
    });
  }

  deleteCategory(budgetId: string, id: string, transferCategoryId?: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.category(id);
      if (transferCategoryId === id) throw ApiError.validation('Pick a different category to move transactions to');
      await api.deleteCategory(id, transferCategoryId);
    });
  }

  createGroup(budgetId: string, input: { name: string }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const id = await api.createCategoryGroup({ name: input.name.trim(), is_income: false, hidden: false });
      return this.group(id);
    });
  }

  updateGroup(budgetId: string, id: string, patch: { name?: string; hidden?: boolean }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.group(id);
      const fields: Record<string, unknown> = {};
      if (patch.name !== undefined) fields.name = patch.name.trim();
      if (patch.hidden !== undefined) fields.hidden = patch.hidden;
      if (Object.keys(fields).length) await api.updateCategoryGroup(id, fields);
      return this.group(id);
    });
  }

  deleteGroup(budgetId: string, id: string, transferCategoryId?: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const group = await this.group(id);
      if (group.isIncome) throw ApiError.validation("The income group can't be deleted");
      await api.deleteCategoryGroup(id, transferCategoryId);
    });
  }

  private async account(id: string) {
    const a = (await api.getAccounts()).find((x) => x.id === id);
    if (!a) throw ApiError.notFound(`Account ${id} not found`);
    return toAccount(a, await api.getAccountBalance(id));
  }

  private async category(id: string) {
    const c = (await api.getCategories()).find((x) => x.id === id);
    if (!c) throw ApiError.notFound(`Category ${id} not found`);
    return toCategory(c);
  }

  private async group(id: string) {
    const g = (await api.getCategoryGroups()).find((x) => x.id === id);
    if (!g) throw ApiError.notFound(`Category group ${id} not found`);
    return toCategoryGroup(g);
  }
}
