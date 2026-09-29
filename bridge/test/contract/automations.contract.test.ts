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
import { buildServer } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Budget automations (programmable budgets) against a real Actual server: the seeded
// household budget plus three earlier months of Food spending.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'automations');
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
  const { Food } = actual.categories as Record<string, string>;
  await spend(shift(M, -1), -30000, Food!, 'Grocer');
  await spend(shift(M, -2), -45000, Food!, 'Grocer');
  await spend(shift(M, -3), -15000, Food!, 'Grocer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

const auto = (id: string) => `/categories/${id}/automations`;
const AUTO = '/categories/{categoryId}/automations';

describe('automations', () => {
  it('reads #template notes as automations that follow the notes', async () => {
    await host.withBudget(b, 'write', async () => {
      await api.updateNote(actual.categories.Bills!, 'Rent and utilities\n#template 1850 up to 2000');
    });
    const res = await call('GET', `${auto(actual.categories.Bills!)}?month=${M}`, AUTO, viewer);
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      source: 'notes',
      notesHaveTemplates: true,
      automations: [{ type: 'simple', priority: 0, monthly: 185000, limit: { amount: 200000, hold: false, period: 'monthly', start: null }, description: 'Rent and utilities' }],
      month: M,
    });
    expect(res.body.projected).toBe(185000);
    expect(res.body.perAutomation).toEqual([185000]);
  });

  it('saves every kind of automation and reads it back the same', async () => {
    // One cap per category, so the kinds are spread over a few categories.
    const food = [
      { type: 'average', priority: 1, months: 3, adjustment: { kind: 'percent', value: 10 } },
      { type: 'refill', priority: 2, limit: { amount: 60000, hold: true, period: 'monthly', start: null } },
      { type: 'goal', amount: 100000 },
    ];
    const general = [
      { type: 'periodic', priority: 0, amount: 2500, every: { unit: 'week', count: 1 }, starting: `${M}-01`, limit: null },
      { type: 'copy', priority: 3, monthsAgo: 12 },
      { type: 'percentage', priority: 4, percent: 5, of: 'all income', previous: true },
      { type: 'remainder', weight: 2, limit: { amount: 5000, hold: false, period: 'weekly', start: `${M}-01` } },
    ];
    const bills = [
      { type: 'by', priority: 0, amount: 120000, month: shift(M, 6), repeat: { unit: 'year', count: 1 }, spendFrom: null },
      { type: 'by', priority: 0, amount: 30000, month: shift(M, 3), repeat: null, spendFrom: shift(M, 1) },
      { type: 'simple', priority: 5, monthly: 1000, limit: null, description: 'Buffer' },
    ];
    for (const [id, list] of [[actual.categories.Food!, food], [actual.categories.General!, general], [actual.categories.Bills!, bills]] as const) {
      const put = await call('PUT', auto(id), AUTO, owner, { automations: list });
      expect(put.status).toBe(200);
      expect(put.body.source).toBe('ui');
      expect(put.body.automations).toEqual(list);
      const got = await call('GET', auto(id), AUTO, viewer);
      expect(got.body.automations).toEqual(list);
    }
    recordFixture('category-automations', (await call('GET', `${auto(actual.categories.Food!)}?month=${M}`, AUTO, viewer)).body);
  });

  it('lists every category with what it would budget', async () => {
    const res = await call('GET', `/automations?month=${M}`, '/automations', viewer);
    const food = res.body.categories.find((c: any) => c.categoryId === actual.categories.Food);
    expect(food).toMatchObject({ name: 'Food', source: 'ui', isIncome: false });
    // Average of 300, 450, 150 = 300, plus 10% = 330; the refill to 600 then fills the rest.
    expect(food.projected).toBeGreaterThanOrEqual(33000);
    const income = res.body.categories.find((c: any) => c.isIncome);
    expect(income.source).toBe('none');
    recordFixture('automations', res.body);
  });

  it('previews without saving', async () => {
    const res = await call('POST', `${auto(actual.categories.General!)}/preview`, `${AUTO}/preview`, viewer, {
      month: M,
      automations: [{ type: 'simple', priority: 0, monthly: 4200, limit: null }, { type: 'simple', priority: 1, monthly: 800, limit: null }],
    });
    expect(res.body).toEqual({ month: M, projected: 5000, perAutomation: [4200, 800] });
    expect((await call('GET', auto(actual.categories.General!), AUTO, viewer)).body.automations[0].type).toBe('periodic');
  });

  it('explains what it won\'t accept', async () => {
    const twoCaps = [
      { type: 'refill', priority: 0, limit: { amount: 100, hold: false, period: 'monthly', start: null } },
      { type: 'simple', priority: 0, monthly: 100, limit: { amount: 200, hold: false, period: 'monthly', start: null } },
    ];
    const bad = await call('PUT', auto(actual.categories.General!), AUTO, owner, { automations: twoCaps });
    expect(bad.status).toBe(400);
    expect(bad.body.detail).toMatch(/one cap/i);
    const income = actual.categories.Income!;
    expect((await call('PUT', auto(income), AUTO, owner, { automations: [{ type: 'simple', priority: 0, monthly: 100, limit: null }] })).status).toBe(400);
    expect((await call('PUT', auto(actual.categories.Food!), AUTO, viewer, { automations: [] })).status).toBe(403);
  });

  it('applies to one category, overwriting its budget', async () => {
    await call('PUT', auto(actual.categories.General!), AUTO, owner, { automations: [{ type: 'simple', priority: 0, monthly: 7700, limit: null }] });
    const res = await call('POST', '/automations/apply', '/automations/apply', owner, { month: M, categoryIds: [actual.categories.General] });
    expect(res.status).toBe(200);
    expect(res.body.ok).toBe(true);
    const month = (await call('GET', `/months/${M}`, '/months/{month}', viewer)).body;
    expect(month.groups.flatMap((g: any) => g.categories).find((c: any) => c.id === actual.categories.General).budgeted).toBe(7700);
  });

  it('keeps goals in the automations of automated categories', async () => {
    const food = actual.categories.Food!;
    let goals = (await call('GET', '/goals', '/goals', viewer)).body;
    expect(goals.items.find((g: any) => g.categoryId === food)).toMatchObject({ kind: 'balance', target: 100000 });
    const target = shift(M, 8);
    expect((await call('PUT', `/categories/${food}/goal`, '/categories/{id}/goal', owner, { kind: 'by', target: 90000, targetMonth: target })).status).toBe(204);
    const after = (await call('GET', auto(food), AUTO, viewer)).body;
    // The goal was swapped; the other automations are untouched.
    expect(after.source).toBe('ui');
    expect(after.automations.map((a: any) => a.type)).toEqual(['average', 'refill', 'by']);
    expect(after.automations[2]).toMatchObject({ amount: 90000, month: target });
    goals = (await call('GET', '/goals', '/goals', viewer)).body;
    expect(goals.items.find((g: any) => g.categoryId === food)).toMatchObject({ kind: 'by', target: 90000, targetMonth: target });
  });

  it('goes back to the notes', async () => {
    const res = await call('DELETE', auto(actual.categories.Bills!), AUTO, owner);
    expect(res.body.source).toBe('notes');
    expect(res.body.automations[0]).toMatchObject({ type: 'simple', monthly: 185000 });
  });
});
