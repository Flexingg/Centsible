import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, rmSync } from 'node:fs';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { createDeps } from '../../src/deps.js';
import { buildServer, type Deps } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';
import { day, startFakeSimpleFin } from '../support/fake-simplefin.js';

// SimpleFIN end to end: a real Actual server, and a fake SimpleFIN Bridge speaking the
// real protocol (setup token -> claim -> access URL -> /accounts).
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'bank-sync');
const silent = { debug() {}, info() {}, warn() {} };
const today = new Date().toISOString().slice(0, 10);

let actual: SeededActual;
let sf: Awaited<ReturnType<typeof startFakeSimpleFin>>;
let store: HouseholdStore;
let host: ActualHost;
let deps: Deps;
let app: FastifyInstance;
let owner: string;
let member: string;
let viewer: string;
let b: string;

function tokenFor(role: 'owner' | 'member' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, path, res) as Record<string, any> };
}

async function waitForJob(jobId: string) {
  for (let i = 0; i < 100; i++) {
    const job = (await call('GET', `/v1/jobs/${jobId}`, '/v1/jobs/{jobId}', owner)).body;
    if (job.status !== 'running') return job;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('job did not finish');
}

beforeAll(async () => {
  sf = await startFakeSimpleFin([
    {
      id: 'SF-CHK', name: 'Everyday Checking', org: { domain: 'bank.example', name: 'Example Bank' }, balance: '1234.56',
      transactions: [
        { id: 'T1', posted: day(today), amount: '-12.34', description: 'COFFEE SHOP 123', payee: 'Coffee Shop' },
        { id: 'T2', posted: 0, amount: '-50.00', description: 'GAS 1234', payee: 'Gas Station', pending: true, transacted_at: day(today) },
      ],
    },
    { id: 'SF-CARD', name: 'Rewards Card', org: { domain: 'card.example', name: 'Card Co' }, balance: '-300.00', transactions: [] },
  ]);
  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  b = actual.budgetId;
  const dataDir = join(root, 'bridge');
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  const config: BridgeConfig = {
    port: 0, host: '127.0.0.1', dataDir, publicUrl: 'https://budget-api.example.com', trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0, accessTokenTtlSec: 3600, refreshTokenTtlSec: 3600, logLevel: 'silent',
  };
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  deps = createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent);
  app = await buildServer(deps, { logger: false });
  owner = tokenFor('owner');
  member = tokenFor('member');
  viewer = tokenFor('viewer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
  await sf?.stop();
});

describe('SimpleFIN setup', () => {
  it('starts out not connected, with the schedule off', async () => {
    const res = await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer);
    expect(res.body.simplefin).toEqual({ configured: false, requestsToday: 0, dailyQuota: 24, historyAccess: false });
    expect(res.body.backfill).toBeNull();
    expect(res.body.schedule).toMatchObject({ intervalHours: 0, nextRunAt: null, lastResult: null });
  });

  it('only owners can connect', async () => {
    const res = await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', member, { setupToken: sf.setupToken });
    expect(res.status).toBe(403);
  });

  it('rejects something that is not a setup token', async () => {
    const res = await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: 'definitely not a token!!' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/isn't a SimpleFIN setup token/);
  });

  it('connects with a setup token and claims it right away', async () => {
    const res = await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
    expect(res.status).toBe(200);
    expect(res.body.simplefin).toMatchObject({ configured: true, requestsToday: 1, historyAccess: true });
    expect(sf.stats.claims).toBe(1);
    recordFixture('bank-sync-overview', res.body);
  });

  it('explains a setup token that was already used', async () => {
    // Tokens are single-use: SimpleFIN answers the second claim with 403.
    const res = await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
    expect(res.status).toBe(502);
    expect(res.body.detail).toMatch(/Setup tokens work only once/);
    // The claim fails before anything is stored, so the working connection stays.
    expect((await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body.simplefin).toMatchObject({ configured: true, historyAccess: true });
  });
});

describe('linking and syncing', () => {
  let checkingId: string;

  beforeAll(async () => {
    // Reconnect with a fresh SimpleFIN (new token) for the rest of the suite.
    await sf.stop();
    sf = await startFakeSimpleFin(sf.accounts);
    await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
  });

  it('lists SimpleFIN accounts with their link status', async () => {
    const res = await call('GET', `/v1/budgets/${b}/bank-sync/simplefin/accounts`, '/v1/budgets/{budgetId}/bank-sync/simplefin/accounts', member);
    expect(res.body.items).toEqual([
      { id: 'SF-CHK', name: 'Everyday Checking', institution: 'Example Bank', balance: 123456, currency: 'USD', linkedAccountId: null, linkedAccountName: null },
      { id: 'SF-CARD', name: 'Rewards Card', institution: 'Card Co', balance: -30000, currency: 'USD', linkedAccountId: null, linkedAccountName: null },
    ]);
    recordFixture('simplefin-accounts', res.body);
  });

  it('links to a new account and imports its transactions', async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', member, { externalId: 'SF-CHK' });
    expect(res.status).toBe(201);
    checkingId = res.body.accountId;
    const accounts = (await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[];
    const acct = accounts.find((a) => a.id === checkingId);
    expect(acct).toMatchObject({ name: 'Everyday Checking', syncSource: 'simpleFin', bankSyncStatus: 'ok', balance: 123456 });
    const txs = (await call('GET', `/v1/budgets/${b}/transactions?accountId=${checkingId}`, '/v1/budgets/{budgetId}/transactions', member)).body.items as any[];
    expect(txs.map((t) => t.amount).sort()).toEqual([-5000, -1234, 129690].sort());
  });

  it('links to an existing account instead, keeping its history', async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', member, { externalId: 'SF-CARD', accountId: actual.accounts.card });
    expect(res.status).toBe(201);
    expect(res.body.accountId).toBe(actual.accounts.card);
    const list = (await call('GET', `/v1/budgets/${b}/bank-sync/simplefin/accounts?refresh=false`, '/v1/budgets/{budgetId}/bank-sync/simplefin/accounts', member)).body.items as any[];
    expect(list.map((a) => [a.id, a.linkedAccountName])).toEqual([['SF-CHK', 'Everyday Checking'], ['SF-CARD', 'Visa']]);
  });

  it("won't link the same SimpleFIN account twice", async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', member, { externalId: 'SF-CHK' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/already linked to "Everyday Checking"/);
  });

  it('has Actual\'s default sync options, and saves changes', async () => {
    const url = `/v1/budgets/${b}/accounts/${checkingId}/bank-sync-settings`;
    const path = '/v1/budgets/{budgetId}/accounts/{id}/bank-sync-settings';
    const defaults = (await call('GET', url, path, viewer)).body;
    expect(defaults).toEqual({
      importTransactions: true, importPending: true, importNotes: true, reimportDeleted: true, updateDates: false,
      mapping: { payment: { date: 'date', payee: 'payeeName', notes: 'notes' }, deposit: { date: 'date', payee: 'payeeName', notes: 'notes' } },
    });
    const mapping = { payment: { date: 'postedDate', payee: 'notes', notes: 'payeeName' }, deposit: { date: 'date', payee: 'payeeName', notes: 'notes' } };
    const saved = (await call('PATCH', url, path, member, { importPending: false, mapping })).body;
    expect(saved).toMatchObject({ importPending: false, mapping });
    recordFixture('bank-sync-settings', saved);
    expect((await call('PATCH', url, path, viewer, { importPending: true })).status).toBe(403);
  });

  it('syncs everything with one SimpleFIN request, reporting per account', async () => {
    sf.accounts[1]!.transactions.push({ id: 'C1', posted: day(today), amount: '-25.00', description: 'BOOKS', payee: 'Bookstore' });
    const before = sf.stats.accountRequests;
    const started = await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member);
    expect(started.status).toBe(202);
    const job = await waitForJob(started.body.id);
    expect(job.status).toBe('succeeded');
    expect(sf.stats.accountRequests - before).toBe(1);
    expect(job.result.simplefinRequests).toBe(1);
    const card = job.result.results.find((r: any) => r.accountId === actual.accounts.card);
    expect(card).toMatchObject({ name: 'Visa', newTransactions: 1, error: null, status: 'ok' });
    recordFixture('bank-sync-summary', job.result);
  });

  it('reports a SimpleFIN problem per account instead of failing the sync', async () => {
    sf.accounts.splice(1, 1); // the card disappears from the SimpleFIN connection
    const started = await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member);
    const job = await waitForJob(started.body.id);
    expect(job.status).toBe('succeeded');
    const card = job.result.results.find((r: any) => r.accountId === actual.accounts.card);
    expect(card.status).toBe('account-missing');
    expect(card.error).toBeTruthy();
  });

  it('unlinks an account, keeping its transactions', async () => {
    const res = await call('POST', `/v1/budgets/${b}/accounts/${actual.accounts.card}/unlink`, '/v1/budgets/{budgetId}/accounts/{id}/unlink', member);
    expect(res.status).toBe(204);
    const accounts = (await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[];
    expect(accounts.find((a) => a.id === actual.accounts.card)).toMatchObject({ syncSource: null, bankSyncStatus: null });
  });

  it('counts every SimpleFIN request against the daily quota', async () => {
    const res = await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer);
    // Everything the current (second) fake SimpleFIN saw, plus the first connection's
    // account listing (a rejected claim never gets as far as a data request).
    expect(res.body.simplefin.requestsToday).toBe(sf.stats.accountRequests + 1);
  });
});

