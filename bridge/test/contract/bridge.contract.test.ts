import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { BudgetOps } from '../../src/actual/budget-ops.js';
import { StructureOps } from '../../src/actual/structure-ops.js';
import { TransactionOps } from '../../src/actual/transaction-ops.js';
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
  app = await buildServer({ config, store, host, ops: new BudgetOps(host), transactions: new TransactionOps(host), structure: new StructureOps(host) }, { logger: false });

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
    expect(caps.features['transactions.splits']).toBe(true);
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

describe('phase 1: transactions', () => {
  const tx = () => `/v1/budgets/${budgetId}/transactions`;
  const one = (id: string) => `${tx()}/${id}`;
  const LIST = '/v1/budgets/{budgetId}/transactions';
  const ONE = '/v1/budgets/{budgetId}/transactions/{transactionId}';
  type Tx = { id: string; accountId: string; amount: number; payeeName: string | null; categoryId: string | null; notes: string | null; cleared: boolean; transferId: string | null; isParent: boolean; subtransactions: { id: string; amount: number; categoryId: string | null }[] };

  async function create(body: Record<string, unknown>) {
    const res = await call({ method: 'POST', url: tx(), path: LIST, token: owner.accessToken, body: { id: randomUUID(), date: `${actual.month}-15`, ...body } });
    expect(res.status).toBe(201);
    return res.body as Tx;
  }
  const list = async (query: string) =>
    ((await call({ method: 'GET', url: `${tx()}?${query}`, path: LIST, token: owner.accessToken })).body as { items: Tx[] }).items;

  it('searches payee, notes and category names', async () => {
    expect((await list('q=trader')).map((t) => t.payeeName)).toContain("Trader Joe's");
    expect((await list('q=groceries')).map((t) => t.payeeName)).toContain("Trader Joe's"); // notes "#groceries"
    const byCategory = await list('q=Bills');
    expect(byCategory.length).toBeGreaterThan(0);
    expect(await list('q=zzzz-no-match')).toHaveLength(0);
  });

  it('filters transactions that still need a category', async () => {
    const t = await create({ accountId: actual.accounts.checking, amount: -999, payeeName: 'Mystery Shop' });
    const ids = (await list('uncategorized=true')).map((x) => x.id);
    expect(ids).toContain(t.id);
    expect(ids.every((id) => id !== undefined)).toBe(true);
    const categorized = (await list('uncategorized=true')).filter((x) => x.categoryId !== null);
    expect(categorized).toHaveLength(0);
  });

  it('gets one transaction and 404s on unknown ids', async () => {
    const t = await create({ accountId: actual.accounts.checking, amount: -100, payeeName: 'Getter' });
    const got = await call({ method: 'GET', url: one(t.id), path: ONE, token: owner.accessToken });
    expect((got.body as Tx).id).toBe(t.id);
    const missing = await call({ method: 'GET', url: one(randomUUID()), path: ONE, token: owner.accessToken });
    expect(missing.status).toBe(404);
  });

  it('edits fields and creates new payees by name', async () => {
    const t = await create({ accountId: actual.accounts.checking, amount: -1500, payeeName: 'Corner Store', categoryId: actual.categories['Food'] });
    const res = await call({
      method: 'PATCH',
      url: one(t.id),
      path: ONE,
      token: owner.accessToken,
      body: { amount: -1750, notes: 'milk + bread', categoryId: actual.categories['General'], cleared: false, payeeName: 'Corner Market' },
    });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ amount: -1750, notes: 'milk + bread', categoryId: actual.categories['General'], cleared: false, payeeName: 'Corner Market' });
    recordFixture('transaction-updated', res.body);
  });

  it('creates and deletes transfers with both sides', async () => {
    const payees = (await call({ method: 'GET', url: `/v1/budgets/${budgetId}/payees`, path: '/v1/budgets/{budgetId}/payees', token: owner.accessToken })).body as {
      items: { id: string; transferAccountId: string | null }[];
    };
    const toCard = payees.items.find((p) => p.transferAccountId === actual.accounts.card)!;
    const t = await create({ accountId: actual.accounts.checking, amount: -30000, payeeId: toCard.id });
    expect(t.transferId).not.toBeNull();
    const other = (await list(`accountId=${actual.accounts.card}`)).find((x) => x.id === t.transferId)!;
    expect(other.amount).toBe(30000);

    const del = await call({ method: 'DELETE', url: one(t.id), path: ONE, token: owner.accessToken });
    expect(del.status).toBe(204);
    expect((await call({ method: 'GET', url: one(t.id), path: ONE, token: owner.accessToken })).status).toBe(404);
    expect((await call({ method: 'GET', url: one(t.transferId!), path: ONE, token: owner.accessToken })).status).toBe(404);
  });

  it('splits, re-splits and unsplits a transaction', async () => {
    const t = await create({ accountId: actual.accounts.card, amount: -10000, payeeName: 'Walmart', categoryId: actual.categories['Food'] });
    const split = await call({
      method: 'PATCH',
      url: one(t.id),
      path: ONE,
      token: owner.accessToken,
      body: { subtransactions: [{ amount: -6000, categoryId: actual.categories['Food'] }, { amount: -4000, categoryId: actual.categories['General'] }] },
    });
    const s1 = split.body as Tx;
    expect(s1.isParent).toBe(true);
    expect(s1.categoryId).toBeNull();
    expect(s1.subtransactions.map((x) => x.amount).sort((a, b) => a - b)).toEqual([-6000, -4000]);

    const keep = s1.subtransactions.find((x) => x.amount === -6000)!;
    const resplit = await call({
      method: 'PATCH',
      url: one(t.id),
      path: ONE,
      token: owner.accessToken,
      body: { subtransactions: [{ id: keep.id, amount: -7000, categoryId: actual.categories['Food'] }, { amount: -3000, categoryId: actual.categories['Bills'] }] },
    });
    const s2 = resplit.body as Tx;
    expect(s2.subtransactions).toHaveLength(2);
    expect(s2.subtransactions.find((x) => x.id === keep.id)?.amount).toBe(-7000);
    expect(s2.subtransactions.some((x) => x.categoryId === actual.categories['Bills'])).toBe(true);

    const bad = await call({ method: 'PATCH', url: one(t.id), path: ONE, token: owner.accessToken, body: { subtransactions: [{ amount: -1 }] } });
    expect(bad.status).toBe(400);

    const unsplit = await call({ method: 'PATCH', url: one(t.id), path: ONE, token: owner.accessToken, body: { subtransactions: [], categoryId: actual.categories['Food'] } });
    const s3 = unsplit.body as Tx;
    expect(s3.isParent).toBe(false);
    expect(s3.subtransactions).toHaveLength(0);
    expect(s3.categoryId).toBe(actual.categories['Food']);
  });

  it('reports budget preferences with defaults', async () => {
    const res = await call({ method: 'GET', url: `/v1/budgets/${budgetId}/preferences`, path: '/v1/budgets/{budgetId}/preferences', token: owner.accessToken });
    expect(res.body).toMatchObject({ budgetType: 'envelope', currencyCode: 'USD', firstDayOfWeek: 0 });
    recordFixture('preferences', res.body);
  });
});

