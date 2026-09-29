import type { FastifyPluginAsync } from 'fastify';
import type { AutomationDto } from '../../actual/automation-ops.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { MONTH } from './budgets.js';

type BudgetParams = { budgetId: string };
type CategoryParams = { budgetId: string; categoryId: string };
type MonthQuery = { month?: string };

const monthQuery = { type: 'object', properties: { month: MONTH } };
// The shape is checked type by type in AutomationOps (clear messages beat schema errors).
const AUTOMATIONS = { type: 'array', maxItems: 20, items: { type: 'object', required: ['type'], properties: { type: { type: 'string' } } } };

/** Budget automations ("programmable budgets"): see AutomationOps. */
export const automationRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { automations } = deps;

    app.get<{ Params: BudgetParams; Querystring: MonthQuery }>('/v1/budgets/:budgetId/automations', { schema: { querystring: monthQuery } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return automations.list(req.params.budgetId, req.query.month ?? null);
    });

    app.get<{ Params: CategoryParams; Querystring: MonthQuery }>(
      '/v1/budgets/:budgetId/categories/:categoryId/automations',
      { schema: { querystring: monthQuery } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return automations.one(req.params.budgetId, req.params.categoryId, req.query.month ?? null);
      },
    );

    app.put<{ Params: CategoryParams; Querystring: MonthQuery; Body: { automations: AutomationDto[] } }>(
      '/v1/budgets/:budgetId/categories/:categoryId/automations',
      { schema: { querystring: monthQuery, body: { type: 'object', required: ['automations'], additionalProperties: false, properties: { automations: AUTOMATIONS } } } },
      async (req) => {
        const { budgetId, categoryId } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const res = await automations.set(budgetId, categoryId, req.body.automations, req.query.month ?? null);
        audit(deps, req, budgetId, 'budget.automations_saved', categoryId, { types: req.body.automations.map((a) => a.type) });
        return res;
      },
    );

    // Back to #template lines in the category's notes.
    app.delete<{ Params: CategoryParams; Querystring: MonthQuery }>(
      '/v1/budgets/:budgetId/categories/:categoryId/automations',
      { schema: { querystring: monthQuery } },
      async (req) => {
        const { budgetId, categoryId } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const res = await automations.useNotes(budgetId, categoryId, req.query.month ?? null);
        audit(deps, req, budgetId, 'budget.automations_to_notes', categoryId);
        return res;
      },
    );

    app.post<{ Params: CategoryParams; Body: { month: string; automations: AutomationDto[] } }>(
      '/v1/budgets/:budgetId/categories/:categoryId/automations/preview',
      { schema: { body: { type: 'object', required: ['month', 'automations'], additionalProperties: false, properties: { month: MONTH, automations: AUTOMATIONS } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return automations.preview(req.params.budgetId, req.params.categoryId, req.body.month, req.body.automations);
      },
    );

    app.post<{ Params: BudgetParams; Body: { month: string; overwrite?: boolean; categoryIds?: string[] } }>(
      '/v1/budgets/:budgetId/automations/apply',
      {
        schema: {
          body: {
            type: 'object',
            required: ['month'],
            additionalProperties: false,
            properties: { month: MONTH, overwrite: { type: 'boolean' }, categoryIds: { type: 'array', minItems: 1, maxItems: 500, items: { type: 'string' } } },
          },
        },
      },
      async (req) => {
        const { budgetId } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        const res = await automations.apply(budgetId, req.body.month, { overwrite: req.body.overwrite ?? false, categoryIds: req.body.categoryIds });
        audit(deps, req, budgetId, 'budget.automations_applied', req.body.month, { overwrite: req.body.overwrite ?? false, categories: req.body.categoryIds?.length ?? 'all' });
        return res;
      },
    );
  };
