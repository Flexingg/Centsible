import type { FastifyPluginAsync } from 'fastify';
import type { ImportFile } from '../../actual/account-ops.js';
import { ApiError } from '../../errors.js';
import { audit, requireBudget, type Deps } from '../server.js';
import { MONEY, MONTH, type BudgetParams } from './budgets.js';

type IdParams = BudgetParams & { id: string };

const IMPORT_BODY = {
  type: 'object',
  required: ['fileName', 'contentBase64'],
  additionalProperties: false,
  properties: {
    fileName: { type: 'string', minLength: 1, maxLength: 200 },
    contentBase64: { type: 'string', minLength: 1 },
    options: {
      type: 'object',
      additionalProperties: false,
      properties: {
        dateFormat: { type: 'string', enum: ['yyyy-MM-dd', 'MM/dd/yyyy', 'dd/MM/yyyy', 'MM/dd/yy', 'dd/MM/yy'] },
        hasHeaderRow: { type: 'boolean' },
        delimiter: { type: 'string', maxLength: 1 },
        invertAmounts: { type: 'boolean' },
        csvMapping: {
          type: 'object',
          additionalProperties: false,
          properties: Object.fromEntries(['date', 'payee', 'amount', 'inflow', 'outflow', 'notes'].map((k) => [k, { type: 'string' }])),
        },
      },
    },
  },
};
// Base64 of a 10 MB statement, with room to spare. Cloudflare allows 100 MB.
const IMPORT_LIMIT = 16 * 1024 * 1024;

/** Bank sync jobs, statement import, reconciliation, reports and goal templates. */
export const operationRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { accountOps, reports, ops, jobs } = deps;

    // ── Bank sync (async: 202 + job polling) ──
    const startSync = (budgetId: string, accountId: string | null) => {
      const running = jobs.running('bank-sync', budgetId);
      return running ?? jobs.start('bank-sync', budgetId, () => accountOps.bankSync(budgetId, accountId));
    };
    app.post<{ Params: IdParams }>('/v1/budgets/:budgetId/accounts/:id/bank-sync', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      audit(deps, req, req.params.budgetId, 'bank_sync.started', req.params.id);
      return reply.status(202).send(startSync(req.params.budgetId, req.params.id));
    });
    app.post<{ Params: BudgetParams }>('/v1/budgets/:budgetId/bank-sync', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      audit(deps, req, req.params.budgetId, 'bank_sync.started');
      return reply.status(202).send(startSync(req.params.budgetId, null));
    });
    app.get<{ Params: { jobId: string } }>('/v1/jobs/:jobId', async (req) => {
      const job = jobs.get(req.params.jobId);
      if (!job) throw ApiError.notFound('Job not found');
      requireBudget(deps, req, job.budgetId);
      return job;
    });

    // ── Statement import ──
    app.post<{ Params: IdParams; Body: ImportFile }>(
      '/v1/budgets/:budgetId/accounts/:id/import/preview',
      { bodyLimit: IMPORT_LIMIT, schema: { body: IMPORT_BODY } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        return accountOps.previewImport(req.params.budgetId, req.params.id, req.body);
      },
    );
    app.post<{ Params: IdParams; Body: ImportFile }>(
      '/v1/budgets/:budgetId/accounts/:id/import',
      { bodyLimit: IMPORT_LIMIT, schema: { body: IMPORT_BODY } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await accountOps.commitImport(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'import.committed', req.params.id, { file: req.body.fileName, added: res.added, updated: res.updated });
        return res;
      },
    );

    // ── Reconcile ──
    app.get<{ Params: IdParams }>('/v1/budgets/:budgetId/accounts/:id/reconcile', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return accountOps.reconcileStatus(req.params.budgetId, req.params.id);
    });
    app.post<{ Params: IdParams; Body: { statementBalance: number; createAdjustment?: boolean } }>(
      '/v1/budgets/:budgetId/accounts/:id/reconcile',
      {
        schema: {
          body: {
            type: 'object',
            required: ['statementBalance'],
            additionalProperties: false,
            properties: { statementBalance: MONEY, createAdjustment: { type: 'boolean' } },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await accountOps.reconcile(req.params.budgetId, req.params.id, req.body.statementBalance, req.body.createAdjustment ?? false);
        if (res.reconciled) audit(deps, req, req.params.budgetId, 'account.reconciled', req.params.id, { difference: res.difference });
        return res;
      },
    );

    // ── Reports ──
    const RANGE = { type: 'object', required: ['start', 'end'], properties: { start: MONTH, end: MONTH } };
    app.get<{ Params: BudgetParams; Querystring: { start: string; end: string } }>(
      '/v1/budgets/:budgetId/reports/cash-flow',
      { schema: { querystring: RANGE } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        if (req.query.start > req.query.end) throw ApiError.validation('start must not be after end');
        return reports.cashFlow(req.params.budgetId, req.query.start, req.query.end);
      },
    );
    app.get<{ Params: BudgetParams; Querystring: { start: string; end: string } }>(
      '/v1/budgets/:budgetId/reports/spending',
      { schema: { querystring: RANGE } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        if (req.query.start > req.query.end) throw ApiError.validation('start must not be after end');
        return reports.spending(req.params.budgetId, req.query.start, req.query.end);
      },
    );
    app.get<{ Params: BudgetParams; Querystring: { months?: number } }>(
      '/v1/budgets/:budgetId/reports/net-worth',
      { schema: { querystring: { type: 'object', properties: { months: { type: 'integer', minimum: 1, maximum: 120, default: 12 } } } } },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId);
        return reports.netWorth(req.params.budgetId, req.query.months ?? 12);
      },
    );

    // ── Goal templates ──
    app.post<{ Params: BudgetParams & { month: string }; Body: { overwrite?: boolean } }>(
      '/v1/budgets/:budgetId/months/:month/apply-templates',
      {
        schema: {
          params: { type: 'object', properties: { budgetId: { type: 'string' }, month: MONTH } },
          body: { type: ['object', 'null'], additionalProperties: false, properties: { overwrite: { type: 'boolean' } } },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await ops.applyTemplates(req.params.budgetId, req.params.month, req.body?.overwrite ?? false);
        audit(deps, req, req.params.budgetId, 'budget.templates_applied', undefined, { month: req.params.month });
        return res;
      },
    );
    app.get<{ Params: BudgetParams }>('/v1/budgets/:budgetId/templates/check', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return ops.checkTemplates(req.params.budgetId);
    });
  };
