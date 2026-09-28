import type { FastifyPluginAsync } from 'fastify';
import { AVERAGE_BASES, type Basis, type GoalInput } from '../../actual/plan-ops.js';
import { ApiError } from '../../errors.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { MONEY, MONTH } from './budgets.js';

type MonthParams = { budgetId: string; month: string };
type CategoryParams = { budgetId: string; id: string };
const monthParams = { type: 'object', properties: { month: MONTH } };

/** Budget autopilot, savings goals, and the cash-flow forecast behind the bill calendar. */
export const planRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { plan } = deps;

    app.get<{ Params: MonthParams }>('/v1/budgets/:budgetId/months/:month/autopilot', { schema: { params: monthParams } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return plan.autopilot(req.params.budgetId, req.params.month);
    });

    app.post<{ Params: MonthParams; Body: { basis: Basis; categoryIds?: string[] } }>(
      '/v1/budgets/:budgetId/months/:month/autopilot',
      {
        schema: {
          params: monthParams,
          body: {
            type: 'object',
            required: ['basis'],
            additionalProperties: false,
            properties: { basis: { type: 'integer', enum: [...AVERAGE_BASES] }, categoryIds: { type: 'array', items: { type: 'string', minLength: 1 }, maxItems: 500 } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await plan.applyAutopilot(req.params.budgetId, req.params.month, req.body.basis, req.body.categoryIds);
        audit(deps, req, req.params.budgetId, 'budget.autopilot', req.params.month, { basis: req.body.basis, changed: res.changed });
        return res;
      },
    );

    app.post<{ Params: MonthParams }>('/v1/budgets/:budgetId/months/:month/cover-overspending', { schema: { params: monthParams } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const res = await plan.coverOverspending(req.params.budgetId, req.params.month);
      audit(deps, req, req.params.budgetId, 'budget.cover_overspending', req.params.month, { moves: res.moves.length });
      return res;
    });

    app.get<{ Params: { budgetId: string }; Querystring: { month?: string } }>(
      '/v1/budgets/:budgetId/goals',
      { schema: { querystring: { type: 'object', properties: { month: MONTH } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return plan.goals(req.params.budgetId, req.query.month);
      },
    );

    app.put<{ Params: CategoryParams; Body: GoalInput }>(
      '/v1/budgets/:budgetId/categories/:id/goal',
      {
        schema: {
          body: {
            type: 'object',
            required: ['kind', 'target'],
            additionalProperties: false,
            properties: { kind: { type: 'string', enum: ['balance', 'by'] }, target: { ...MONEY, minimum: 100 }, targetMonth: { ...MONTH, type: ['string', 'null'] } },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        await plan.setGoal(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'goal.set', req.params.id, req.body);
        return reply.status(204).send();
      },
    );

    app.delete<{ Params: CategoryParams }>('/v1/budgets/:budgetId/categories/:id/goal', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      await plan.setGoal(req.params.budgetId, req.params.id, null);
      audit(deps, req, req.params.budgetId, 'goal.removed', req.params.id);
      return reply.status(204).send();
    });

    app.get<{ Params: { budgetId: string }; Querystring: { days?: number; accountIds?: string; includeTypical?: boolean } }>(
      '/v1/budgets/:budgetId/forecast',
      {
        schema: {
          querystring: {
            type: 'object',
            properties: {
              days: { type: 'integer', minimum: 7, maximum: 366, default: 90 },
              accountIds: { type: 'string', maxLength: 4000 },
              includeTypical: { type: 'boolean', default: true },
            },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        const accountIds = req.query.accountIds?.split(',').map((s) => s.trim()).filter(Boolean);
        if (accountIds && !accountIds.length) throw ApiError.validation('accountIds is empty');
        return plan.forecast(req.params.budgetId, { days: req.query.days ?? 90, accountIds, includeTypical: req.query.includeTypical !== false });
      },
    );
  };
