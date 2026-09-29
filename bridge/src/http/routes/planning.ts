import type { FastifyPluginAsync } from 'fastify';
import type { RuleDraft } from '../../actual/rule-tools.js';
import type { RuleInput, ScheduleInput } from '../../actual/planning-ops.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { DATE, MONEY, type BudgetParams } from './budgets.js';

type IdParams = BudgetParams & { id: string };

const RECURRENCE = {
  type: 'object',
  required: ['frequency', 'start'],
  properties: {
    frequency: { type: 'string', enum: ['daily', 'weekly', 'monthly', 'yearly'] },
    interval: { type: 'integer', minimum: 1, maximum: 366 },
    start: DATE,
    endMode: { type: 'string', enum: ['never', 'after_n_occurrences', 'on_date'] },
    endOccurrences: { type: 'integer', minimum: 1 },
    endDate: DATE,
    skipWeekend: { type: 'boolean' },
    weekendSolveMode: { type: 'string', enum: ['before', 'after'] },
    patterns: { type: 'array' },
  },
};
const SCHEDULE = {
  type: 'object',
  additionalProperties: false,
  properties: {
    name: { type: ['string', 'null'], maxLength: 100 },
    payeeId: { type: ['string', 'null'] },
    payeeName: { type: 'string', maxLength: 200 },
    accountId: { type: ['string', 'null'] },
    amount: MONEY,
    amountMax: MONEY,
    amountOp: { type: 'string', enum: ['is', 'isapprox', 'isbetween'] },
    recurrence: RECURRENCE,
    date: DATE,
    postsTransaction: { type: 'boolean' },
  },
};
const RULE_ITEM = {
  type: 'object',
  required: ['op'],
  properties: { field: { type: ['string', 'null'] }, op: { type: 'string' }, value: {}, type: { type: ['string', 'null'] }, options: { type: ['object', 'null'] } },
};
const RULE = {
  type: 'object',
  required: ['conditions', 'actions'],
  additionalProperties: false,
  properties: {
    stage: { type: ['string', 'null'], enum: ['pre', 'post', null] },
    conditionsOp: { type: 'string', enum: ['and', 'or'] },
    conditions: { type: 'array', maxItems: 50, items: RULE_ITEM },
    actions: { type: 'array', maxItems: 50, items: RULE_ITEM },
  },
};
const TAG = {
  type: 'object',
  additionalProperties: false,
  properties: {
    tag: { type: 'string', minLength: 1, maxLength: 60, pattern: '^#?[^\\s#]+$' },
    color: { type: ['string', 'null'], pattern: '^#[0-9a-fA-F]{6}$' },
    description: { type: ['string', 'null'], maxLength: 200 },
  },
};

