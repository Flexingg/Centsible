import type { FastifyPluginAsync } from 'fastify';
import { audit, requireBudget, type Deps } from '../server.js';
import { ACCOUNT_KINDS, type AccountKind } from '../../actual/account-kinds.js';
import { MONEY, type BudgetParams } from './budgets.js';

type IdParams = BudgetParams & { id: string };
const NAME = { type: 'string', minLength: 1, maxLength: 100 };
/** An account type, or null to clear it. */
const KIND = { type: ['string', 'null'], enum: [...ACCOUNT_KINDS, null] };
const TRANSFER_QUERY = { type: 'object', properties: { transferCategoryId: { type: 'string' } } };

/** Accounts, categories and category groups. Members and owners may edit. */
export const structureRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { structure } = deps;

    app.post<{ Params: BudgetParams; Body: { name: string; offBudget?: boolean; initialBalance?: number; kind?: AccountKind | null } }>(
      '/v1/budgets/:budgetId/accounts',
      {
        schema: {
          body: {
            type: 'object',
            required: ['name'],
            additionalProperties: false,
            properties: { name: NAME, offBudget: { type: 'boolean' }, initialBalance: MONEY, kind: KIND },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const account = await structure.createAccount(req.params.budgetId, {
          name: req.body.name,
          offBudget: req.body.offBudget ?? false,
          initialBalance: req.body.initialBalance ?? 0,
          kind: req.body.kind ?? null,
        });
        audit(deps, req, req.params.budgetId, 'account.created', account.id);
        return reply.status(201).send(account);
      },
    );

    app.patch<{ Params: IdParams; Body: { name?: string; kind?: AccountKind | null } }>(
      '/v1/budgets/:budgetId/accounts/:id',
      { schema: { body: { type: 'object', minProperties: 1, additionalProperties: false, properties: { name: NAME, kind: KIND } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const account = await structure.updateAccount(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'account.updated', req.params.id, req.body.kind !== undefined ? { kind: req.body.kind } : undefined);
        return account;
      },
    );

    app.post<{ Params: IdParams; Body: { transferAccountId?: string; transferCategoryId?: string } }>(
      '/v1/budgets/:budgetId/accounts/:id/close',
      {
        schema: {
          body: {
            type: 'object',
            additionalProperties: false,
            properties: { transferAccountId: { type: 'string' }, transferCategoryId: { type: 'string' } },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const account = await structure.closeAccount(req.params.budgetId, req.params.id, req.body ?? {});
        audit(deps, req, req.params.budgetId, 'account.closed', req.params.id);
        // null when Actual deleted an account that had no transactions
        return account ? account : reply.status(204).send();
      },
    );

    app.post<{ Params: IdParams }>('/v1/budgets/:budgetId/accounts/:id/reopen', async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const account = await structure.reopenAccount(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'account.reopened', req.params.id);
      return account;
    });

    app.post<{ Params: BudgetParams; Body: { name: string; groupId?: string; parentId?: string | null } }>(
      '/v1/budgets/:budgetId/categories',
      {
        schema: {
          body: {
            type: 'object',
            required: ['name'],
            additionalProperties: false,
            properties: { name: NAME, groupId: { type: 'string' }, parentId: { type: ['string', 'null'] } },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const category = await structure.createCategory(req.params.budgetId, req.body);
        audit(deps, req, req.params.budgetId, 'category.created', category.id);
        return reply.status(201).send(category);
      },
    );

    app.patch<{ Params: IdParams; Body: { name?: string; hidden?: boolean; groupId?: string; parentId?: string | null } }>(
      '/v1/budgets/:budgetId/categories/:id',
      {
        schema: {
          body: {
            type: 'object',
            minProperties: 1,
            additionalProperties: false,
            properties: { name: NAME, hidden: { type: 'boolean' }, groupId: { type: 'string' }, parentId: { type: ['string', 'null'] } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const category = await structure.updateCategory(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'category.updated', req.params.id, req.body.parentId !== undefined ? { parentId: req.body.parentId } : undefined);
        return category;
      },
    );

    app.delete<{ Params: IdParams; Querystring: { transferCategoryId?: string } }>(
      '/v1/budgets/:budgetId/categories/:id',
      { schema: { querystring: TRANSFER_QUERY } },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        await structure.deleteCategory(req.params.budgetId, req.params.id, req.query.transferCategoryId);
        audit(deps, req, req.params.budgetId, 'category.deleted', req.params.id);
        return reply.status(204).send();
      },
    );

    app.post<{ Params: BudgetParams; Body: { name: string } }>(
      '/v1/budgets/:budgetId/category-groups',
      { schema: { body: { type: 'object', required: ['name'], additionalProperties: false, properties: { name: NAME } } } },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const group = await structure.createGroup(req.params.budgetId, req.body);
        audit(deps, req, req.params.budgetId, 'category_group.created', group.id);
        return reply.status(201).send(group);
      },
    );

    app.patch<{ Params: IdParams; Body: { name?: string; hidden?: boolean } }>(
      '/v1/budgets/:budgetId/category-groups/:id',
      {
        schema: {
          body: { type: 'object', minProperties: 1, additionalProperties: false, properties: { name: NAME, hidden: { type: 'boolean' } } },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const group = await structure.updateGroup(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'category_group.updated', req.params.id);
        return group;
      },
    );

    app.delete<{ Params: IdParams; Querystring: { transferCategoryId?: string } }>(
      '/v1/budgets/:budgetId/category-groups/:id',
      { schema: { querystring: TRANSFER_QUERY } },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        await structure.deleteGroup(req.params.budgetId, req.params.id, req.query.transferCategoryId);
        audit(deps, req, req.params.budgetId, 'category_group.deleted', req.params.id);
        return reply.status(204).send();
      },
    );
  };
