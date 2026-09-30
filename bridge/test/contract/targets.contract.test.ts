import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, rmSync } from 'node:fs';
import type { FastifyInstance } from 'fastify';
import * as api from '@actual-app/api';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { createDeps } from '../../src/deps.js';
import type { Deps } from '../../src/http/server.js';
import { buildServer } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Goals on accounts and on monthly spending (limits, and at least an amount or a share of
// income), against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'targets');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let b: string;
let deps: Deps;
const dayFromNow = (n: number) => new Date(Date.now() + n * 86400_000).toISOString().slice(0, 10);

function tokenFor(role: 'owner' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url: `/v1/budgets/${b}${url}`, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, `/v1/budgets/{budgetId}${path}`, res) as Record<string, any> };
}

beforeAll(async () => {
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
  viewer = tokenFor('viewer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

describe('goals on accounts and spending', () => {
  it('tracks a spending limit, a share of income, and an account balance', async () => {
    const { Food, General } = actual.categories as Record<string, string>;
    expect((await call('POST', '/targets', '/targets', viewer, { kind: 'spend-under', categoryId: Food, amount: 20000 })).status).toBe(403);
    expect((await call('POST', '/targets', '/targets', owner, { kind: 'spend-under', amount: 20000 })).status).toBe(400);
    const limit = await call('POST', '/targets', '/targets', owner, { kind: 'spend-under', categoryId: Food, amount: 20000 });
    expect(limit.status).toBe(201);
    await call('POST', '/targets', '/targets', owner, { kind: 'spend-at-least', categoryId: General, percentOfIncome: 1, name: 'Giving' });
    await call('POST', '/targets', '/targets', owner, { kind: 'account', accountId: actual.accounts.checking, amount: 1_000_000 });

    const res = await call('GET', '/targets', '/targets', viewer);
    recordFixture('targets', res.body);
    const [food, giving, saving] = res.body.items as any[];
    // Food this month: 98.34 (split at the hardware store) + 45.23 (Trader Joe's).
    expect(food).toMatchObject({ kind: 'spend-under', name: 'Food', current: 14357, goal: 20000 });
    expect(['on-track', 'behind', 'reached']).toContain(food.status);
    expect(food.history.at(-1)).toEqual({ month: res.body.month, value: 14357, goal: 20000 });
    // 1% of this month's income; General has 30.00 + 16.50 so far.
    expect(giving).toMatchObject({ kind: 'spend-at-least', name: 'Giving', current: 4650 });
    expect(giving.income).toBeGreaterThan(0);
    expect(giving.goal).toBe(Math.round(giving.income / 100));
    const accounts = (await app.inject({ method: 'GET', url: `/v1/budgets/${b}/accounts`, headers: { authorization: `Bearer ${viewer}` } })).json().items as any[];
    expect(saving).toMatchObject({ kind: 'account', name: accounts.find((a) => a.id === actual.accounts.checking).name, goal: 1_000_000, current: accounts.find((a) => a.id === actual.accounts.checking).balance });

    const moved = await call('PUT', `/targets/${limit.body.id}`, '/targets/{id}', owner, { kind: 'spend-under', categoryId: Food, amount: 10000 });
    expect(moved.body.amount).toBe(10000);
    expect(((await call('GET', '/targets', '/targets', viewer)).body.items as any[])[0].status).toBe('over');
    expect((await call('DELETE', `/targets/${limit.body.id}`, '/targets/{id}', owner)).status).toBe(204);
    expect((await call('GET', '/targets', '/targets', viewer)).body.items).toHaveLength(2);
    expect((await call('DELETE', `/targets/${limit.body.id}`, '/targets/{id}', owner)).status).toBe(404);
  });
});
