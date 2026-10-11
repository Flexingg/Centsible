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

// A category's history (for deciding its budget), and categories kept off the books,
// against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'category-tools');
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

const shift = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
const spend = (date: string, amount: number, payeeName: string, categoryId: string) =>
  call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: actual.accounts.checking, date, amount, payeeName, categoryId });

describe('category history', () => {
  it('shows each month and the 3, 6 and 12 month averages', async () => {
    const { Food } = actual.categories as Record<string, string>;
    const M = actual.month;
    await spend(`${shift(M, -3)}-10`, -15_000, 'History Grocer', Food!);
    await spend(`${shift(M, -2)}-10`, -45_000, 'History Grocer', Food!);
    await spend(`${shift(M, -1)}-10`, -30_000, 'History Grocer', Food!);
    const res = await call('GET', `/categories/${Food}/history?month=${M}`, '/categories/{id}/history', viewer);
    recordFixture('category-history', res.body);
    expect(res.body.months.at(-1).month).toBe(M);
    expect(res.body.months.slice(-4, -1).map((m: any) => m.spent)).toEqual([15_000, 45_000, 30_000]);
    // Three finished months of history: every average is over those three.
    expect(res.body.monthsOfHistory).toBe(3);
    expect(res.body.averages).toEqual({ avg3: 30_000, avg6: 30_000, avg12: 30_000 });
    expect(res.body).toMatchObject({ highest: 45_000, lowest: 15_000 });
    expect((await call('GET', '/categories/nope/history', '/categories/{id}/history', viewer)).status).toBe(404);
  });
});

describe('off the books', () => {
  it('keeps a holding category out of the review queue and spending reports', async () => {
    const M = actual.month;
    const holding = await host.withBudget(b, 'write', async () => {
      const group = ((await api.getCategoryGroups()) as { id: string; is_income?: boolean }[]).find((g) => !g.is_income)!;
      const id = await api.createCategory({ name: 'To match', group_id: group.id });
      await new Promise((r) => setTimeout(r, 0));
      return id as string;
    });
    const t = (await spend(`${M}-03`, -5_000, 'Mystery charge', holding)).body;
    const inbox = async () => ((await call('GET', '/review?limit=200', '/review', viewer)).body.items as any[]).map((i) => i.id);
    const spending = async () => ((await call('GET', `/reports/spending?start=${M}&end=${M}`, '/reports/spending', viewer)).body.categories as any[]).map((c) => c.categoryId);
    expect(await inbox()).toContain(t.id);
    expect(await spending()).toContain(holding);

    expect((await call('PUT', `/categories/${holding}/off-books`, '/categories/{id}/off-books', viewer, { offBooks: true })).status).toBe(403);
    const set = await call('PUT', `/categories/${holding}/off-books`, '/categories/{id}/off-books', owner, { offBooks: true });
    expect(set.body.categoryIds).toEqual([holding]);
    expect((await call('GET', '/off-books', '/off-books', viewer)).body.categoryIds).toEqual([holding]);
    expect(await inbox()).not.toContain(t.id);
    expect(await spending()).not.toContain(holding);
    // Rollover is on, so what's in it waits there instead of hitting next month's To Budget.
    const month = (await app.inject({ method: 'GET', url: `/v1/budgets/${b}/months/${M}`, headers: { authorization: `Bearer ${viewer}` } })).json();
    const cat = month.groups.flatMap((g: any) => g.categories).find((c: any) => c.id === holding);
    expect(cat.carryover).toBe(true);

    await call('PUT', `/categories/${holding}/off-books`, '/categories/{id}/off-books', owner, { offBooks: false });
    expect(await spending()).toContain(holding);
  });
});
