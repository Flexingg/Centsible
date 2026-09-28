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
import { buildServer } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Autopilot, goals and the forecast, against a real Actual server with the seeded
// household budget (this month) plus three earlier months of spending added here.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'plan');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let b: string;
let M: string;
const shift = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};
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

// A payee per category: Actual learns payee -> category rules, which would recategorize.
const spend = (month: string, amount: number, category: string, payeeName: string) =>
  call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: actual.accounts.checking, date: `${month}-10`, amount, payeeName, categoryId: category });

beforeAll(async () => {
  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  b = actual.budgetId;
  M = actual.month;
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
  app = await buildServer(createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent), { logger: false });
  owner = tokenFor('owner');
  viewer = tokenFor('viewer');
  const { Food, General } = actual.categories as Record<string, string>;
  await spend(shift(M, -1), -30000, Food!, 'Grocer');
  await spend(shift(M, -2), -45000, Food!, 'Grocer');
  await spend(shift(M, -3), -15000, Food!, 'Grocer');
  await spend(shift(M, -1), -1001, General!, 'Hardware'); // General is new last month
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

describe('autopilot', () => {
  it('suggests budgets from average spending, counting only months with activity', async () => {
    const res = await call('GET', `/months/${M}/autopilot`, '/months/{month}/autopilot', viewer);
    expect(res.status).toBe(200);
    const food = res.body.suggestions.find((s: any) => s.name === 'Food');
    expect(food).toMatchObject({ average: { avg3: 30000, avg6: 30000, avg12: 30000 }, suggested: { avg3: 30000 }, lastMonthSpent: 30000, monthsOfHistory: 3, budgeted: 60000 });
    const general = res.body.suggestions.find((s: any) => s.name === 'General');
    expect(general).toMatchObject({ average: { avg3: 1001 }, suggested: { avg3: 1100 }, monthsOfHistory: 1 }); // rounded up to whole units
    recordFixture('autopilot', res.body);
  });

  it('applies suggestions to the chosen categories', async () => {
    expect((await call('POST', `/months/${M}/autopilot`, '/months/{month}/autopilot', viewer, { basis: 3 })).status).toBe(403);
    const res = await call('POST', `/months/${M}/autopilot`, '/months/{month}/autopilot', owner, { basis: 3, categoryIds: [actual.categories.Food] });
    expect(res.body.changed).toBe(1);
    const food = res.body.month.groups.flatMap((g: any) => g.categories).find((c: any) => c.id === actual.categories.Food);
    expect(food.budgeted).toBe(30000);
    expect((await call('POST', `/months/${M}/autopilot`, '/months/{month}/autopilot', owner, { basis: 5 })).status).toBe(400);
  });

  it('plans and covers overspending from To Budget first', async () => {
    // General: 46.50 spent this month against 10.00 budgeted.
    await call('PATCH', `/months/${M}/categories/${actual.categories.General}`, '/months/{month}/categories/{categoryId}', owner, { budgeted: 1000 });
    const plan = (await call('GET', `/months/${M}/autopilot`, '/months/{month}/autopilot', viewer)).body;
    expect(plan.overspent).toEqual([{ categoryId: actual.categories.General, name: 'General', amount: 3650 }]);
    expect(plan.cover.moves).toEqual([{ from: 'to-budget', fromName: 'To Budget', to: actual.categories.General, toName: 'General', amount: 3650 }]);
    const res = await call('POST', `/months/${M}/cover-overspending`, '/months/{month}/cover-overspending', owner);
    expect(res.body).toMatchObject({ uncovered: 0, moves: plan.cover.moves });
    const general = res.body.month.groups.flatMap((g: any) => g.categories).find((c: any) => c.id === actual.categories.General);
    expect(general).toMatchObject({ budgeted: 4650, balance: 0 });
  });
});

describe('goals', () => {
  const bills = () => actual.categories.Bills!;

  it('writes a goal into the category notes, keeping what was there', async () => {
    await call('PUT', `/categories/${bills()}/note`, '/categories/{id}/note', owner, { note: 'Rent for the flat' });
    const target = shift(M, 5);
    expect((await call('PUT', `/categories/${bills()}/goal`, '/categories/{id}/goal', owner, { kind: 'by', target: 120000, targetMonth: target })).status).toBe(204);
    const note = (await call('GET', `/categories/${bills()}/note`, '/categories/{id}/note', viewer)).body.note;
    expect(note).toBe(`Rent for the flat\n#template 1200 by ${target}`);

    const goals = (await call('GET', '/goals', '/goals', viewer)).body;
    expect(goals.month).toBe(M);
    expect(goals.items).toHaveLength(1);
    expect(goals.items[0]).toMatchObject({ categoryId: bills(), kind: 'by', target: 120000, targetMonth: target, monthlyNeeded: 20000, remaining: 120000, progress: 0 });
    recordFixture('goals', goals);
  });

  it('replaces the goal and can remove it', async () => {
    await call('PUT', `/categories/${bills()}/goal`, '/categories/{id}/goal', owner, { kind: 'balance', target: 250050 });
    const item = (await call('GET', '/goals', '/goals', viewer)).body.items[0];
    expect(item).toMatchObject({ kind: 'balance', target: 250050, targetMonth: null, monthlyNeeded: null, line: '#goal 2500.50' });
    expect((await call('DELETE', `/categories/${bills()}/goal`, '/categories/{id}/goal', owner)).status).toBe(204);
    expect((await call('GET', `/categories/${bills()}/note`, '/categories/{id}/note', viewer)).body.note).toBe('Rent for the flat');
    expect((await call('GET', '/goals', '/goals', viewer)).body.items).toEqual([]);
  });

  it('rejects goals that make no sense', async () => {
    expect((await call('PUT', `/categories/${bills()}/goal`, '/categories/{id}/goal', owner, { kind: 'by', target: 5000, targetMonth: shift(M, -1) })).status).toBe(400);
    expect((await call('PUT', `/categories/${actual.categories.Income}/goal`, '/categories/{id}/goal', owner, { kind: 'balance', target: 5000 })).status).toBe(400);
    expect((await call('PUT', `/categories/${bills()}/goal`, '/categories/{id}/goal', viewer, { kind: 'balance', target: 5000 })).status).toBe(403);
  });
});

describe('forecast', () => {
  let startingBalance: number;

  beforeAll(async () => {
    const accounts = (await call('GET', '/accounts', '/accounts', viewer)).body.items as any[];
    startingBalance = accounts.filter((a) => !a.offBudget && !a.closed).reduce((s, a) => s + a.balance, 0);
    await call('POST', '/schedules', '/schedules', owner, {
      name: 'Gym', payeeName: 'Gym', accountId: actual.accounts.checking, amount: -5000, amountOp: 'is',
      recurrence: { frequency: 'monthly', interval: 1, start: dayFromNow(3) },
    });
  });

  it('projects balances from scheduled bills', async () => {
    const res = await call('GET', '/forecast?days=60&includeTypical=false', '/forecast', viewer);
    expect(res.status).toBe(200);
    const f = res.body;
    expect(f.startingBalance).toBe(startingBalance);
    expect(f.days).toHaveLength(61);
    const gym = f.events.filter((e: any) => e.name === 'Gym');
    expect(gym.map((e: any) => e.date)).toEqual([dayFromNow(3), ...(gym.length > 1 ? [gym[1].date] : [])]);
    expect(gym.length).toBeGreaterThanOrEqual(2); // two months fit in 60 days from 3 days out
    expect(f.days.at(-1).balance).toBe(startingBalance - 5000 * gym.length);
    expect(f.lowest).toEqual({ date: gym.at(-1).date, balance: startingBalance - 5000 * gym.length });
    recordFixture('forecast', f);
  });

  it('adds everyday money that isn\'t scheduled', async () => {
    const f = (await call('GET', '/forecast?days=30', '/forecast', viewer)).body;
    expect(Number.isInteger(f.typicalDaily)).toBe(true);
    expect(f.typicalDaily).not.toBe(0);
    expect(f.days[0].typical).toBe(0);
    expect(f.days[1].typical).toBe(f.typicalDaily);
  });

  it('follows the chosen accounts', async () => {
    const f = (await call('GET', `/forecast?days=30&accountIds=${actual.accounts.card}`, '/forecast', viewer)).body;
    expect(f.accountIds).toEqual([actual.accounts.card]);
    expect(f.events).toEqual([]);
    expect((await call('GET', '/forecast?accountIds=nope', '/forecast', viewer)).status).toBe(400);
  });
});