describe('background sync', () => {
  it('only owners set the schedule, to one of the offered intervals', async () => {
    expect((await call('PUT', '/v1/bank-sync/schedule', '/v1/bank-sync/schedule', member, { intervalHours: 6 })).status).toBe(403);
    expect((await call('PUT', '/v1/bank-sync/schedule', '/v1/bank-sync/schedule', owner, { intervalHours: 5 })).status).toBe(400);
    const res = await call('PUT', '/v1/bank-sync/schedule', '/v1/bank-sync/schedule', owner, { intervalHours: 6 });
    expect(res.body).toMatchObject({ intervalHours: 6, lastRunAt: null });
    expect(res.body.nextRunAt).toBeTruthy();
  });

  it('runs when due, then waits for the next interval', async () => {
    sf.accounts[0]!.transactions.push({ id: 'T3', posted: day(today), amount: '-9.99', description: 'MUSIC', payee: 'Music Service' });
    expect(await deps.scheduler.tick()).toBe(true);
    const state = deps.scheduler.state();
    expect(state.lastResult).toMatchObject({ newTransactions: 1, errors: [], skipped: null });
    expect(Date.parse(state.nextRunAt!) - Date.parse(state.lastRunAt!)).toBe(6 * 3600_000);
    expect(await deps.scheduler.tick()).toBe(false); // not due again yet
  });

  it('skips a run near SimpleFIN\'s daily quota, and says why', async () => {
    for (let i = 0; i < 20; i++) store.recordSimpleFinRequest();
    store.setSetting('bank_sync.last_run_at', null);
    const before = sf.stats.accountRequests;
    expect(await deps.scheduler.tick()).toBe(true);
    expect(sf.stats.accountRequests).toBe(before);
    expect(deps.scheduler.state().lastResult?.skipped).toMatch(/SimpleFIN allows about 24/);
  });

  it('turns off', async () => {
    const res = await call('PUT', '/v1/bank-sync/schedule', '/v1/bank-sync/schedule', owner, { intervalHours: 0 });
    expect(res.body).toMatchObject({ intervalHours: 0, nextRunAt: null });
    expect(await deps.scheduler.tick()).toBe(false);
  });
});

describe('disconnecting', () => {
  it('owners can reset the SimpleFIN credentials', async () => {
    expect((await call('DELETE', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', member)).status).toBe(403);
    expect((await call('DELETE', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner)).status).toBe(204);
    expect((await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body.simplefin).toMatchObject({ configured: false, historyAccess: false });
  });
});
