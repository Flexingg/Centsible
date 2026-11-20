import type { FastifyPluginAsync } from 'fastify';
import { SIMPLEFIN_DAILY_QUOTA, SIMPLEFIN_FIELDS, type BankSyncSettings } from '../../actual/bank-sync-ops.js';
import { BACKFILL_MAX_YEARS, type BackfillState } from '../../bank-sync-backfill.js';
import { SYNC_INTERVALS } from '../../bank-sync-scheduler.js';
import { PLAID_ENVIRONMENTS, type PlaidEnvironment } from '../../actual/plaid-client.js';
import { audit, requireBudget, requireRole, type Deps } from '../server.js';
import { DATE } from './budgets.js';

type BudgetParams = { budgetId: string };

/** What the app sees of a history import (the walk's internals stay in the bridge). */
export function publicBackfill(s: BackfillState | null) {
  if (!s) return null;
  const { windowEnd: _e, retryAt: _r, emptyStreak: _s, ...rest } = s;
  return rest;
}
type AccountParams = BudgetParams & { id: string };

const mapping = {
  type: 'object',
  required: ['date', 'payee', 'notes'],
  additionalProperties: false,
  properties: {
    date: { type: 'string', enum: [...SIMPLEFIN_FIELDS.date] },
    payee: { type: 'string', enum: [...SIMPLEFIN_FIELDS.payee] },
    notes: { type: 'string', enum: [...SIMPLEFIN_FIELDS.notes] },
  },
};

