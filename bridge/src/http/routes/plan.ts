import * as api from '@actual-app/api';
import type { FastifyPluginAsync } from 'fastify';
import { AVERAGE_BASES, type Basis, type GoalInput } from '../../actual/plan-ops.js';
import type { MortgageInput } from '../../actual/mortgage-ops.js';
import type { TargetInput } from '../../actual/target-ops.js';
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
    const { plan, targets, mortgages, annual, offBooks } = deps;

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

    // ── Goals on accounts and on monthly spending (kept by the bridge) ──
    const TARGET_BODY = {
      type: 'object',
      required: ['kind'],
      additionalProperties: false,
      properties: {
        kind: { type: 'string', enum: ['account', 'spend-under', 'spend-at-least'] },
        name: { type: ['string', 'null'], maxLength: 100 },
        accountId: { type: ['string', 'null'] },
        categoryId: { type: ['string', 'null'] },
        groupId: { type: ['string', 'null'] },
        amount: { type: ['integer', 'null'], minimum: 0 },
        percentOfIncome: { type: ['number', 'null'], minimum: 0, maximum: 100 },
        targetMonth: { ...MONTH, type: ['string', 'null'] },
      },
    };

    app.get<{ Params: { budgetId: string }; Querystring: { month?: string } }>(
      '/v1/budgets/:budgetId/targets',
      { schema: { querystring: { type: 'object', properties: { month: MONTH } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return targets.list(req.params.budgetId, req.query.month);
      },
    );

    app.post<{ Params: { budgetId: string }; Body: TargetInput }>('/v1/budgets/:budgetId/targets', { schema: { body: TARGET_BODY } }, async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const t = await targets.create(req.params.budgetId, req.body);
      audit(deps, req, req.params.budgetId, 'target.created', t.id, req.body);
      return reply.status(201).send(t);
    });

    app.put<{ Params: CategoryParams; Body: TargetInput }>('/v1/budgets/:budgetId/targets/:id', { schema: { body: TARGET_BODY } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const t = await targets.update(req.params.budgetId, req.params.id, req.body);
      audit(deps, req, req.params.budgetId, 'target.updated', t.id, req.body);
      return t;
    });

    app.delete<{ Params: CategoryParams }>('/v1/budgets/:budgetId/targets/:id', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      targets.remove(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'target.removed', req.params.id);
      return reply.status(204).send();
    });

    // ── A category's months, for deciding what to budget ──
    app.get<{ Params: CategoryParams; Querystring: { month?: string; months?: number } }>(
      '/v1/budgets/:budgetId/categories/:id/history',
      { schema: { querystring: { type: 'object', properties: { month: MONTH, months: { type: 'integer', minimum: 3, maximum: 24, default: 12 } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return plan.categoryHistory(req.params.budgetId, req.params.id, req.query.month, req.query.months ?? 12);
      },
    );

    // ── Off the books: set aside, left out of reviews, reports and insights ──
    app.get<{ Params: { budgetId: string } }>('/v1/budgets/:budgetId/off-books', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { categoryIds: offBooks.ids(req.params.budgetId) };
    });

    app.put<{ Params: CategoryParams; Body: { offBooks: boolean } }>(
      '/v1/budgets/:budgetId/categories/:id/off-books',
      { schema: { body: { type: 'object', required: ['offBooks'], additionalProperties: false, properties: { offBooks: { type: 'boolean' } } } } },
      async (req) => {
        const { budgetId, id } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        await deps.host.withBudget(budgetId, 'write', async () => {
          const cat = (await api.getCategories()).find((c) => c.id === id);
          if (!cat) throw ApiError.notFound(`Category ${id} not found`);
          if (cat.is_income) throw ApiError.validation('An income category can’t be off the books.');
          // Its balance waits in the category instead of coming out of next month's To Budget.
          if (req.body.offBooks) await api.setBudgetCarryover(new Date().toISOString().slice(0, 7), id, true);
          await new Promise((r) => setTimeout(r, 0));
        });
        const ids = offBooks.set(budgetId, id, req.body.offBooks);
        audit(deps, req, budgetId, 'category.off_books', id, { offBooks: req.body.offBooks });
        return { categoryIds: ids };
      },
    );

    // ── Yearly budgets: a twelfth a month, bills drawn from the rest of the year ──
    app.get<{ Params: { budgetId: string }; Querystring: { month?: string } }>(
      '/v1/budgets/:budgetId/annual-budgets',
      { schema: { querystring: { type: 'object', properties: { month: MONTH } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return annual.list(req.params.budgetId, req.query.month);
      },
    );

    app.put<{ Params: CategoryParams; Body: { amount: number; startMonth?: number } }>(
      '/v1/budgets/:budgetId/categories/:id/annual-budget',
      {
        schema: {
          body: {
            type: 'object',
            required: ['amount'],
            additionalProperties: false,
            properties: { amount: { type: 'integer', minimum: 100 }, startMonth: { type: 'integer', minimum: 1, maximum: 12, default: 1 } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await annual.set(req.params.budgetId, req.params.id, req.body.amount, req.body.startMonth ?? 1);
        audit(deps, req, req.params.budgetId, 'annual_budget.set', req.params.id, req.body);
        return res;
      },
    );

    app.delete<{ Params: CategoryParams }>('/v1/budgets/:budgetId/categories/:id/annual-budget', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      annual.remove(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'annual_budget.removed', req.params.id);
      return reply.status(204).send();
    });

    app.post<{ Params: MonthParams }>('/v1/budgets/:budgetId/months/:month/annual-budgets/apply', { schema: { params: monthParams } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const res = await annual.apply(req.params.budgetId, req.params.month);
      audit(deps, req, req.params.budgetId, 'annual_budget.applied', req.params.month, { changes: res.changes.length });
      return res;
    });

    // ── Mortgages: terms kept by the bridge, balances in Actual's accounts ──
    const MORTGAGE_BODY = {
      type: 'object',
      required: ['name', 'principal', 'rate', 'termMonths', 'firstPayment'],
      additionalProperties: false,
      properties: {
        name: { type: 'string', minLength: 1, maxLength: 100 },
        principal: { type: 'integer', minimum: 1 },
        rate: { type: 'number', minimum: 0, maximum: 50 },
        termMonths: { type: 'integer', minimum: 1, maximum: 600 },
        firstPayment: { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
        escrow: { type: 'integer', minimum: 0 },
        extra: { type: 'integer', minimum: 0 },
        payeeId: { type: ['string', 'null'] },
        paymentAccountId: { type: ['string', 'null'] },
        loanAccountId: { type: ['string', 'null'] },
        homeAccountId: { type: ['string', 'null'] },
        createLoanAccount: { type: 'boolean' },
        currentBalance: { type: ['integer', 'null'], minimum: 0 },
        homeValue: { type: ['integer', 'null'], minimum: 0 },
      },
    };

    app.get<{ Params: { budgetId: string } }>('/v1/budgets/:budgetId/mortgages', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return mortgages.list(req.params.budgetId);
    });

    app.post<{ Params: { budgetId: string }; Body: MortgageInput }>('/v1/budgets/:budgetId/mortgages', { schema: { body: MORTGAGE_BODY } }, async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const m = await mortgages.create(req.params.budgetId, req.body);
      audit(deps, req, req.params.budgetId, 'mortgage.created', m.id);
      return reply.status(201).send(m);
    });

    app.get<{ Params: CategoryParams }>('/v1/budgets/:budgetId/mortgages/:id', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return mortgages.get(req.params.budgetId, req.params.id);
    });

    app.put<{ Params: CategoryParams; Body: MortgageInput }>('/v1/budgets/:budgetId/mortgages/:id', { schema: { body: MORTGAGE_BODY } }, async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const m = await mortgages.update(req.params.budgetId, req.params.id, req.body);
      audit(deps, req, req.params.budgetId, 'mortgage.updated', m.id);
      return m;
    });

    app.delete<{ Params: CategoryParams }>('/v1/budgets/:budgetId/mortgages/:id', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      mortgages.remove(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'mortgage.removed', req.params.id);
      return reply.status(204).send();
    });

    app.put<{ Params: CategoryParams; Body: { accountId: string | null; retireOld?: boolean } }>(
      '/v1/budgets/:budgetId/mortgages/:id/loan-account',
      {
        schema: {
          body: {
            type: 'object',
            required: ['accountId'],
            additionalProperties: false,
            properties: { accountId: { type: ['string', 'null'], minLength: 1 }, retireOld: { type: 'boolean' } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const m = await mortgages.linkLoanAccount(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'mortgage.loan_account', req.params.id, { accountId: req.body.accountId, retired: m.retiredAccount });
        return m;
      },
    );

    app.put<{ Params: CategoryParams; Body: { value: number } }>(
      '/v1/budgets/:budgetId/mortgages/:id/home-value',
      { schema: { body: { type: 'object', required: ['value'], additionalProperties: false, properties: { value: { type: 'integer', minimum: 0 } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const m = await mortgages.setHomeValue(req.params.budgetId, req.params.id, req.body.value);
        audit(deps, req, req.params.budgetId, 'mortgage.home_value', req.params.id, { value: req.body.value });
        return m;
      },
    );

    app.post<{ Params: CategoryParams }>('/v1/budgets/:budgetId/mortgages/:id/record-principal', async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      const r = await mortgages.recordPrincipal(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'mortgage.principal_recorded', req.params.id, r);
      return r;
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