/** Schedules, rules, payees (merchants), tags and category notes. */
export const planningRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { planning } = deps;
    const write = (req: Parameters<typeof requireBudget>[1], budgetId: string) => requireBudget(deps, req, budgetId, 'member');

    // Schedules (Monarch "Recurring")
    app.get<{ Params: BudgetParams; Querystring: { upcoming?: number } }>(
      '/v1/budgets/:budgetId/schedules',
      { schema: { querystring: { type: 'object', properties: { upcoming: { type: 'integer', minimum: 1, maximum: 24, default: 3 } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return { items: await planning.schedules(req.params.budgetId, req.query.upcoming ?? 3) };
      },
    );
    app.post<{ Params: BudgetParams; Body: ScheduleInput }>('/v1/budgets/:budgetId/schedules', { schema: { body: SCHEDULE } }, async (req, reply) => {
      write(req, req.params.budgetId);
      const s = await planning.createSchedule(req.params.budgetId, req.body);
      audit(deps, req, req.params.budgetId, 'schedule.created', s.id);
      return reply.status(201).send(s);
    });
    app.patch<{ Params: IdParams; Body: ScheduleInput }>('/v1/budgets/:budgetId/schedules/:id', { schema: { body: { ...SCHEDULE, minProperties: 1 } } }, async (req) => {
      write(req, req.params.budgetId);
      const s = await planning.updateSchedule(req.params.budgetId, req.params.id, req.body);
      audit(deps, req, req.params.budgetId, 'schedule.updated', req.params.id);
      return s;
    });
    app.delete<{ Params: IdParams }>('/v1/budgets/:budgetId/schedules/:id', async (req, reply) => {
      write(req, req.params.budgetId);
      await planning.deleteSchedule(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'schedule.deleted', req.params.id);
      return reply.status(204).send();
    });
    app.post<{ Params: IdParams }>('/v1/budgets/:budgetId/schedules/:id/skip', async (req) => {
      write(req, req.params.budgetId);
      const s = await planning.skipSchedule(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'schedule.skipped', req.params.id);
      return s;
    });
    app.post<{ Params: IdParams }>('/v1/budgets/:budgetId/schedules/:id/post', async (req) => {
      write(req, req.params.budgetId);
      const s = await planning.postSchedule(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'schedule.posted', req.params.id);
      return s;
    });

    // Rules
    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/rules', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await planning.rules(req.params.budgetId) };
    });
    app.post<{ Params: BudgetParams; Body: RuleInput }>('/v1/budgets/:budgetId/rules', { schema: { body: RULE } }, async (req, reply) => {
      write(req, req.params.budgetId);
      const r = await planning.createRule(req.params.budgetId, req.body);
      audit(deps, req, req.params.budgetId, 'rule.created', r.id);
      return reply.status(201).send(r);
    });
    app.put<{ Params: IdParams; Body: RuleInput }>('/v1/budgets/:budgetId/rules/:id', { schema: { body: RULE } }, async (req) => {
      write(req, req.params.budgetId);
      const r = await planning.updateRule(req.params.budgetId, req.params.id, req.body);
      audit(deps, req, req.params.budgetId, 'rule.updated', req.params.id);
      return r;
    });
    // Which transactions a (draft) rule matches and what it would do; nothing is written.
    app.post<{ Params: BudgetParams; Body: RuleDraft & { limit?: number } }>(
      '/v1/budgets/:budgetId/rules/preview',
      {
        schema: {
          body: {
            type: 'object',
            required: ['conditions', 'actions'],
            additionalProperties: false,
            properties: { ...RULE.properties, limit: { type: 'integer', minimum: 1, maximum: 100 } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return deps.ruleTools.preview(req.params.budgetId, req.body, req.body.limit ?? 20);
      },
    );

    // Runs a saved rule on the existing transactions it matches (or some of them).
    app.post<{ Params: IdParams; Body: { transactionIds?: string[] } | undefined }>(
      '/v1/budgets/:budgetId/rules/:id/run',
      { schema: { body: { type: ['object', 'null'], additionalProperties: false, properties: { transactionIds: { type: 'array', minItems: 1, maxItems: 1000, items: { type: 'string' } } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await deps.ruleTools.runRule(req.params.budgetId, req.params.id, req.body?.transactionIds);
        audit(deps, req, req.params.budgetId, 'rule.run', req.params.id, res);
        return res;
      },
    );

    // Every rule again, on chosen transactions (as if just imported).
    app.post<{ Params: BudgetParams; Body: { transactionIds: string[] } }>(
      '/v1/budgets/:budgetId/rules/rerun',
      { schema: { body: { type: 'object', required: ['transactionIds'], additionalProperties: false, properties: { transactionIds: { type: 'array', minItems: 1, maxItems: 500, items: { type: 'string' } } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await deps.ruleTools.rerun(req.params.budgetId, req.body.transactionIds);
        audit(deps, req, req.params.budgetId, 'rules.rerun', undefined, res);
        return res;
      },
    );

    app.delete<{ Params: IdParams }>('/v1/budgets/:budgetId/rules/:id', async (req, reply) => {
      write(req, req.params.budgetId);
      await planning.deleteRule(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'rule.deleted', req.params.id);
      return reply.status(204).send();
    });

    // Payees (Monarch "Merchants")
    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/payees/stats', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await planning.payeeStats(req.params.budgetId) };
    });
    app.patch<{ Params: IdParams; Body: { name: string } }>(
      '/v1/budgets/:budgetId/payees/:id',
      { schema: { body: { type: 'object', required: ['name'], additionalProperties: false, properties: { name: { type: 'string', minLength: 1, maxLength: 200 } } } } },
      async (req) => {
        write(req, req.params.budgetId);
        const p = await planning.renamePayee(req.params.budgetId, req.params.id, req.body.name);
        audit(deps, req, req.params.budgetId, 'payee.renamed', req.params.id);
        return p;
      },
    );
    app.post<{ Params: IdParams; Body: { mergeIds: string[] } }>(
      '/v1/budgets/:budgetId/payees/:id/merge',
      {
        schema: {
          body: {
            type: 'object',
            required: ['mergeIds'],
            additionalProperties: false,
            properties: { mergeIds: { type: 'array', minItems: 1, maxItems: 100, items: { type: 'string' } } },
          },
        },
      },
      async (req, reply) => {
        write(req, req.params.budgetId);
        await planning.mergePayees(req.params.budgetId, req.params.id, req.body.mergeIds);
        audit(deps, req, req.params.budgetId, 'payee.merged', req.params.id, req.body.mergeIds);
        return reply.status(204).send();
      },
    );
    app.delete<{ Params: IdParams }>('/v1/budgets/:budgetId/payees/:id', async (req, reply) => {
      write(req, req.params.budgetId);
      await planning.deletePayee(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'payee.deleted', req.params.id);
      return reply.status(204).send();
    });

    // Tags
    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/tags', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { items: await planning.tags(req.params.budgetId) };
    });
    app.post<{ Params: BudgetParams; Body: { tag: string; color?: string | null; description?: string | null } }>(
      '/v1/budgets/:budgetId/tags',
      { schema: { body: { ...TAG, required: ['tag'] } } },
      async (req, reply) => {
        write(req, req.params.budgetId);
        const t = await planning.createTag(req.params.budgetId, req.body);
        audit(deps, req, req.params.budgetId, 'tag.created', t.id);
        return reply.status(201).send(t);
      },
    );
    app.patch<{ Params: IdParams; Body: { tag?: string; color?: string | null; description?: string | null } }>(
      '/v1/budgets/:budgetId/tags/:id',
      { schema: { body: { ...TAG, minProperties: 1 } } },
      async (req) => {
        write(req, req.params.budgetId);
        const t = await planning.updateTag(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'tag.updated', req.params.id);
        return t;
      },
    );
    app.delete<{ Params: IdParams }>('/v1/budgets/:budgetId/tags/:id', async (req, reply) => {
      write(req, req.params.budgetId);
      await planning.deleteTag(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'tag.deleted', req.params.id);
      return reply.status(204).send();
    });

    // Category notes (where #template goal lines live)
    app.get<{ Params: IdParams }>('/v1/budgets/:budgetId/categories/:id/note', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return planning.categoryNote(req.params.budgetId, req.params.id);
    });
    app.put<{ Params: IdParams; Body: { note: string | null } }>(
      '/v1/budgets/:budgetId/categories/:id/note',
      { schema: { body: { type: 'object', required: ['note'], additionalProperties: false, properties: { note: { type: ['string', 'null'], maxLength: 10000 } } } } },
      async (req) => {
        write(req, req.params.budgetId);
        const n = await planning.setCategoryNote(req.params.budgetId, req.params.id, req.body.note);
        audit(deps, req, req.params.budgetId, 'category.note_updated', req.params.id);
        return n;
      },
    );
  };
