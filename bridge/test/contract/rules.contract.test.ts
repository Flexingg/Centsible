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

// Rule previews (with formulas), running rules on existing transactions, and running
// every rule again, against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'rules');
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

const payeeId = async (name: string) => {
  const res = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/payees`, headers: { authorization: `Bearer ${viewer}` } });
  return (res.json().items as { id: string; name: string }[]).find((p) => p.name === name)!.id;
};

describe('rule preview', () => {
  it('shows matches and what each action would do, formulas included', async () => {
    const costco = await payeeId('Costco');
    const res = await call('POST', '/rules/preview', '/rules/preview', viewer, {
      conditionsOp: 'and',
      conditions: [{ field: 'payee', op: 'is', value: costco, type: 'id' }],
      actions: [
        { field: 'category', op: 'set', value: actual.categories.Food, type: 'id' },
        { field: 'notes', op: 'set', value: '', type: 'string', options: { formula: '=UPPER(payee_name) & " at " & account_name' } },
        { field: 'amount', op: 'set', value: 0, type: 'number', options: { formula: '=amount/100*2' } },
      ],
      limit: 5,
    });
    expect(res.status).toBe(200);
    expect(res.body.matchCount).toBeGreaterThanOrEqual(1);
    const first = res.body.items[0];
    expect(first.transaction.payeeName).toBe('Costco');
    expect(first.changes).toEqual([
      { field: 'category', value: 'Food' },
      { field: 'notes', value: 'COSTCO at Joint Checking' },
      { field: 'amount', value: first.transaction.amount * 2 },
    ]);
    recordFixture('rule-preview', res.body);
  });

  it('explains a broken formula and budget-data formulas', async () => {
    const res = await call('POST', '/rules/preview', '/rules/preview', viewer, {
      conditions: [{ field: 'amount', op: 'lt', value: 0, type: 'number' }],
      actions: [
        { field: 'notes', op: 'set', value: '', options: { formula: '=NOPE(' } },
        { field: 'notes', op: 'set', value: '', options: { formula: '=BALANCE_OF("Visa")' } },
      ],
      limit: 1,
    });
    const [broken, needsActual] = res.body.items[0].changes;
    expect(broken.error).toBeTruthy();
    expect(needsActual.note).toMatch(/when the rule runs/);
  });
});

describe('running rules', () => {
  it('runs a saved rule on the transactions it matches', async () => {
    const t = await spend(M, -4321, actual.categories.General!, 'Corner Hardware');
    const rule = (await call('POST', '/rules', '/rules', owner, {
      conditionsOp: 'and',
      conditions: [{ field: 'imported_payee', op: 'contains', value: 'nothing-imported', type: 'string' }, { field: 'payee', op: 'is', value: t.body.payeeId, type: 'id' }],
      actions: [{ field: 'category', op: 'set', value: actual.categories.Bills, type: 'id' }],
    })).body;
    // imported_payee is empty, so nothing matches yet: the rule is only about this payee once we drop the first condition.
    const updated = (await call('PUT', `/rules/${rule.id}`, '/rules/{id}', owner, {
      conditionsOp: 'and',
      conditions: [{ field: 'payee', op: 'is', value: t.body.payeeId, type: 'id' }],
      actions: [{ field: 'category', op: 'set', value: actual.categories.Bills, type: 'id' }],
    })).body;
    expect((await call('POST', `/rules/${updated.id}/run`, '/rules/{id}/run', viewer, {})).status).toBe(403);
    const res = await call('POST', `/rules/${updated.id}/run`, '/rules/{id}/run', owner, {});
    expect(res.body).toEqual({ updated: 1 });
    const after = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/transactions/${t.body.id}`, headers: { authorization: `Bearer ${viewer}` } });
    expect(after.json().categoryId).toBe(actual.categories.Bills);
  });

  it('runs every rule again on chosen transactions, formulas included', async () => {
    const t = await spend(M, -1500, actual.categories.General!, 'Formula Cafe');
    await call('POST', '/rules', '/rules', owner, {
      conditionsOp: 'and',
      conditions: [{ field: 'payee', op: 'is', value: t.body.payeeId, type: 'id' }],
      actions: [{ field: 'notes', op: 'set', value: '', type: 'string', options: { formula: '=UPPER(payee_name)' } }],
    });
    const res = await call('POST', '/rules/rerun', '/rules/rerun', owner, { transactionIds: [t.body.id] });
    expect(res.body).toEqual({ checked: 1, changed: 1 });
    const after = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/transactions/${t.body.id}`, headers: { authorization: `Bearer ${viewer}` } });
    expect(after.json().notes).toBe('FORMULA CAFE');
  });
});
