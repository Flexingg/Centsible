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
import { startFakeSimpleFin } from '../support/fake-simplefin.js';

// History import: a real Actual server and a fake SimpleFIN Bridge. SimpleFIN Bridge
// serves at most 90 days per request, so every window the bridge asks for is checked.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'backfill');
const silent = { debug() {}, info() {}, warn() {} };
const DAY = 86400;
const now = Math.floor(Date.now() / 1000);
const ago = (days: number) => now - days * DAY;
const isoAgo = (days: number) => new Date(ago(days) * 1000).toISOString().slice(0, 10);

let actual: SeededActual;
let sf: Awaited<ReturnType<typeof startFakeSimpleFin>>;
let store: HouseholdStore;
let host: ActualHost;
let deps: Deps;
let app: FastifyInstance;
let owner: string;
let member: string;
let b: string;
let checkingId: string;
const clock = { offset: 0 }; // moves the quota's rolling day forward

function tokenFor(role: 'owner' | 'member') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, path, res) as Record<string, any> };
}

async function settled() {
  for (let i = 0; i < 300; i++) {
    const s = deps.backfill.state();
    if (s && s.status !== 'running') return s;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('history import did not settle');
}

const transactions = async () =>
  (await call('GET', `/v1/budgets/${b}/transactions?accountId=${checkingId}&limit=500`, '/v1/budgets/{budgetId}/transactions', owner)).body.items as any[];

beforeAll(async () => {
  sf = await startFakeSimpleFin([
    {
      id: 'SF-CHK', name: 'Everyday Checking', org: { domain: 'bank.example', name: 'Example Bank' }, balance: '1000.00',
      transactions: [
        { id: 'H0', posted: ago(1), amount: '-10.00', description: 'COFFEE', payee: 'Coffee Shop' },
        { id: 'H1', posted: ago(120), amount: '-20.00', description: 'GROCER 1', payee: 'Grocer' },
        { id: 'H2', posted: ago(250), amount: '2500.00', description: 'PAYROLL', payee: 'Employer' },
        { id: 'H3', posted: ago(420), amount: '-40.00', description: 'HARDWARE', payee: 'Hardware Store' },
        { id: 'H4', posted: ago(700), amount: '-50.00', description: 'BOOKS #12', payee: 'Bookstore' },
      ],
    },
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
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'), undefined, () => Date.now() + clock.offset);
  host = new ActualHost(config, silent);
  await host.start();
  deps = createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent);
  app = await buildServer(deps, { logger: false });
  owner = tokenFor('owner');
  member = tokenFor('member');
  await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
  // Linking imports what Actual's own sync reaches: the last 90 days.
  checkingId = (await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', owner, { externalId: 'SF-CHK' })).body.accountId;
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
  await sf?.stop();
});

describe('history import', () => {
  it("starts with only Actual's 90 days", async () => {
    const amounts = (await transactions()).map((t) => t.amount).sort((x, y) => x - y);
    expect(amounts).toEqual([-1000, 101000]); // coffee, and the opening balance
  });

  it('is for owners, and up to 10 years', async () => {
    const url = `/v1/budgets/${b}/bank-sync/simplefin/backfill`;
    const path = '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill';
    expect((await call('POST', url, path, member, { years: 2 })).status).toBe(403);
    expect((await call('POST', url, path, owner, { years: 11 })).status).toBe(400);
    expect((await call('POST', url, path, owner, { years: 2, accountIds: ['not-linked'] })).status).toBe(400);
  });

  it('walks back in 90-day windows and imports everything the bank has', async () => {
    const before = sf.stats.queries.length;
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/backfill`, '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill', owner, { years: 2 });
    expect(res.status).toBe(202);
    expect(res.body).toMatchObject({ status: 'running', accountIds: [checkingId], since: isoAgo(730) });
    const state = await settled();
    expect(state).toMatchObject({ status: 'done', transactionsAdded: 4 });
    expect(state.message).toMatch(/Imported 4 older transactions/);

    const windows = sf.stats.queries.slice(before);
    expect(windows.length).toBe(state.windowsDone);
    for (const q of windows) {
      expect(Number(q.get('end-date')) - Number(q.get('start-date'))).toBeLessThanOrEqual(90 * DAY);
      expect(q.getAll('account')).toEqual(['SF-CHK']);
    }
    const overview = (await call('GET', '/v1/bank-sync', '/v1/bank-sync', member)).body;
    expect(overview.backfill).toMatchObject({ status: 'done', windowsDone: state.windowsDone });
    recordFixture('bank-sync-backfill', overview.backfill);
  });

  it('keeps the balance the bank reports by moving the opening balance behind the history', async () => {
    const txs = await transactions();
    const accounts = (await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', owner)).body.items as any[];
    expect(accounts.find((a) => a.id === checkingId).balance).toBe(100000);
    expect(txs.map((t) => t.amount).sort((x, y) => x - y)).toEqual([-5000, -4000, -2000, -1000, 101000 - 250000 + 11000, 250000].sort((x, y) => x - y));
    const opening = txs.find((t) => t.amount === 101000 - 250000 + 11000);
    expect(opening.date <= isoAgo(700)).toBe(true);
    const books = txs.find((t) => t.amount === -5000);
    expect(books).toMatchObject({ date: isoAgo(700), notes: 'BOOKS ##12' });
  });

  it("stops early once the bank has nothing older", async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/backfill`, '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill', owner, { years: 10 });
    expect(res.status).toBe(202);
    const state = await settled();
    expect(state).toMatchObject({ status: 'done', transactionsAdded: 0, windowsDone: 5 }); // one overlapping, four empty
    expect(state.message).toMatch(/didn't share anything older/);
    expect((await transactions()).length).toBe(6); // nothing imported twice
  });

  it("pauses at SimpleFIN's daily limit and carries on when it frees up", async () => {
    sf.accounts[0]!.transactions.push({ id: 'H5', posted: ago(800), amount: '-60.00', description: 'OLD', payee: 'Old Shop' });
    for (let i = store.simpleFinRequestsToday(); i < 16; i++) store.recordSimpleFinRequest();
    const before = sf.stats.queries.length;
    await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/backfill`, '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill', owner, { years: 3 });
    const waiting = await settled();
    expect(waiting.status).toBe('waiting');
    expect(waiting.message).toMatch(/daily limit/);
    expect(sf.stats.queries.length).toBe(before);

    clock.offset = 25 * 3600_000; // a day later
    await deps.backfill.tick();
    const state = await settled();
    expect(state).toMatchObject({ status: 'done', transactionsAdded: 1 });
  });

  it('can be cancelled, by owners', async () => {
    clock.offset = 0; // quota spent again
    await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/backfill`, '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill', owner, { years: 10 });
    expect((await settled()).status).toBe('waiting');
    expect((await call('DELETE', '/v1/bank-sync/backfill', '/v1/bank-sync/backfill', member)).status).toBe(403);
    const res = await call('DELETE', '/v1/bank-sync/backfill', '/v1/bank-sync/backfill', owner);
    expect(res.body.backfill).toMatchObject({ status: 'cancelled' });
    expect(res.body.backfill.message).toMatch(/was kept/);
  });

  it('needs the bridge to hold the SimpleFIN access', async () => {
    await call('DELETE', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner);
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/backfill`, '/v1/budgets/{budgetId}/bank-sync/simplefin/backfill', owner, { years: 1 });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/Paste a new setup token/);
  });
});
