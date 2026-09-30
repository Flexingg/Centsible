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

// Yearly budgets: a twelfth a month, carried when unspent, a bill covered from the rest of
// the year, against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'annual');
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

describe('yearly budgets', () => {
  it('budgets a twelfth, then covers a yearly bill from the rest of the year', async () => {
    const month = actual.month;
    const categoryId = await host.withBudget(b, 'write', async () => {
      const group = ((await api.getCategoryGroups()) as { id: string; is_income?: boolean }[]).find((g) => !g.is_income)!;
      const id = await api.createCategory({ name: 'Car insurance', group_id: group.id });
      await new Promise((r) => setTimeout(r, 0));
      return id as string;
    });
    const thisMonth = Number(month.slice(5));
    expect((await call('PUT', `/categories/${categoryId}/annual-budget`, '/categories/{id}/annual-budget', viewer, { amount: 120_000 })).status).toBe(403);
    const set = await call('PUT', `/categories/${categoryId}/annual-budget`, '/categories/{id}/annual-budget', owner, { amount: 120_000, startMonth: thisMonth });
    expect(set.body).toMatchObject({ amount: 120_000, monthIndex: 0, budgeted: 10_000, suggested: 10_000, remaining: 120_000 });

    // The yearly bill arrives: this month grows to cover it from the rest of the year.
    await call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: actual.accounts.checking, date: `${month}-02`, amount: -120_000, payeeName: 'Insurer', categoryId });
    const applied = await call('POST', `/months/${month}/annual-budgets/apply`, '/months/{month}/annual-budgets/apply', owner);
    expect(applied.body.changes).toEqual([{ categoryId, from: 10_000, to: 120_000 }]);
    const list = await call('GET', '/annual-budgets', '/annual-budgets', viewer);
    recordFixture('annual-budgets', list.body);
    expect(list.body.items[0]).toMatchObject({ categoryId, name: 'Car insurance', budgeted: 120_000, suggested: 120_000, spentThisMonth: 120_000 });

    // The rest of the year: nothing left to budget.
    const [y, m] = month.split('-').map(Number) as [number, number];
    const next = new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 7);
    const later = await call('GET', `/annual-budgets?month=${next}`, '/annual-budgets', viewer);
    if (later.body.items.length) expect(later.body.items[0]).toMatchObject({ monthIndex: 1, remaining: 0, suggested: 0 });

    expect((await call('DELETE', `/categories/${categoryId}/annual-budget`, '/categories/{id}/annual-budget', owner)).status).toBe(204);
    expect((await call('GET', '/annual-budgets', '/annual-budgets', viewer)).body.items).toEqual([]);
  });
});
