import type { FastifyPluginAsync } from 'fastify';
import type { NewTransaction, TransactionPatch } from '../../actual/transaction-ops.js';
import { ApiError } from '../../errors.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { DATE, MONEY, type BudgetParams } from './budgets.js';

type TxParams = BudgetParams & { transactionId: string };

const UUID = { type: 'string', pattern: '^[0-9a-fA-F-]{36}$' };
const SPLITS = {
  type: 'array',
  maxItems: 100,
  items: {
    type: 'object',
    required: ['amount'],
    additionalProperties: false,
    properties: {
      id: { type: 'string' },
      amount: MONEY,
      categoryId: { type: ['string', 'null'] },
      notes: { type: ['string', 'null'], maxLength: 2000 },
    },
  },
};

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

export const transactionRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { transactions, review } = deps;

    app.get<{
      Params: BudgetParams;
      Querystring: {
        accountId?: string;
        categoryId?: string;
        payeeId?: string;
        groupId?: string;
        since?: string;
        until?: string;
        q?: string;
        uncategorized?: boolean;
        limit?: number;
        cursor?: string;
      };
    }>(
      '/v1/budgets/:budgetId/transactions',
      {
        schema: {
          querystring: {
            type: 'object',
            properties: {
              accountId: { type: 'string' },
              categoryId: { type: 'string' },
              payeeId: { type: 'string' },
              groupId: { type: 'string' },
              since: DATE,
              until: DATE,
              q: { type: 'string', maxLength: 100 },
              uncategorized: { type: 'boolean' },
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
        const page = await transactions.list(req.params.budgetId, { ...filters, limit, offset });
        return { items: page.items, nextCursor: page.hasMore ? encodeCursor(offset + limit) : null, runningBalances: page.runningBalances };
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
              id: UUID,
              accountId: { type: 'string' },
              date: DATE,
              amount: MONEY,
              payeeId: { type: 'string' },
              payeeName: { type: 'string', maxLength: 200 },
              categoryId: { type: 'string' },
              notes: { type: 'string', maxLength: 2000 },
              cleared: { type: 'boolean' },
              subtransactions: SPLITS,
            },
          },
        },
      },
      async (req, reply) => {
        const auth = requireBudget(deps, req, req.params.budgetId, 'member');
        const { created, transaction } = await transactions.create(req.params.budgetId, req.body);
        // Your own entry doesn't need reviewing by you.
        review.markReviewed(req.params.budgetId, auth.member.id, [transaction.id]);
        if (created) audit(deps, req, req.params.budgetId, 'transaction.created', transaction.id, { amount: transaction.amount });
        return reply.status(created ? 201 : 200).send(transaction);
      },
    );

    app.get<{ Params: TxParams }>('/v1/budgets/:budgetId/transactions/:transactionId', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return transactions.get(req.params.budgetId, req.params.transactionId);
    });

    app.patch<{ Params: TxParams; Body: TransactionPatch }>(
      '/v1/budgets/:budgetId/transactions/:transactionId',
      {
        schema: {
          body: {
            type: 'object',
            minProperties: 1,
            additionalProperties: false,
            properties: {
              accountId: { type: 'string' },
              date: DATE,
              amount: MONEY,
              payeeId: { type: ['string', 'null'] },
              payeeName: { type: 'string', maxLength: 200 },
              categoryId: { type: ['string', 'null'] },
              notes: { type: ['string', 'null'], maxLength: 2000 },
              cleared: { type: 'boolean' },
              subtransactions: SPLITS,
            },
          },
        },
      },
      async (req) => {
        const { budgetId, transactionId } = req.params;
        const auth = requireBudget(deps, req, budgetId, 'member');
        const updated = await transactions.update(budgetId, transactionId, req.body);
        review.markReviewed(budgetId, auth.member.id, [transactionId]);
        audit(deps, req, budgetId, 'transaction.updated', transactionId, Object.keys(req.body));
        return updated;
      },
    );

    app.delete<{ Params: TxParams }>('/v1/budgets/:budgetId/transactions/:transactionId', async (req, reply) => {
      const { budgetId, transactionId } = req.params;
      requireBudget(deps, req, budgetId, 'member');
      await transactions.delete(budgetId, transactionId);
      audit(deps, req, budgetId, 'transaction.deleted', transactionId);
      return reply.status(204).send();
    });

    // Bulk edit or delete from multi-select.
    app.post<{ Params: BudgetParams; Body: { ids: string[]; set?: Record<string, unknown>; delete?: boolean } }>(
      '/v1/budgets/:budgetId/transactions/batch',
      {
        schema: {
          body: {
            type: 'object',
            required: ['ids'],
            additionalProperties: false,
            properties: {
              ids: { type: 'array', minItems: 1, maxItems: 500, items: { type: 'string' } },
              set: {
                type: 'object',
                minProperties: 1,
                additionalProperties: false,
                properties: {
                  categoryId: { type: ['string', 'null'] },
                  accountId: { type: 'string' },
                  cleared: { type: 'boolean' },
                  notes: { type: ['string', 'null'], maxLength: 2000 },
                  date: DATE,
                  payeeId: { type: ['string', 'null'] },
                },
              },
              delete: { type: 'boolean' },
            },
          },
        },
      },
      async (req) => {
        const { budgetId } = req.params;
        const auth = requireBudget(deps, req, budgetId, 'member');
        const { ids, set, delete: del } = req.body;
        if (!!set === !!del) throw ApiError.validation('Send either set or delete');
        const result = await transactions.batch(budgetId, ids, { set, delete: del });
        if (set) review.markReviewed(budgetId, auth.member.id, ids.filter((id) => !result.skipped.some((s) => s.id === id)));
        audit(deps, req, budgetId, del ? 'transaction.batch_deleted' : 'transaction.batch_updated', undefined, { count: ids.length, fields: set ? Object.keys(set) : undefined });
        return result;
      },
    );

    // ── Review inbox (per person) ──
    app.get<{ Params: BudgetParams; Querystring: { limit?: number } }>(
      '/v1/budgets/:budgetId/review',
      { schema: { querystring: { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 200, default: 50 } } } } },
      async (req) => {
        const auth = requireBudget(deps, req, req.params.budgetId);
        return review.inbox(req.params.budgetId, auth.member.id, req.query.limit ?? 50);
      },
    );

    app.post<{ Params: BudgetParams; Body: { ids?: string[]; all?: boolean } }>(
      '/v1/budgets/:budgetId/review',
      {
        schema: {
          body: {
            type: 'object',
            additionalProperties: false,
            minProperties: 1,
            properties: { ids: { type: 'array', minItems: 1, maxItems: 500, items: { type: 'string' } }, all: { type: 'boolean', const: true } },
          },
        },
      },
      async (req) => {
        const { budgetId } = req.params;
        const auth = requireBudget(deps, req, budgetId);
        if (req.body.all) await review.reviewAll(budgetId, auth.member.id);
        else review.markReviewed(budgetId, auth.member.id, req.body.ids ?? []);
        return { remaining: await review.count(budgetId, auth.member.id) };
      },
    );
  };
