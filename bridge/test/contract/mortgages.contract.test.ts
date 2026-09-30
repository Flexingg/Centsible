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

// A mortgage: its amortization, the loan and home as off-budget accounts (so net worth has
// them), recording principal from payments, and the home's value, against a real Actual server.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'mortgages');
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

const monthStart = (back: number) => {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - back, 1)).toISOString().slice(0, 10);
};
const accounts = async () => (await app.inject({ method: 'GET', url: `/v1/budgets/${b}/accounts`, headers: { authorization: `Bearer ${viewer}` } })).json().items as any[];

describe('mortgages', () => {
  it('tracks a mortgage, its principal and the home, and net worth sees both', async () => {
    const { Bills } = actual.categories as Record<string, string>;
    const pay = async (date: string) =>
      (await call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: actual.accounts.checking, date, amount: -209_865, payeeName: 'Lakeside Mortgage', categoryId: Bills })).body;
    const first = await pay(monthStart(3));
    await pay(monthStart(2));
    await pay(monthStart(1));

    const input = {
      name: 'House mortgage', principal: 30_000_000, rate: 6, termMonths: 360, firstPayment: monthStart(3), escrow: 30_000,
      payeeId: first.payeeId, paymentAccountId: actual.accounts.checking, createLoanAccount: true, homeValue: 42_000_000,
    };
    expect((await call('POST', '/mortgages', '/mortgages', viewer, input)).status).toBe(403);
    expect((await call('POST', '/mortgages', '/mortgages', owner, { ...input, rate: 70 })).status).toBe(400);
    const created = await call('POST', '/mortgages', '/mortgages', owner, input);
    expect(created.status).toBe(201);
    const m = created.body;
    expect(m).toMatchObject({ monthlyPayment: 179_865, monthlyTotal: 209_865, homeValue: 42_000_000, loanSynced: false, unrecorded: 0, paymentsFound: 3 });
    expect(m.balance).toBe(m.scheduledBalance);
    expect(m.equity).toBe(42_000_000 - m.balance);
    expect(m.payoffDate > m.firstPayment).toBe(true);

    // Both accounts are in Actual, off budget: net worth counts the home and the loan.
    const all = await accounts();
    expect(all.find((a) => a.id === m.loanAccountId)).toMatchObject({ offBudget: true, balance: -m.balance });
    expect(all.find((a) => a.id === m.homeAccountId)).toMatchObject({ offBudget: true, balance: 42_000_000 });

    // A new payment: its principal comes off the loan account.
    await pay(new Date().toISOString().slice(0, 10));
    expect((await call('GET', '/mortgages', '/mortgages', viewer)).body.items[0].unrecorded).toBe(1);
    const rec = await call('POST', `/mortgages/${m.id}/record-principal`, '/mortgages/{id}/record-principal', owner);
    expect(rec.body.recorded).toBe(1);
    expect(rec.body.principal).toBeGreaterThan(29_000);
    const after = await call('GET', `/mortgages/${m.id}`, '/mortgages/{id}', viewer);
    recordFixture('mortgage', after.body);
    expect(after.body.balance).toBe(m.balance - rec.body.principal);
    expect(after.body.unrecorded).toBe(0);
    expect(after.body.schedule).toHaveLength(360);
    expect(after.body.schedule[0]).toMatchObject({ n: 1, interest: 150_000, principal: 29_865, paidOn: monthStart(3) });

    const valued = await call('PUT', `/mortgages/${m.id}/home-value`, '/mortgages/{id}/home-value', owner, { value: 45_000_000 });
    expect(valued.body.homeValue).toBe(45_000_000);
    expect((await accounts()).find((a) => a.id === m.homeAccountId).balance).toBe(45_000_000);

    expect((await call('DELETE', `/mortgages/${m.id}`, '/mortgages/{id}', owner)).status).toBe(204);
    // The accounts stay.
    expect((await accounts()).some((a) => a.id === m.loanAccountId)).toBe(true);
  });
});
