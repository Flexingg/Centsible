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

// Matching the two sides of a payment (a credit card payment from checking) and linking
// them as one transfer, against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'transfers');
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

const matches = async () => (await call('GET', '/transfers/matches', '/transfers/matches', viewer)).body as { auto: boolean; pairs: { from: any; to: any; days: number; confident: boolean }[] };
const pairOf = <P extends { from: any }>(pairs: P[], fromId: string) => pairs.find((p) => p.from.id === fromId);

describe('transfer matching', () => {
  it('finds a card payment from both sides and links it into one transfer', async () => {
    const paid = await add(actual.accounts.checking, -123456, 3, 'CARD AUTOPAY', actual.categories.General!);
    const received = await add(actual.accounts.card, 123456, 1, 'PAYMENT THANK YOU');
    // Same amount, same direction: not a transfer.
    await add(actual.accounts.card, -123456, 2, 'Big purchase', actual.categories.General!);

    const found = await matches();
    recordFixture('transfer-matches', found);
    expect(found.auto).toBe(false);
    const pair = pairOf(found.pairs, paid.id)!;
    expect(pair.to.id).toBe(received.id);
    expect(pair.days).toBe(2);
    expect(pair.confident).toBe(true);

    expect((await call('POST', '/transfers/link', '/transfers/link', viewer, { fromId: paid.id, toId: received.id })).status).toBe(403);
    const linked = await call('POST', '/transfers/link', '/transfers/link', owner, { fromId: paid.id, toId: received.id });
    expect(linked.status).toBe(200);
    expect(linked.body.from.transferId).toBe(received.id);
    expect(linked.body.to.transferId).toBe(paid.id);
    // A transfer between two budget accounts isn't spending: no category, and each keeps its date.
    expect(linked.body.from.categoryId).toBeNull();
    expect(linked.body.from.date).toBe(dayFromNow(-3));
    expect(linked.body.to.date).toBe(dayFromNow(-1));
    expect(pairOf((await matches()).pairs, paid.id)).toBeUndefined();

    const again = await call('POST', '/transfers/link', '/transfers/link', owner, { fromId: paid.id, toId: received.id });
    expect(again.status).toBe(400);
  });

  it('stops suggesting a dismissed pair', async () => {
    const out = await add(actual.accounts.checking, -7777, 4, 'Venmo');
    const inn = await add(actual.accounts.card, 7777, 4, 'Refund');
    expect(pairOf((await matches()).pairs, out.id)).toBeDefined();
    expect((await call('POST', '/transfers/dismiss', '/transfers/dismiss', owner, { fromId: out.id, toId: inn.id })).status).toBe(204);
    expect(pairOf((await matches()).pairs, out.id)).toBeUndefined();
  });

  it('links the confident pairs on its own after a sync, when turned on', async () => {
    const out = await add(actual.accounts.checking, -24680, 2, 'CARD PMT');
    const inn = await add(actual.accounts.card, 24680, 0, 'PAYMENT');
    // Two candidates for one payment: left for a person to choose.
    const outA = await add(actual.accounts.checking, -1350, 1, 'Transfer A');
    await add(actual.accounts.card, 1350, 1, 'Credit 1');
    await add(actual.accounts.card, 1350, 2, 'Credit 2');

    expect((await call('PUT', '/transfers/settings', '/transfers/settings', viewer, { auto: true })).status).toBe(403);
    expect((await call('PUT', '/transfers/settings', '/transfers/settings', owner, { auto: true })).body).toEqual({ auto: true });
    const n = await host.withBudget(b, 'write', (lib) => deps.transfers.autoLink(b, lib));
    expect(n).toBe(1);
    const after = await matches();
    expect(after.auto).toBe(true);
    expect(pairOf(after.pairs, out.id)).toBeUndefined();
    const t = await app.inject({ method: 'GET', url: `/v1/budgets/${b}/transactions/${inn.id}`, headers: { authorization: `Bearer ${viewer}` } });
    expect(t.json().transferId).toBe(out.id);
    expect(pairOf(after.pairs, outA.id)?.confident).toBe(false);
  });
});
