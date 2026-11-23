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
import type { Deps } from '../../src/http/server.js';
import { buildServer } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Sub-categories (levels 3 to 5, kept by the bridge) and the flows report behind the
// Sankey, against a real Actual server with the seeded household budget.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'category-tree');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let b: string;
let deps: Deps;

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

const month = new Date().toISOString().slice(0, 7);
const today = new Date().toISOString().slice(0, 10);
const newCat = async (name: string, parent: { groupId?: string; parentId?: string }) => {
  const r = await call('POST', '/categories', '/categories', owner, { name, ...parent });
  expect(r.status).toBe(201);
  return r.body as { id: string; groupId: string; parentId: string | null };
};
const patch = (id: string, body: Record<string, unknown>) => call('PATCH', `/categories/${id}`, '/categories/{id}', owner, body);
const groupsNow = async () => {
  const r = await call('GET', '/category-groups', '/category-groups', viewer);
  return (r.body.items as { id: string; categories: { id: string; parentId: string | null }[] }[]).flatMap((g) => g.categories.map((c) => ({ ...c, groupId: g.id })));
};

describe('sub-categories', () => {
  it('nests down to level 5, keeps it in the parent\'s group, and refuses a sixth level or a loop', async () => {
    const { Food } = actual.categories as Record<string, string>;
    const food = (await groupsNow()).find((c) => c.id === Food)!;
    expect(food.parentId).toBeNull();

    const groceries = await newCat('Groceries', { parentId: Food }); // level 3
    expect(groceries.parentId).toBe(Food);
    expect(groceries.groupId).toBe(food.groupId);
    const costco = await newCat('Costco', { parentId: groceries.id }); // level 4
    const bulk = await newCat('Bulk', { parentId: costco.id }); // level 5
    expect(bulk.parentId).toBe(costco.id);

    const tooDeep = await call('POST', '/categories', '/categories', owner, { name: 'Too deep', parentId: bulk.id });
    expect(tooDeep.status).toBe(400);
    const loop = await patch(Food!, { parentId: costco.id });
    expect(loop.status).toBe(400);

    const all = await groupsNow();
    expect(all.find((c) => c.id === costco.id)?.parentId).toBe(groceries.id);

    // The month carries the links too.
    const m = await call('GET', `/months/${month}`, '/months/{month}', viewer);
    const cats = (m.body.groups as { categories: { id: string; parentId: string | null }[] }[]).flatMap((g) => g.categories);
    expect(cats.find((c) => c.id === bulk.id)?.parentId).toBe(costco.id);
    recordFixture('category-groups-nested', (await call('GET', '/category-groups', '/category-groups', viewer)).body);
  });

  it('moves a branch with its sub-categories, and brings it back to the top', async () => {
    const { Food, Bills } = actual.categories as Record<string, string>;
    const groceries = (await groupsNow()).find((c) => c.parentId === Food)!;
    // Under Bills: still the same group in the seed, so only the link changes.
    const moved = await patch(groceries.id, { parentId: Bills });
    expect(moved.status).toBe(200);
    expect(moved.body.parentId).toBe(Bills);
    const all = await groupsNow();
    const costco = all.find((c) => c.parentId === groceries.id)!;
    expect(costco).toBeTruthy();
    // A branch too deep for its new place is refused: Groceries > Costco > Bulk is 3 deep.
    const bulk = all.find((c) => c.parentId === costco.id)!;
    expect((await patch(groceries.id, { parentId: bulk.id })).status).toBe(400);

    const top = await patch(groceries.id, { parentId: null });
    expect(top.body.parentId).toBeNull();
    expect((await patch(groceries.id, { parentId: Food })).status).toBe(200);
  });

  it('lists a category\'s transactions with its sub-categories on request', async () => {
    const { Food } = actual.categories as Record<string, string>;
    const groceries = (await groupsNow()).find((c) => c.parentId === Food)!;
    const id = randomUUID();
    await call('POST', '/transactions', '/transactions', owner, { id, accountId: actual.accounts.checking, date: today, amount: -1234, payeeName: 'Corner Store', categoryId: groceries.id });

    const only = await call('GET', `/transactions?categoryId=${Food}`, '/transactions', viewer);
    expect((only.body.items as { id: string }[]).some((t) => t.id === id)).toBe(false);
    const all = await call('GET', `/transactions?categoryId=${Food}&subcategories=true`, '/transactions', viewer);
    const ids = (all.body.items as { id: string; categoryId: string }[]).map((t) => t.id);
    expect(ids).toContain(id);
    expect((all.body.items as { categoryId: string }[]).some((t) => t.categoryId === Food)).toBe(true);
  });

  it('moves sub-categories up a level when their parent is deleted', async () => {
    const { Food } = actual.categories as Record<string, string>;
    const groceries = (await groupsNow()).find((c) => c.parentId === Food)!;
    const costco = (await groupsNow()).find((c) => c.parentId === groceries.id)!;
    const del = await call('DELETE', `/categories/${groceries.id}?transferCategoryId=${Food}`, '/categories/{id}', owner);
    expect(del.status).toBe(204);
    expect((await groupsNow()).find((c) => c.id === costco.id)?.parentId).toBe(Food);
  });

  it('can be made under a parent in another group, joining that group', async () => {
    const { Bills } = actual.categories as Record<string, string>;
    const g = await call('POST', '/category-groups', '/category-groups', owner, { name: 'Fun' });
    const games = await newCat('Games', { groupId: g.body.id });
    const r = await patch(games.id, { parentId: Bills });
    expect(r.status).toBe(200);
    const bills = (await groupsNow()).find((c) => c.id === Bills)!;
    expect(r.body.groupId).toBe(bills.groupId);
    // Moving it to a group on its own leaves its parent.
    const back = await patch(games.id, { groupId: g.body.id });
    expect(back.body.parentId).toBeNull();
    expect(back.body.groupId).toBe(g.body.id);
  });

  it('only members may nest categories', async () => {
    const { Food } = actual.categories as Record<string, string>;
    const r = await call('POST', '/categories', '/categories', viewer, { name: 'Nope', parentId: Food });
    expect(r.status).toBe(403);
  });
});

describe('flows report', () => {
  it('gives income and spending by category with parents, for a Sankey', async () => {
    const r = await call('GET', `/reports/flows?start=${month}&end=${month}`, '/reports/flows', viewer);
    expect(r.status).toBe(200);
    const body = r.body as { totalIncome: number; totalSpending: number; income: { amount: number; parentId: string | null }[]; spending: { amount: number; categoryId: string | null; parentId: string | null }[] };
    expect(body.totalIncome).toBeGreaterThan(0);
    expect(body.totalSpending).toBeLessThan(0);
    expect(body.income.every((c) => c.amount !== 0)).toBe(true);
    expect(body.totalSpending).toBe(body.spending.reduce((s, c) => s + c.amount, 0));
    // Corner Store's spending was in Groceries, which moved into Food when deleted.
    const { Food } = actual.categories as Record<string, string>;
    expect(body.spending.find((c) => c.categoryId === Food)?.parentId).toBeNull();
    recordFixture('report-flows', body);
    expect((await call('GET', `/reports/flows?start=${month}&end=2000-01`, '/reports/flows', viewer)).status).toBe(400);
  });
});
