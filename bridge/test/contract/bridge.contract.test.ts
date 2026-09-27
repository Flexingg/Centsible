import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { BudgetOps } from '../../src/actual/budget-ops.js';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { buildServer } from '../../src/http/server.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..', '.test-data', 'contract');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let app: FastifyInstance;
let config: BridgeConfig;
let owner: { accessToken: string; refreshToken: string };
let budgetId: string;

type Call = { method: string; url: string; path: string; token?: string; body?: unknown; headers?: Record<string, string> };

/** Calls the bridge and asserts the response against contract/openapi.yaml. */
async function call({ method, url, path, token, body, headers }: Call) {
  const res = await app.inject({
    method: method as 'GET',
    url,
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    ...(body !== undefined ? { payload: body as object } : {}),
  });
  const json = expectContract(method, path, res);
  return { status: res.statusCode, body: json, headers: res.headers };
}

async function pair(memberId: string, deviceName: string) {
  const { code } = store.createPairingCode(memberId, null);
  const res = await call({
    method: 'POST',
    url: '/v1/auth/pair',
    path: '/v1/auth/pair',
    body: { code, deviceName, platform: 'android' },
  });
  expect(res.status).toBe(200);
  return res.body as { accessToken: string; refreshToken: string; device: { id: string } };
}