describe('phase 1: accounts and categories', () => {
  const base = () => `/v1/budgets/${budgetId}`;
  type Account = { id: string; name: string; balance: number; closed: boolean };

  it('creates, renames, closes and reopens accounts', async () => {
    const created = await call({
      method: 'POST',
      url: `${base()}/accounts`,
      path: '/v1/budgets/{budgetId}/accounts',
      token: owner.accessToken,
      body: { name: 'Kids Savings', initialBalance: 5000 },
    });
    expect(created.status).toBe(201);
    const acct = created.body as Account;
    expect(acct).toMatchObject({ name: 'Kids Savings', balance: 5000, closed: false });

    const renamed = await call({ method: 'PATCH', url: `${base()}/accounts/${acct.id}`, path: '/v1/budgets/{budgetId}/accounts/{id}', token: owner.accessToken, body: { name: 'Kid Savings' } });
    expect((renamed.body as Account).name).toBe('Kid Savings');

    const close = (body: object) =>
      call({ method: 'POST', url: `${base()}/accounts/${acct.id}/close`, path: '/v1/budgets/{budgetId}/accounts/{id}/close', token: owner.accessToken, body });
    expect((await close({})).status).toBe(400); // has a balance
    const closed = await close({ transferAccountId: actual.accounts.checking });
    expect(closed.status).toBe(200);
    expect((closed.body as Account).closed).toBe(true);

    const reopened = await call({ method: 'POST', url: `${base()}/accounts/${acct.id}/reopen`, path: '/v1/budgets/{budgetId}/accounts/{id}/reopen', token: owner.accessToken });
    expect((reopened.body as Account).closed).toBe(false);
  });

  it('manages category groups and categories', async () => {
    const group = await call({ method: 'POST', url: `${base()}/category-groups`, path: '/v1/budgets/{budgetId}/category-groups', token: owner.accessToken, body: { name: 'Kids' } });
    expect(group.status).toBe(201);
    const groupId = (group.body as { id: string }).id;

    const cat = await call({ method: 'POST', url: `${base()}/categories`, path: '/v1/budgets/{budgetId}/categories', token: owner.accessToken, body: { name: 'Soccer', groupId } });
    expect(cat.status).toBe(201);
    const catId = (cat.body as { id: string }).id;
    expect(cat.body).toMatchObject({ name: 'Soccer', groupId, isIncome: false, hidden: false });

    const hidden = await call({
      method: 'PATCH',
      url: `${base()}/categories/${catId}`,
      path: '/v1/budgets/{budgetId}/categories/{id}',
      token: owner.accessToken,
      body: { name: 'Sports', hidden: true },
    });
    expect(hidden.body).toMatchObject({ name: 'Sports', hidden: true });

    const renamedGroup = await call({ method: 'PATCH', url: `${base()}/category-groups/${groupId}`, path: '/v1/budgets/{budgetId}/category-groups/{id}', token: owner.accessToken, body: { name: 'Children' } });
    expect((renamedGroup.body as { name: string }).name).toBe('Children');

    const delCat = await call({
      method: 'DELETE',
      url: `${base()}/categories/${catId}?transferCategoryId=${actual.categories['General']}`,
      path: '/v1/budgets/{budgetId}/categories/{id}',
      token: owner.accessToken,
    });
    expect(delCat.status).toBe(204);
    const delGroup = await call({ method: 'DELETE', url: `${base()}/category-groups/${groupId}`, path: '/v1/budgets/{budgetId}/category-groups/{id}', token: owner.accessToken });
    expect(delGroup.status).toBe(204);

    const groups = (await call({ method: 'GET', url: `${base()}/category-groups`, path: '/v1/budgets/{budgetId}/category-groups', token: owner.accessToken })).body as { items: { id: string }[] };
    expect(groups.items.map((g) => g.id)).not.toContain(groupId);
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
