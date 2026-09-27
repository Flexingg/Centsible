import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { NewTransaction } from '../../actual/budget-ops.js';
import { ApiError } from '../../errors.js';
import { requireBudget, requireRole, type Deps } from '../server.js';

const MONTH = { type: 'string', pattern: '^\\d{4}-\\d{2}$' };
const DATE = { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' };
const MONEY = { type: 'integer' };

type BudgetParams = { budgetId: string };
type MonthParams = BudgetParams & { month: string };

const encodeCursor = (offset: number) => Buffer.from(JSON.stringify({ o: offset })).toString('base64url');
function decodeCursor(cursor: string | undefined): number {
  if (!cursor) return 0;
  try {
    const o = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8')).o;
    if (Number.isInteger(o) && o >= 0) return o;
  } catch {
    /* fall through */
  }
  throw ApiError.validation('Invalid cursor');
}

export const budgetRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { host, ops, store } = deps;

    /** Replays the stored response for a repeated Idempotency-Key. */
    async function idempotent<T>(req: FastifyRequest, route: string, fn: () => Promise<T>): Promise<T> {
      const key = req.headers['idempotency-key'];
      if (typeof key !== 'string' || key.length < 8 || key.length > 128) {
        throw ApiError.validation('Idempotency-Key header (8-128 chars) is required');
      }
      const memberId = req.auth!.member.id;
      const prior = store.getIdempotent(memberId, key, route);
      if (prior?.status === 409) throw new ApiError(409, 'conflict', 'Idempotency-Key reused for a different request');
      if (prior) return prior.body as T;
      const result = await fn();
      store.putIdempotent(memberId, key, route, 200, result);
      return result;
    }

    function audit(req: FastifyRequest, budgetId: string, action: string, target?: string, detail?: unknown) {
      const { member, device } = req.auth!;
      store.audit({ memberId: member.id, deviceId: device.id, budgetId, action, target, detail });
    }

    app.get('/v1/budgets', async (req) => {
      const { member } = requireRole(req, 'viewer');
      const budgets = await host.listBudgets();
      return { items: budgets.filter((b) => store.canAccessBudget(member, b.id)) };
    });

    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/accounts', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await ops.accounts(req.params.budgetId) };
    });

    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/category-groups', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await ops.categoryGroups(req.params.budgetId) };
    });

    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/payees', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await ops.payees(req.params.budgetId) };
    });

    app.get<{
      Params: BudgetParams;
      Querystring: { accountId?: string; categoryId?: string; since?: string; until?: string; limit?: number; cursor?: string };
    }>(
      '/v1/budgets/:budgetId/transactions',
      {
        schema: {
          querystring: {
            type: 'object',
            properties: {
              accountId: { type: 'string' },
              categoryId: { type: 'string' },
              since: DATE,
              until: DATE,
              limit: { type: 'integer', minimum: 1, maximum: 500, default: 100 },
              cursor: { type: 'string' },
            },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        const { cursor, limit = 100, ...filters } = req.query;
        const offset = decodeCursor(cursor);
        const page = await ops.transactions(req.params.budgetId, { ...filters, limit, offset });
        return { items: page.items, nextCursor: page.hasMore ? encodeCursor(offset + limit) : null };
      },
    );

    app.post<{ Params: BudgetParams; Body: NewTransaction }>(
      '/v1/budgets/:budgetId/transactions',
      {
        schema: {
          body: {
            type: 'object',
            required: ['id', 'accountId', 'date', 'amount'],
            properties: {
              id: { type: 'string', pattern: '^[0-9a-fA-F-]{36}$' },
              accountId: { type: 'string' },
              date: DATE,
              amount: MONEY,
              payeeId: { type: 'string' },
              payeeName: { type: 'string', maxLength: 200 },
              categoryId: { type: 'string' },
              notes: { type: 'string', maxLength: 2000 },
              cleared: { type: 'boolean' },
              subtransactions: {
                type: 'array',
                items: {
                  type: 'object',
                  required: ['amount'],
                  properties: { amount: MONEY, categoryId: { type: 'string' }, notes: { type: 'string' } },
                },
              },
            },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const { created, transaction } = await ops.createTransaction(req.params.budgetId, req.body);
        if (created) audit(req, req.params.budgetId, 'transaction.created', transaction.id, { amount: transaction.amount });
        return reply.status(created ? 201 : 200).send(transaction);
      },
    );

    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/months', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { months: await ops.months(req.params.budgetId) };
    });

    app.get<{ Params: MonthParams }>(
      '/v1/budgets/:budgetId/months/:month',
      { schema: { params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return ops.month(req.params.budgetId, req.params.month);
      },
    );

    app.patch<{ Params: MonthParams & { categoryId: string }; Body: { budgeted?: number; carryover?: boolean } }>(
      '/v1/budgets/:budgetId/months/:month/categories/:categoryId',
      {
        schema: {
          params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH, categoryId: { type: 'string' } } },
          body: {
            type: 'object',
            minProperties: 1,
            additionalProperties: false,
            properties: { budgeted: MONEY, carryover: { type: 'boolean' } },
          },
        },
      },
      async (req) => {
        const { budgetId, month, categoryId } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const result = await ops.updateCategoryBudget(budgetId, month, categoryId, req.body);
        audit(req, budgetId, 'budget.category_updated', categoryId, { month, ...req.body });
        return result;
      },
    );

    app.post<{ Params: MonthParams; Body: { from: string; to: string; amount: number } }>(
      '/v1/budgets/:budgetId/months/:month/transfers',
      {
        schema: {
          params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH } },
          body: {
            type: 'object',
            required: ['from', 'to', 'amount'],
            properties: { from: { type: 'string' }, to: { type: 'string' }, amount: { type: 'integer', minimum: 1 } },
          },
        },
      },
      async (req) => {
        const { budgetId, month } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        return idempotent(req, `transfer:${budgetId}:${month}`, async () => {
          const result = await ops.moveMoney(budgetId, month, req.body);
          audit(req, budgetId, 'budget.money_moved', undefined, { month, ...req.body });
          return result;
        });
      },
    );

    app.put<{ Params: MonthParams; Body: { amount: number } }>(
      '/v1/budgets/:budgetId/months/:month/hold',
      {
        schema: {
          params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH } },
          body: { type: 'object', required: ['amount'], properties: { amount: { type: 'integer', minimum: 0 } } },
        },
      },
      async (req) => {
        const { budgetId, month } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const result = await ops.hold(budgetId, month, req.body.amount);
        audit(req, budgetId, 'budget.hold_set', undefined, { month, amount: req.body.amount });
        return result;
      },
    );

    app.delete<{ Params: MonthParams }>(
      '/v1/budgets/:budgetId/months/:month/hold',
      { schema: { params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH } } } },
      async (req) => {
        const { budgetId, month } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const result = await ops.hold(budgetId, month, null);
        audit(req, budgetId, 'budget.hold_reset', undefined, { month });
        return result;
      },
    );
  };
