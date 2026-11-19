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

// Finding the same transaction twice in an account and merging it with Actual's own
// mergeTransactions, against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'duplicates');
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

const add = async (account: string, amount: number, daysAgo: number, payeeName: string, categoryId: string | null = null) =>
  (await call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: account, date: dayFromNow(-daysAgo), amount, payeeName, categoryId })).body;

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

/** A bank-imported transaction (as SimpleFIN, Teller or a file import would add it). */
async function imported(account: string, amount: number, daysAgo: number, payee: string, importedId: string) {
  await host.withBudget(b, 'write', async () => {
    await api.importTransactions(account, [{ date: dayFromNow(-daysAgo), amount, payee_name: payee, imported_payee: payee, imported_id: importedId, cleared: true } as never]);
  });
  const res = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/transactions?accountId=${account}&limit=200`, headers: { authorization: `Bearer ${owner}` } });
  return (JSON.parse(res.body).items as any[]).find((t) => t.amount === amount && t.date === dayFromNow(-daysAgo) && t.payeeName?.toUpperCase() === payee.toUpperCase())!;
}

const duplicates = async () => (await call('GET', '/transactions/duplicates', '/transactions/duplicates', viewer)).body.pairs as { a: any; b: any; days: number }[];
const pairWith = <P extends { a: any; b: any }>(pairs: P[], id: string) => pairs.find((p) => p.a.id === id || p.b.id === id);

describe('duplicates', () => {
  let typed: any;
  let synced: any;

  it("doesn't need to: Actual already matches a bank import to the same purchase typed in", async () => {
    typed = await add(actual.accounts.checking, -1234, 2, 'Coffee', actual.categories.General!);
    await imported(actual.accounts.checking, -1234, 1, 'COFFEE SHOP 123', 'sf-coffee-1');
    expect(pairWith(await duplicates(), typed.id)).toBeUndefined();
  });

  it('pairs the same purchase from SimpleFIN and from Teller', async () => {
    const sf = await imported(actual.accounts.checking, -6789, 3, 'HARDWARE STORE', 'sf-hw-1');
    synced = await imported(actual.accounts.checking, -6789, 2, 'The Hardware Store', 'teller:txn_hw');
    const pair = pairWith(await duplicates(), sf.id);
    expect(pair).toBeDefined();
    expect([pair!.a.id, pair!.b.id].sort()).toEqual([sf.id, synced.id].sort());
    expect(pair!.days).toBe(1);
    typed = sf;
    recordFixture('duplicates', { pairs: [pair] });
  });

  it('leaves alone two different purchases of the same amount from one bank, and anything from Teller', async () => {
    const x = await imported(actual.accounts.card, -999, 3, 'BOOKSTORE', 'sf-book');
    const y = await imported(actual.accounts.card, -999, 3, 'PHARMACY', 'sf-pharm');
    const t1 = await imported(actual.accounts.card, -777, 2, 'LUNCH SPOT', 'teller:txn_a');
    const t2 = await imported(actual.accounts.card, -777, 2, 'LUNCH SPOT', 'teller:txn_b');
    const pairs = await duplicates();
    for (const id of [x.id, y.id, t1.id, t2.id]) expect(pairWith(pairs, id)).toBeUndefined();
  });

  it('pairs the same purchase imported twice by a reconnected bank connection', async () => {
    const first = await imported(actual.accounts.card, -4321, 5, 'GAS STATION 77', 'sf-old-1');
    const again = await imported(actual.accounts.card, -4321, 4, 'GAS STATION 77', 'sf-new-9');
    const pair = pairWith(await duplicates(), first.id);
    expect(pair && [pair.a.id, pair.b.id].includes(again.id)).toBe(true);
  });

  it('merges the pair, keeping the category set on either', async () => {
    // Categorize the one that will be dropped: the category must survive the merge.
    const drop = synced;
    await app.inject({ method: 'PATCH', url: `/v1/budgets/${b}/transactions/${drop.id}`, headers: { authorization: `Bearer ${owner}` }, payload: { categoryId: actual.categories.General! } });
    expect((await call('POST', '/transactions/merge', '/transactions/merge', viewer, { ids: [typed.id, drop.id] })).status).toBe(403);
    const res = await call('POST', '/transactions/merge', '/transactions/merge', owner, { ids: [typed.id, drop.id] });
    expect(res.status).toBe(200);
    expect([typed.id, drop.id]).toContain(res.body.id);
    expect(res.body.categoryId).toBe(actual.categories.General);
    const gone = res.body.id === typed.id ? drop.id : typed.id;
    const left = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/transactions/${gone}`, headers: { authorization: `Bearer ${owner}` } });
    expect(left.statusCode).toBe(404);
    expect(pairWith(await duplicates(), res.body.id)).toBeUndefined();
    recordFixture('transaction-merged', res.body);
  });

  it("won't merge different amounts", async () => {
    const p = await add(actual.accounts.checking, -500, 1, 'Snack');
    const q = await add(actual.accounts.checking, -600, 1, 'Snack');
    const res = await call('POST', '/transactions/merge', '/transactions/merge', owner, { ids: [p.id, q.id] });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/different amounts/);
  });

  it('stops suggesting a pair marked as not a duplicate', async () => {
    const p = await add(actual.accounts.checking, -2500, 1, 'Parking');
    const q = await add(actual.accounts.checking, -2500, 0, 'Parking');
    expect(pairWith(await duplicates(), p.id)).toBeDefined();
    expect((await call('POST', '/transactions/duplicates/dismiss', '/transactions/duplicates/dismiss', owner, { ids: [q.id, p.id] })).status).toBe(204);
    expect(pairWith(await duplicates(), p.id)).toBeUndefined();
  });

  it('advertises merging only when Actual can do it', async () => {
    const res = await app.inject({ method: 'GET', url: '/v1/capabilities', headers: { authorization: `Bearer ${viewer}` } });
    expect(JSON.parse(res.body).features['transactions.merge']).toBe(true);
  });
});