/** SimpleFIN setup, linking, per-account sync options and the background schedule. */
export const bankSyncRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { bankSync, scheduler, store, backfill, plaid, jobs } = deps;
    const overview = async () => ({
      simplefin: { ...(await bankSync.status()), requestsToday: bankSync.requestsToday(), dailyQuota: SIMPLEFIN_DAILY_QUOTA, historyAccess: bankSync.historyAccess },
      plaid: plaid.status(),
      schedule: scheduler.state(),
      intervals: [...SYNC_INTERVALS],
      backfill: publicBackfill(backfill.state()),
    });

    // ── Household-wide (the SimpleFIN token is server-wide in Actual) ──
    app.get('/v1/bank-sync', async (req) => {
      requireRole(req, 'viewer');
      return overview();
    });

    app.put<{ Body: { setupToken: string } }>(
      '/v1/bank-sync/simplefin',
      { schema: { body: { type: 'object', required: ['setupToken'], additionalProperties: false, properties: { setupToken: { type: 'string', minLength: 10, maxLength: 4096 } } } } },
      async (req) => {
        const { member, device } = requireRole(req, 'owner');
        const res = await bankSync.connect(req.body.setupToken);
        store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.simplefin_connected', detail: { accounts: res.accounts } });
        return overview();
      },
    );

    app.delete('/v1/bank-sync/simplefin', async (req, reply) => {
      const { member, device } = requireRole(req, 'owner');
      await bankSync.reset();
      store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.simplefin_reset' });
      return reply.status(204).send();
    });

    app.put<{ Body: { intervalHours: number } }>(
      '/v1/bank-sync/schedule',
      { schema: { body: { type: 'object', required: ['intervalHours'], additionalProperties: false, properties: { intervalHours: { type: 'integer', enum: [...SYNC_INTERVALS] } } } } },
      async (req) => {
        const { member, device } = requireRole(req, 'owner');
        const state = scheduler.setInterval(req.body.intervalHours);
        store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.schedule_set', detail: { intervalHours: req.body.intervalHours } });
        return state;
      },
    );

    app.delete('/v1/bank-sync/backfill', async (req) => {
      const { member, device } = requireRole(req, 'owner');
      const state = backfill.cancel();
      store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.backfill_cancelled' });
      return { backfill: publicBackfill(state) };
    });

    // ── Plaid (household-wide: the keys and the bank logins) ──
    app.put<{ Body: { clientId: string; secret?: string; environment: PlaidEnvironment } }>(
      '/v1/bank-sync/plaid',
      {
        schema: {
          body: {
            type: 'object',
            required: ['clientId', 'environment'],
            additionalProperties: false,
            properties: {
              clientId: { type: 'string', minLength: 4, maxLength: 200 },
              secret: { type: 'string', maxLength: 200 },
              environment: { type: 'string', enum: [...PLAID_ENVIRONMENTS] },
            },
          },
        },
      },
      async (req) => {
        const { member, device } = requireRole(req, 'owner');
        const res = await plaid.configure(req.body);
        store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.plaid_configured', detail: { environment: req.body.environment } });
        return res;
      },
    );

    app.delete('/v1/bank-sync/plaid', async (req, reply) => {
      const { member, device } = requireRole(req, 'owner');
      await plaid.reset();
      store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.plaid_reset' });
      return reply.status(204).send();
    });

    app.post<{ Body: { itemId?: string | null } }>(
      '/v1/bank-sync/plaid/link-token',
      { schema: { body: { type: 'object', additionalProperties: false, properties: { itemId: { type: ['string', 'null'], maxLength: 200 } } } } },
      async (req) => {
        const { member } = requireRole(req, 'member'); // anyone who can link accounts can connect their bank
        return plaid.linkToken({ memberId: member.id, itemId: req.body.itemId ?? null });
      },
    );

    app.post<{ Body: { publicToken?: string | null; itemId?: string | null; institution?: string | null } }>(
      '/v1/bank-sync/plaid/items',
      {
        schema: {
          body: {
            type: 'object',
            additionalProperties: false,
            properties: {
              publicToken: { type: ['string', 'null'], maxLength: 1000 },
              itemId: { type: ['string', 'null'], maxLength: 200 },
              institution: { type: ['string', 'null'], maxLength: 200 },
            },
          },
        },
      },
      async (req, reply) => {
        const { member, device } = requireRole(req, 'member');
        const res = await plaid.addItem(req.body);
        store.audit({ memberId: member.id, deviceId: device.id, action: req.body.itemId ? 'bank_sync.plaid_item_reconnected' : 'bank_sync.plaid_item_added', target: res.id, detail: { institution: res.institution } });
        return reply.status(201).send(res);
      },
    );

    app.delete<{ Params: { id: string } }>('/v1/bank-sync/plaid/items/:id', async (req, reply) => {
      const { member, device } = requireRole(req, 'owner');
      await plaid.removeItem(req.params.id);
      store.audit({ memberId: member.id, deviceId: device.id, action: 'bank_sync.plaid_item_removed', target: req.params.id });
      return reply.status(204).send();
    });

    // ── Per budget ──
    app.post<{ Params: BudgetParams; Body: { since: string; accountIds?: string[] } }>(
      '/v1/budgets/:budgetId/bank-sync/plaid/history',
      {
        schema: {
          body: {
            type: 'object',
            required: ['since'],
            additionalProperties: false,
            properties: { since: DATE, accountIds: { type: 'array', items: { type: 'string', minLength: 1 }, maxItems: 100 } },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const { budgetId } = req.params;
        // Shares the bank sync slot: a history import and a sync of the same budget can't overlap.
        const running = jobs.running('bank-sync', budgetId);
        if (running) return reply.status(202).send(running);
        audit(deps, req, budgetId, 'bank_sync.plaid_history', undefined, { since: req.body.since, accounts: req.body.accountIds?.length ?? 'all' });
        return reply.status(202).send(jobs.start('bank-sync', budgetId, () => bankSync.plaidHistory(budgetId, req.body.since, req.body.accountIds)));
      },
    );

    app.get<{ Params: BudgetParams; Querystring: { refresh?: boolean } }>('/v1/budgets/:budgetId/bank-sync/plaid/accounts', async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      return { items: await plaid.listAccounts(req.params.budgetId, req.query.refresh !== false) };
    });

    app.post<{ Params: BudgetParams; Body: { plaidAccountId: string; accountId: string; startDate?: string } }>(
      '/v1/budgets/:budgetId/bank-sync/plaid/link',
      {
        schema: {
          body: {
            type: 'object',
            required: ['plaidAccountId', 'accountId'],
            additionalProperties: false,
            properties: { plaidAccountId: { type: 'string', minLength: 1 }, accountId: { type: 'string', minLength: 1 }, startDate: DATE },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await plaid.link(req.params.budgetId, req.body);
        audit(deps, req, req.params.budgetId, 'bank_sync.plaid_linked', res.accountId, { plaidAccountId: req.body.plaidAccountId });
        return reply.status(201).send(res);
      },
    );

    // ── Per budget ──
    app.post<{ Params: BudgetParams; Body: { years: number; accountIds?: string[] } }>(
      '/v1/budgets/:budgetId/bank-sync/simplefin/backfill',
      {
        schema: {
          body: {
            type: 'object',
            required: ['years'],
            additionalProperties: false,
            properties: {
              years: { type: 'integer', minimum: 1, maximum: BACKFILL_MAX_YEARS },
              accountIds: { type: 'array', items: { type: 'string', minLength: 1 }, maxItems: 100 },
            },
          },
        },
      },
      async (req, reply) => {
        requireRole(req, 'owner'); // spends the household's SimpleFIN quota for days
        requireBudget(deps, req, req.params.budgetId, 'member');
        const state = await backfill.start(req.params.budgetId, req.body);
        audit(deps, req, req.params.budgetId, 'bank_sync.backfill_started', undefined, { years: req.body.years, accounts: state.accountIds.length });
        return reply.status(202).send(publicBackfill(state));
      },
    );

    app.get<{ Params: BudgetParams; Querystring: { refresh?: boolean } }>('/v1/budgets/:budgetId/bank-sync/simplefin/accounts', async (req) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      return { items: await bankSync.listAccounts(req.params.budgetId, req.query.refresh !== false) };
    });

    app.post<{ Params: BudgetParams; Body: { externalId: string; accountId?: string; offBudget?: boolean; startDate?: string } }>(
      '/v1/budgets/:budgetId/bank-sync/simplefin/link',
      {
        schema: {
          body: {
            type: 'object',
            required: ['externalId'],
            additionalProperties: false,
            properties: { externalId: { type: 'string', minLength: 1 }, accountId: { type: 'string' }, offBudget: { type: 'boolean' }, startDate: DATE },
          },
        },
      },
      async (req, reply) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await bankSync.link(req.params.budgetId, req.body.externalId, req.body);
        audit(deps, req, req.params.budgetId, 'bank_sync.linked', res.accountId, { externalId: req.body.externalId });
        return reply.status(201).send(res);
      },
    );

    app.post<{ Params: AccountParams }>('/v1/budgets/:budgetId/accounts/:id/unlink', async (req, reply) => {
      requireBudget(deps, req, req.params.budgetId, 'member');
      if (!plaid.unlink(req.params.budgetId, req.params.id)) await bankSync.unlink(req.params.budgetId, req.params.id);
      audit(deps, req, req.params.budgetId, 'bank_sync.unlinked', req.params.id);
      return reply.status(204).send();
    });

    app.get<{ Params: AccountParams }>('/v1/budgets/:budgetId/accounts/:id/bank-sync-settings', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return bankSync.settings(req.params.budgetId, req.params.id);
    });

    app.patch<{ Params: AccountParams; Body: Partial<BankSyncSettings> }>(
      '/v1/budgets/:budgetId/accounts/:id/bank-sync-settings',
      {
        schema: {
          body: {
            type: 'object',
            additionalProperties: false,
            properties: {
              importTransactions: { type: 'boolean' },
              importPending: { type: 'boolean' },
              importNotes: { type: 'boolean' },
              reimportDeleted: { type: 'boolean' },
              updateDates: { type: 'boolean' },
              mapping: { type: 'object', required: ['payment', 'deposit'], additionalProperties: false, properties: { payment: mapping, deposit: mapping } },
            },
          },
        },
      },
      async (req) => {
        requireBudget(deps, req, req.params.budgetId, 'member');
        const res = await bankSync.updateSettings(req.params.budgetId, req.params.id, req.body);
        audit(deps, req, req.params.budgetId, 'bank_sync.settings_updated', req.params.id, req.body);
        return res;
      },
    );

  };
