import { randomUUID } from 'node:crypto';
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
import { expectContract } from '../support/contract.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'income');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let deps: Deps;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let b: string;

function tokenFor(role: 'owner' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({
    method: method as 'GET',
    url: `/v1/budgets/${b}${url}`,
    headers: { authorization: `Bearer ${token}` },
    ...(body !== undefined ? { payload: body as object } : {}),
  });
  return { status: res.statusCode, body: expectContract(method, `/v1/budgets/{budgetId}${path}`, res) as Record<string, any> };
}

beforeAll(async () => {
  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  b = actual.budgetId;
  const dataDir = join(root, 'bridge');
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  const config: BridgeConfig = {
    port: 0,
    host: '127.0.0.1',
    dataDir,
    publicUrl: 'https://budget-api.example.com',
    trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0,
    accessTokenTtlSec: 3600,
    refreshTokenTtlSec: 3600,
    logLevel: 'silent',
  };
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  deps = createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent);
  app = await buildServer(deps, { logger: false });
  owner = tokenFor('owner');
  viewer = tokenFor('viewer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

describe('income tracking and pay stubs', () => {
  it('tracks yearly income for all inflows or chosen categories, and learns pay stub templates', async () => {
    const year = Number(actual.month.slice(0, 4));
    // Add an extra inflow in General so 'all' vs 'categories' differs.
    await call('POST', '/transactions', '/transactions', owner, {
      id: randomUUID(),
      accountId: actual.accounts.checking,
      date: `${year}-01-15`,
      amount: 50000,
      payeeName: 'Side Gig',
      categoryId: actual.categories.General!,
    });

    const allOverview = await call('GET', `/income/overview?year=${year}`, '/income/overview', viewer);
    expect(allOverview.status).toBe(200);
    expect(allOverview.body.mode).toBe('all');
    expect(allOverview.body.months).toHaveLength(12);
    expect(allOverview.body.summary.ytdTotal).toBeGreaterThanOrEqual(50000);

    // Viewer cannot change settings or add pay stubs.
    expect((await call('PUT', '/income/settings', '/income/settings', viewer, { mode: 'categories', categoryIds: [actual.categories.General!] })).status).toBe(403);

    // Owner switches to tracking a specific category.
    const saved = await call('PUT', '/income/settings', '/income/settings', owner, {
      mode: 'categories',
      categoryIds: [actual.categories.General!],
    });
    expect(saved.status).toBe(200);
    expect(saved.body).toEqual({ mode: 'categories', categoryIds: [actual.categories.General!] });

    // Enter two pay stubs with different hours & gross so the bridge learns fixed vs % deductions.
    const stub1 = await call('POST', '/income/paystubs', '/income/paystubs', owner, {
      date: `${year}-01-15`,
      employer: 'Acme Corp',
      hours: 80,
      gross: 400000, // $4,000 ($50/hr)
      deductions: [
        { name: '401(k)', kind: 'retirement', amount: 24000 }, // 6%
        { name: 'Federal Tax', kind: 'tax', amount: 60000 }, // 15%
        { name: 'Health Insurance', kind: 'benefit', amount: 12000 }, // $120 fixed
      ],
    });
    expect(stub1.status).toBe(201);
    expect(stub1.body.net).toBe(400000 - 24000 - 60000 - 12000);

    const stub2 = await call('POST', '/income/paystubs', '/income/paystubs', owner, {
      date: `${year}-01-31`,
      employer: 'Acme Corp',
      hours: 90,
      gross: 450000, // $4,500 ($50/hr)
      deductions: [
        { name: '401(k)', kind: 'retirement', amount: 27000 }, // 6%
        { name: 'Federal Tax', kind: 'tax', amount: 67500 }, // 15%
        { name: 'Health Insurance', kind: 'benefit', amount: 12000 }, // $120 fixed
      ],
    });
    expect(stub2.status).toBe(201);

    const overview = await call('GET', `/income/overview?year=${year}`, '/income/overview', viewer);
    expect(overview.status).toBe(200);
    expect(overview.body.mode).toBe('categories');
    expect(overview.body.payStubSummary.stubCount).toBe(2);
    expect(overview.body.payStubSummary.totalHours).toBe(170);
    expect(overview.body.payStubSummary.totalGross).toBe(850000);
    expect(overview.body.payStubSummary.byKind.retirement).toBe(51000);
    expect(overview.body.payStubSummary.byKind.tax).toBe(127500);
    expect(overview.body.payStubSummary.byKind.benefit).toBe(24000);
    expect(overview.body.payStubSummary.effectiveTaxRate).toBe(15);

    // Learned template: $50/hr, 401(k) 6% variable, Federal Tax 15% variable, Health Insurance $120 fixed!
    const tpl = overview.body.template;
    expect(tpl.hourlyRate).toBe(5000);
    expect(tpl.employer).toBe('Acme Corp');
    const byName = Object.fromEntries(tpl.deductions.map((d: any) => [d.name, d]));
    expect(byName['401(k)']).toMatchObject({ mode: 'percent', rate: 6 });
    expect(byName['Federal Tax']).toMatchObject({ mode: 'percent', rate: 15 });
    expect(byName['Health Insurance']).toMatchObject({ mode: 'fixed', amount: 12000 });

    // Update and delete work cleanly.
    const updated = await call('PUT', `/income/paystubs/${stub1.body.id}`, '/income/paystubs/{id}', owner, {
      date: `${year}-01-15`,
      employer: 'Acme Corp',
      hours: 80,
      gross: 400000,
      deductions: [{ name: '401(k)', kind: 'retirement', amount: 30000, mode: 'percent', rate: 7.5 }],
      notes: 'Updated',
    });
    expect(updated.status).toBe(200);
    expect(updated.body.net).toBe(370000);

    expect((await call('DELETE', `/income/paystubs/${stub1.body.id}`, '/income/paystubs/{id}', owner)).status).toBe(204);
    expect((await call('DELETE', `/income/paystubs/${stub1.body.id}`, '/income/paystubs/{id}', owner)).status).toBe(404);
  });
});
