import type { FastifyPluginAsync } from 'fastify';
import { REVIEW_PERIODS, type ReviewPeriod } from '../../actual/insights-ops.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { DATE, MONTH } from './budgets.js';

type BudgetParams = { budgetId: string };

/** Spending trends and alerts, recurring-payment discovery, and the year in review. */
export const insightsRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { insights } = deps;

    app.get<{ Params: BudgetParams; Querystring: { month?: string } }>(
      '/v1/budgets/:budgetId/insights',
      { schema: { querystring: { type: 'object', properties: { month: MONTH } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return insights.insights(req.params.budgetId, req.query.month);
      },
    );

    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/subscriptions', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return insights.subscriptions(req.params.budgetId);
    });

    app.post<{ Params: BudgetParams; Body: { payeeId: string } }>(
      '/v1/budgets/:budgetId/subscriptions/dismiss',
      { schema: { body: { type: 'object', required: ['payeeId'], additionalProperties: false, properties: { payeeId: { type: 'string', minLength: 1 } } } } },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        insights.dismissSubscription(req.params.budgetId, req.body.payeeId);
        audit(deps, req, req.params.budgetId, 'subscriptions.dismissed', req.body.payeeId);
        return reply.status(204).send();
      },
    );

    app.get<{ Params: BudgetParams; Querystring: { period: ReviewPeriod; date?: string } }>(
      '/v1/budgets/:budgetId/reports/review',
      {
        schema: {
          querystring: {
            type: 'object',
            required: ['period'],
            properties: { period: { type: 'string', enum: [...REVIEW_PERIODS] }, date: DATE },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return insights.review(req.params.budgetId, req.query.period, req.query.date);
      },
    );

    app.get<{ Params: BudgetParams; Querystring: { year?: number } }>(
      '/v1/budgets/:budgetId/reports/year-in-review',
      { schema: { querystring: { type: 'object', properties: { year: { type: 'integer', minimum: 2000, maximum: 2100 } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        const now = new Date();
        // Wrapped season: from November on, this year; before that, last year.
        const year = req.query.year ?? (now.getUTCMonth() >= 10 ? now.getUTCFullYear() : now.getUTCFullYear() - 1);
        return insights.yearInReview(req.params.budgetId, year);
      },
    );
  };