beforeAll(async () => {
  const port = 5100 + Math.floor(Math.random() * 800);
  actual = await startSeededActual(join(root, 'actual'), port);
  budgetId = actual.budgetId;

  const bridgeData = join(root, 'bridge');
  rmSync(bridgeData, { recursive: true, force: true });
  mkdirSync(join(bridgeData, 'actual'), { recursive: true });
  config = {
    port: 0,
    host: '127.0.0.1',
    dataDir: bridgeData,
    publicUrl: 'https://budget-api.example.com',
    trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    cfAccess: { clientId: 'cf-id.access', clientSecret: 'cf-secret' },
    syncMaxAgeMs: 0, // always sync before reads so external changes show up immediately
    accessTokenTtlSec: 3600,
    refreshTokenTtlSec: 3600,
    logLevel: 'silent',
  };
  store = new HouseholdStore(join(bridgeData, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  app = await buildServer({ config, store, host, ops: new BudgetOps(host) }, { logger: false });

  const jo = store.createMember({ displayName: 'Jo', role: 'owner' });
  owner = await pair(jo.id, 'Pixel 9');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

describe('system', () => {
  it('health is public', async () => {
    const res = await call({ method: 'GET', url: '/v1/health', path: '/v1/health' });
    expect(res.body).toMatchObject({ status: 'ok' });
  });

  it('rejects missing tokens with a problem document', async () => {
    const res = await call({ method: 'GET', url: '/v1/capabilities', path: '/v1/capabilities' });
    expect(res.status).toBe(401);
    expect(res.body).toMatchObject({ code: 'unauthorized' });
    recordFixture('problem-unauthorized', res.body);
  });

  it('reports capabilities and a compatible Actual version', async () => {
    const res = await call({ method: 'GET', url: '/v1/capabilities', path: '/v1/capabilities', token: owner.accessToken });
    const caps = res.body as { status: string; actual: { compatibility: string; serverVersion: string }; features: Record<string, boolean> };
    expect(caps.status).toBe('ok');
    expect(caps.actual.compatibility).toBe('ok');
    expect(caps.features['budget.envelope']).toBe(true);
    expect(caps.features['budget.moveMoney']).toBe(true);
    expect(res.headers['cache-control']).toBe('no-store');
    recordFixture('capabilities', caps);
  });
});

describe('household', () => {
  let sam: { accessToken: string };
  let samId: string;

  it('owner adds a member and issues a pairing code with the Cloudflare Access token', async () => {
    const created = await call({
      method: 'POST',
      url: '/v1/household/members',
      path: '/v1/household/members',
      token: owner.accessToken,
      body: { displayName: 'Sam', role: 'member' },
    });
    expect(created.status).toBe(201);
    samId = (created.body as { id: string }).id;

    const code = await call({
      method: 'POST',
      url: `/v1/household/members/${samId}/pairing-codes`,
      path: '/v1/household/members/{memberId}/pairing-codes',
      token: owner.accessToken,
    });
    const uri = new URL((code.body as { pairingUri: string }).pairingUri);
    expect(uri.protocol).toBe('actualbridge:');
    expect(uri.searchParams.get('u')).toBe(config.publicUrl);
    expect(uri.searchParams.get('cfid')).toBe('cf-id.access');
    recordFixture('pairing-code', code.body);

    const paired = await call({
      method: 'POST',
      url: '/v1/auth/pair',
      path: '/v1/auth/pair',
      body: { code: (code.body as { code: string }).code.toLowerCase(), deviceName: "Sam's phone", platform: 'android' },
    });
    expect(paired.status).toBe(200);
    sam = paired.body as { accessToken: string };
    recordFixture('token-response', paired.body);
  });

  it('pairing codes are single use', async () => {
    const { code } = store.createPairingCode(samId, null);
    const body = { code, deviceName: 'tablet', platform: 'android' };
    expect((await call({ method: 'POST', url: '/v1/auth/pair', path: '/v1/auth/pair', body })).status).toBe(200);
    expect((await call({ method: 'POST', url: '/v1/auth/pair', path: '/v1/auth/pair', body })).status).toBe(401);
  });

  it('members only see budgets they were granted', async () => {
    const before = await call({ method: 'GET', url: '/v1/budgets', path: '/v1/budgets', token: sam.accessToken });
    expect((before.body as { items: unknown[] }).items).toHaveLength(0);
    const denied = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/accounts`,
      path: '/v1/budgets/{budgetId}/accounts',
      token: sam.accessToken,
    });
    expect(denied.status).toBe(403);

    await call({
      method: 'PUT',
      url: `/v1/household/members/${samId}/budgets`,
      path: '/v1/household/members/{memberId}/budgets',
      token: owner.accessToken,
      body: { budgetIds: [budgetId] },
    });
    const after = await call({ method: 'GET', url: '/v1/budgets', path: '/v1/budgets', token: sam.accessToken });
    expect((after.body as { items: { id: string }[] }).items.map((b) => b.id)).toEqual([budgetId]);
  });

  it('non-owners cannot manage the household', async () => {
    const res = await call({ method: 'GET', url: '/v1/household/members', path: '/v1/household/members', token: sam.accessToken });
    expect(res.status).toBe(403);
  });

  it('viewers can read but not write', async () => {
    const kid = store.createMember({ displayName: 'Kid', role: 'viewer', budgetIds: [budgetId] });
    const viewer = await pair(kid.id, 'iPad');
    const read = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/months/${actual.month}`,
      path: '/v1/budgets/{budgetId}/months/{month}',
      token: viewer.accessToken,
    });
    expect(read.status).toBe(200);
    const write = await call({
      method: 'POST',
      url: `/v1/budgets/${budgetId}/transactions`,
      path: '/v1/budgets/{budgetId}/transactions',
      token: viewer.accessToken,
      body: { id: randomUUID(), accountId: actual.accounts.checking, date: `${actual.month}-10`, amount: -100 },
    });
    expect(write.status).toBe(403);
  });
});

describe('budget data', () => {
  const tx = () => `/v1/budgets/${budgetId}/transactions`;
  const monthUrl = () => `/v1/budgets/${budgetId}/months/${actual.month}`;

  it('lists budgets', async () => {
    const res = await call({ method: 'GET', url: '/v1/budgets', path: '/v1/budgets', token: owner.accessToken });
    expect(res.body).toEqual({ items: [{ id: budgetId, name: 'Household', encrypted: false }] });
    recordFixture('budgets', res.body);
  });

  it('lists accounts with balances', async () => {
    const res = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/accounts`,
      path: '/v1/budgets/{budgetId}/accounts',
      token: owner.accessToken,
    });
    const byName = Object.fromEntries((res.body as { items: { name: string }[] }).items.map((a) => [a.name, a]));
    expect(byName['Joint Checking']).toMatchObject({ balance: 520000 + 610000 - 185000 - 12834, offBudget: false });
    expect(byName['Visa']).toMatchObject({ balance: -4523 - 1650 });
    expect(byName['Brokerage']).toMatchObject({ balance: 1250000, offBudget: true });
    recordFixture('accounts', res.body);
  });

  it('lists category groups and payees', async () => {
    const groups = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/category-groups`,
      path: '/v1/budgets/{budgetId}/category-groups',
      token: owner.accessToken,
    });
    expect((groups.body as { items: { isIncome: boolean }[] }).items.some((g) => g.isIncome)).toBe(true);
    recordFixture('category-groups', groups.body);

    const payees = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/payees`,
      path: '/v1/budgets/{budgetId}/payees',
      token: owner.accessToken,
    });
    const names = (payees.body as { items: { name: string }[] }).items.map((p) => p.name);
    expect(names).toEqual(expect.arrayContaining(['Costco', "Trader Joe's"]));
  });

  it('pages transactions newest first with splits grouped', async () => {
    const all: { payeeName: string; subtransactions: unknown[]; isParent: boolean }[] = [];
    let cursor: string | null = null;
    let pages = 0;
    do {
      const q: string = `?limit=2${cursor ? `&cursor=${cursor}` : ''}`;
      const res = await call({ method: 'GET', url: tx() + q, path: '/v1/budgets/{budgetId}/transactions', token: owner.accessToken });
      const page = res.body as { items: typeof all; nextCursor: string | null };
      if (pages === 0) recordFixture('transactions-page', page);
      all.push(...page.items);
      cursor = page.nextCursor;
      pages++;
    } while (cursor && pages < 10);

    expect(pages).toBeGreaterThan(1);
    const costco = all.find((t) => t.payeeName === 'Costco')!;
    expect(costco.isParent).toBe(true);
    expect(costco.subtransactions).toHaveLength(2);
  });

  it('filters transactions by account', async () => {
    const res = await call({
      method: 'GET',
      url: `${tx()}?accountId=${actual.accounts.card}`,
      path: '/v1/budgets/{budgetId}/transactions',
      token: owner.accessToken,
    });
    const items = (res.body as { items: { accountId: string }[] }).items;
    expect(items.length).toBeGreaterThan(0);
    expect(items.every((t) => t.accountId === actual.accounts.card)).toBe(true);
  });

  it('creates transactions idempotently using the client id', async () => {
    const body = {
      id: randomUUID(),
      accountId: actual.accounts.card,
      date: `${actual.month}-12`,
      amount: -2599,
      payeeName: 'Netflix',
      categoryId: actual.categories['Bills'],
      notes: 'subscription',
    };
    const first = await call({ method: 'POST', url: tx(), path: '/v1/budgets/{budgetId}/transactions', token: owner.accessToken, body });
    expect(first.status).toBe(201);
    expect(first.body).toMatchObject({ id: body.id, amount: -2599, payeeName: 'Netflix', categoryId: body.categoryId });
    recordFixture('transaction', first.body);

    const replay = await call({ method: 'POST', url: tx(), path: '/v1/budgets/{budgetId}/transactions', token: owner.accessToken, body });
    expect(replay.status).toBe(200);
    expect(replay.body).toEqual(first.body);
  });

  it('creates split transactions and rejects splits that do not add up', async () => {
    const good = {
      id: randomUUID(),
      accountId: actual.accounts.checking,
      date: `${actual.month}-13`,
      amount: -5000,
      payeeName: 'Target',
      subtransactions: [
        { amount: -3000, categoryId: actual.categories['Food'] },
        { amount: -2000, categoryId: actual.categories['General'] },
      ],
    };
    const ok = await call({ method: 'POST', url: tx(), path: '/v1/budgets/{budgetId}/transactions', token: owner.accessToken, body: good });
    expect(ok.status).toBe(201);
    expect((ok.body as { subtransactions: unknown[] }).subtransactions).toHaveLength(2);

    const bad = { ...good, id: randomUUID(), amount: -4000 };
    const res = await call({ method: 'POST', url: tx(), path: '/v1/budgets/{budgetId}/transactions', token: owner.accessToken, body: bad });
    expect(res.status).toBe(400);
    expect(res.body).toMatchObject({ code: 'validation' });
  });

  it('returns the envelope month with normalized signs', async () => {
    const res = await call({ method: 'GET', url: monthUrl(), path: '/v1/budgets/{budgetId}/months/{month}', token: owner.accessToken });
    const m = res.body as {
      budgetType: string;
      totalBudgeted: number;
      groups: { isIncome: boolean; categories: { name: string; budgeted: number; spent: number; balance: number; carryover: boolean; received: number }[] }[];
    };
    expect(m.budgetType).toBe('envelope');
    expect(m.totalBudgeted).toBe(60000 + 185000 + 20000);
    const cats = Object.fromEntries(m.groups.flatMap((g) => g.categories).map((c) => [c.name, c]));
    expect(cats['Food']).toMatchObject({ budgeted: 60000, spent: -(9834 + 4523 + 3000), carryover: true });
    expect(cats['Food']!.balance).toBe(60000 - (9834 + 4523 + 3000));
    expect(cats['Income']!.received).toBe(610000);
    recordFixture('budget-month', res.body);
  });

  it('rejects unknown months', async () => {
    const res = await call({
      method: 'GET',
      url: `/v1/budgets/${budgetId}/months/1999-01`,
      path: '/v1/budgets/{budgetId}/months/{month}',
      token: owner.accessToken,
    });
    expect(res.status).toBe(404);
  });

  it('updates a category budget and carryover', async () => {
    const res = await call({
      method: 'PATCH',
      url: `${monthUrl()}/categories/${actual.categories['General']}`,
      path: '/v1/budgets/{budgetId}/months/{month}/categories/{categoryId}',
      token: owner.accessToken,
      body: { budgeted: 25000, carryover: true },
    });
    const general = (res.body as { groups: { categories: { id: string; budgeted: number; carryover: boolean }[] }[] }).groups
      .flatMap((g) => g.categories)
      .find((c) => c.id === actual.categories['General']);
    expect(general).toMatchObject({ budgeted: 25000, carryover: true });
  });

  it('moves money exactly once per Idempotency-Key', async () => {
    const key = randomUUID();
    const move = () =>
      call({
        method: 'POST',
        url: `${monthUrl()}/transfers`,
        path: '/v1/budgets/{budgetId}/months/{month}/transfers',
        token: owner.accessToken,
        headers: { 'idempotency-key': key },
        body: { from: actual.categories['Food'], to: actual.categories['General'], amount: 5000 },
      });
    await move();
    await move();

    const res = await call({ method: 'GET', url: monthUrl(), path: '/v1/budgets/{budgetId}/months/{month}', token: owner.accessToken });
    const cats = Object.fromEntries(
      (res.body as { groups: { categories: { name: string; budgeted: number }[] }[] }).groups.flatMap((g) => g.categories).map((c) => [c.name, c]),
    );
    expect(cats['Food']!.budgeted).toBe(55000);
    expect(cats['General']!.budgeted).toBe(30000);
  });

  it('requires an Idempotency-Key to move money', async () => {
    const res = await call({
      method: 'POST',
      url: `${monthUrl()}/transfers`,
      path: '/v1/budgets/{budgetId}/months/{month}/transfers',
      token: owner.accessToken,
      body: { from: 'to-budget', to: actual.categories['Food'], amount: 100 },
    });
    expect(res.status).toBe(400);
  });

  it('holds and releases money for next month', async () => {
    const held = await call({
      method: 'PUT',
      url: `${monthUrl()}/hold`,
      path: '/v1/budgets/{budgetId}/months/{month}/hold',
      token: owner.accessToken,
      body: { amount: 10000 },
    });
    expect((held.body as { forNextMonth: number }).forNextMonth).toBe(10000);
    const reset = await call({ method: 'DELETE', url: `${monthUrl()}/hold`, path: '/v1/budgets/{budgetId}/months/{month}/hold', token: owner.accessToken });
    expect((reset.body as { forNextMonth: number }).forNextMonth).toBe(0);
  });

  it('sees changes made by other Actual clients (e.g. the web app)', async () => {
    const id = randomUUID();
    const dataDir = join(root, 'external');
    mkdirSync(dataDir, { recursive: true });
    await promisify(execFile)(process.execPath, [
      join(here, '..', 'support', 'external-client.mjs'),
      actual.url,
      actual.password,
      dataDir,
      budgetId,
      actual.accounts.checking,
      JSON.stringify({ id, date: `${actual.month}-14`, amount: -777, payee_name: 'Made on the web' }),
    ]);
    const res = await call({
      method: 'GET',
      url: `${tx()}?accountId=${actual.accounts.checking}`,
      path: '/v1/budgets/{budgetId}/transactions',
      token: owner.accessToken,
    });
    expect((res.body as { items: { id: string }[] }).items.map((t) => t.id)).toContain(id);
  });
});

describe('tokens', () => {
  it('rotates refresh tokens and revokes the device when an old one is replayed', async () => {
    const refreshed = await call({ method: 'POST', url: '/v1/auth/refresh', path: '/v1/auth/refresh', body: { refreshToken: owner.refreshToken } });
    expect(refreshed.status).toBe(200);
    const next = refreshed.body as { accessToken: string; refreshToken: string };

    // The previous access token stops working once rotated.
    expect((await call({ method: 'GET', url: '/v1/me', path: '/v1/me', token: owner.accessToken })).status).toBe(401);
    expect((await call({ method: 'GET', url: '/v1/me', path: '/v1/me', token: next.accessToken })).status).toBe(200);

    // Replaying the old refresh token looks like theft: the device is revoked.
    const replay = await call({ method: 'POST', url: '/v1/auth/refresh', path: '/v1/auth/refresh', body: { refreshToken: owner.refreshToken } });
    expect(replay.status).toBe(401);
    expect((await call({ method: 'GET', url: '/v1/me', path: '/v1/me', token: next.accessToken })).status).toBe(401);
  });

  it('records an audit trail of household activity', () => {
    const actions = store.recentAudit(200).map((r) => r.action);
    expect(actions).toEqual(expect.arrayContaining(['auth.paired', 'transaction.created', 'budget.money_moved', 'household.member_created']));
  });
});
