import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { createDeps } from '../../src/deps.js';
import { buildServer, type Deps } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';
import { day, startFakeSimpleFin } from '../support/fake-simplefin.js';
import { PLAID_CLIENT_ID, PLAID_SECRET, startFakePlaid } from '../support/fake-plaid.js';

// Plaid end to end: a real Actual server, a stand-in for Plaid's API, and (for moving an
// account over) a stand-in SimpleFIN Bridge.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'plaid');
const silent = { debug() {}, info() {}, warn() {} };
const today = new Date().toISOString().slice(0, 10);
const daysAgo = (n: number) => new Date(Date.now() - n * 86400_000).toISOString().slice(0, 10);

let actual: SeededActual;
let plaid: Awaited<ReturnType<typeof startFakePlaid>>;
let sf: Awaited<ReturnType<typeof startFakeSimpleFin>>;
let store: HouseholdStore;
let host: ActualHost;
let deps: Deps;
let app: FastifyInstance;
let dataDir: string;
let owner: string;
let member: string;
let viewer: string;
let b: string;

function tokenFor(role: 'owner' | 'member' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, path, res) as Record<string, any> };
}

async function waitForJob(jobId: string) {
  for (let i = 0; i < 100; i++) {
    const job = (await call('GET', `/v1/jobs/${jobId}`, '/v1/jobs/{jobId}', owner)).body;
    if (job.status !== 'running') return job;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('job did not finish');
}

const txnsOf = async (accountId: string) =>
  (await call('GET', `/v1/budgets/${b}/transactions?accountId=${accountId}`, '/v1/budgets/{budgetId}/transactions', member)).body.items as any[];
const syncAll = async () => waitForJob((await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member)).body.id);

beforeAll(async () => {
  plaid = await startFakePlaid(
    [
      {
        itemId: 'item_wf', accessToken: 'access-production-wf', publicToken: 'public-production-wf', institution: 'Wells Fargo',
        accounts: [
          { id: 'acc_chk', name: 'Everyday Checking', type: 'depository', subtype: 'checking', mask: '6975', current: 1159.18 },
          { id: 'acc_sav', name: 'Way2Save Savings', type: 'depository', subtype: 'savings', mask: '1111', current: 500 },
        ],
      },
      {
        itemId: 'item_disc', accessToken: 'access-production-disc', publicToken: 'public-production-disc', institution: 'Discover',
        accounts: [{ id: 'acc_card', name: 'Discover it Card', type: 'credit', subtype: 'credit card', mask: '4242', current: 245.04 }],
      },
    ],
    { pageSize: 2 }, // small pages, so syncing has to follow has_more
  );
  plaid.add(
    'item_wf',
    // Plaid: positive is money out.
    { id: 'txn_1', accountId: 'acc_chk', date: today, amount: 12.34, name: 'COFFEE SHOP 123', merchant: 'Coffee Shop' },
    { id: 'txn_2', accountId: 'acc_chk', date: daysAgo(3), amount: -2500, name: 'PAYROLL ACME' },
    { id: 'txn_p', accountId: 'acc_chk', date: today, amount: 50, name: 'GAS 1234', pending: true },
    { id: 'txn_old', accountId: 'acc_chk', date: daysAgo(90), amount: 1, name: 'TOO OLD' },
    { id: 'txn_sav', accountId: 'acc_sav', date: daysAgo(2), amount: -0.42, name: 'INTEREST' },
  );
  plaid.add(
    'item_disc',
    { id: 'ctxn_1', accountId: 'acc_card', date: daysAgo(1), amount: 45.67, name: 'GROCERY STORE', merchant: 'Grocery Store' },
    { id: 'ctxn_2', accountId: 'acc_card', date: daysAgo(2), amount: -200, name: 'PAYMENT THANK YOU' },
  );
  sf = await startFakeSimpleFin([
    {
      id: 'SF-CHK', name: 'Everyday Checking', org: { domain: 'wellsfargo.com', name: 'Wells Fargo' }, balance: '1159.18',
      transactions: [{ id: 'T1', posted: day(today), amount: '-12.34', description: 'COFFEE SHOP 123', payee: 'Coffee Shop' }],
    },
  ]);
  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  b = actual.budgetId;
  dataDir = join(root, 'bridge');
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  // Left over from Teller, which closed: the bridge deletes it at start.
  writeFileSync(join(dataDir, 'teller.json'), '{"tokens":{}}');
  const config: BridgeConfig = {
    port: 0, host: '127.0.0.1', dataDir, publicUrl: 'https://budget-api.example.com', trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0, accessTokenTtlSec: 3600, refreshTokenTtlSec: 3600, logLevel: 'silent',
    plaidApiUrl: plaid.url,
  };
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  deps = createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent);
  app = await buildServer(deps, { logger: false });
  owner = tokenFor('owner');
  member = tokenFor('member');
  viewer = tokenFor('viewer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
  await plaid?.stop();
  await sf?.stop();
});

describe('Plaid setup', () => {
  it("starts out not set up, and Teller's leftovers are gone", async () => {
    const res = await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer);
    expect(res.body.plaid).toEqual({ configured: false, clientId: null, environment: null, androidPackageName: 'app.centsible.budget', items: [] });
    expect(existsSync(join(dataDir, 'teller.json'))).toBe(false);
  });

  it('only owners can set it up', async () => {
    const res = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', member, { clientId: PLAID_CLIENT_ID, secret: PLAID_SECRET, environment: 'production' });
    expect(res.status).toBe(403);
  });

  it('checks the keys with Plaid', async () => {
    let res = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner, { clientId: 'nope', secret: PLAID_SECRET, environment: 'production' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/isn't a Plaid client id/);
    res = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner, { clientId: PLAID_CLIENT_ID, environment: 'production' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/secret/);
    res = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner, { clientId: PLAID_CLIENT_ID, secret: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', environment: 'production' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/doesn't accept that client id and secret/);
  });

  it('saves working keys, with the secret kept out of the household database', async () => {
    const res = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner, { clientId: PLAID_CLIENT_ID, secret: PLAID_SECRET, environment: 'production' });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ configured: true, clientId: PLAID_CLIENT_ID, environment: 'production' });
    expect(statSync(join(dataDir, 'plaid.json')).mode & 0o777).toBe(0o600);
    expect(readFileSync(join(dataDir, 'bridge.sqlite')).includes(PLAID_SECRET)).toBe(false);
    // Saving again without the secret keeps the stored one.
    const again = await call('PUT', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner, { clientId: PLAID_CLIENT_ID, environment: 'production' });
    expect(again.status).toBe(200);
    recordFixture('plaid-status', again.body);
  });

  it('hands members a Link token for the Android app', async () => {
    const res = await call('POST', '/v1/bank-sync/plaid/link-token', '/v1/bank-sync/plaid/link-token', member, {});
    expect(res.status).toBe(200);
    expect(res.body.linkToken).toMatch(/^link-/);
    recordFixture('plaid-link-token', res.body);
    const sent = plaid.stats.linkTokens.at(-1)!;
    expect(sent).toMatchObject({ android_package_name: 'app.centsible.budget', products: ['transactions'], transactions: { days_requested: 730 } });
    expect(sent.access_token).toBeUndefined();
    expect((await call('POST', '/v1/bank-sync/plaid/link-token', '/v1/bank-sync/plaid/link-token', viewer, {})).status).toBe(403);
  });

  it('rejects a public token Plaid does not know', async () => {
    const res = await call('POST', '/v1/bank-sync/plaid/items', '/v1/bank-sync/plaid/items', member, { publicToken: 'public-bogus', institution: 'Nope' });
    expect(res.status).toBe(502);
  });

  it('saves bank logins from Plaid Link, keeping access tokens out of the database', async () => {
    const wf = await call('POST', '/v1/bank-sync/plaid/items', '/v1/bank-sync/plaid/items', member, { publicToken: 'public-production-wf', institution: 'Wells Fargo' });
    expect(wf.status).toBe(201);
    expect(wf.body).toEqual({ id: 'item_wf', institution: 'Wells Fargo', status: 'ok', message: null, linkedAccounts: 0, updatedAt: expect.any(String) });
    recordFixture('plaid-item', wf.body);
    // No institution from the phone: Plaid's name is used.
    expect((await call('POST', '/v1/bank-sync/plaid/items', '/v1/bank-sync/plaid/items', member, { publicToken: 'public-production-disc' })).body.institution).toBe('Discover');
    expect(readFileSync(join(dataDir, 'bridge.sqlite')).includes('access-production-wf')).toBe(false);
  });
});

describe('linking and syncing', () => {
  let simplefinChecking: string;

  it('lists the accounts across bank logins, debts negative', async () => {
    const res = await call('GET', `/v1/budgets/${b}/bank-sync/plaid/accounts`, '/v1/budgets/{budgetId}/bank-sync/plaid/accounts', member);
    expect(res.body.items).toEqual([
      { id: 'acc_chk', itemId: 'item_wf', institution: 'Wells Fargo', name: 'Everyday Checking', type: 'depository', subtype: 'checking', mask: '6975', balance: 115918, currency: 'USD', linkedAccountId: null, linkedAccountName: null },
      { id: 'acc_sav', itemId: 'item_wf', institution: 'Wells Fargo', name: 'Way2Save Savings', type: 'depository', subtype: 'savings', mask: '1111', balance: 50000, currency: 'USD', linkedAccountId: null, linkedAccountName: null },
      { id: 'acc_card', itemId: 'item_disc', institution: 'Discover', name: 'Discover it Card', type: 'credit', subtype: 'credit card', mask: '4242', balance: -24504, currency: 'USD', linkedAccountId: null, linkedAccountName: null },
    ]);
    recordFixture('plaid-accounts', res.body);
  });

  it('moves an account over from SimpleFIN without duplicating what is already there', async () => {
    await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
    simplefinChecking = (await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', member, { externalId: 'SF-CHK' })).body.accountId;
    expect((await txnsOf(simplefinChecking)).some((t) => t.amount === -1234)).toBe(true);

    const res = await call('POST', `/v1/budgets/${b}/bank-sync/plaid/link`, '/v1/budgets/{budgetId}/bank-sync/plaid/link', member, { plaidAccountId: 'acc_chk', accountId: simplefinChecking });
    expect(res.status).toBe(201);
    // The coffee is already there from SimpleFIN (matched); payroll is new; pending, too-old and other accounts' ones are left out.
    expect(res.body.result).toMatchObject({ accountId: simplefinChecking, newTransactions: 1, matchedTransactions: 1, error: null, status: 'ok' });
    recordFixture('plaid-link', res.body);

    const after = await txnsOf(simplefinChecking);
    expect(after.filter((t) => t.amount === -1234)).toHaveLength(1);
    expect(after.some((t) => t.amount === 250000)).toBe(true);
    expect(after.some((t) => t.amount === -5000 || t.amount === -100 || t.amount === 42)).toBe(false);

    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === simplefinChecking);
    expect(acct).toMatchObject({ syncSource: 'plaid', bankSyncStatus: 'ok' });
    expect(acct.lastSync).not.toBeNull();
  });

  it('links a credit card: purchases come in negative, payments positive', async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/plaid/link`, '/v1/budgets/{budgetId}/bank-sync/plaid/link', member, { plaidAccountId: 'acc_card', accountId: actual.accounts.card, startDate: daysAgo(10) });
    expect(res.body.result).toMatchObject({ newTransactions: 2, error: null });
    const card = await txnsOf(actual.accounts.card);
    expect(card.filter((t) => t.amount === -4567 || t.amount === 20000)).toHaveLength(2);
  });

  it("won't feed two accounts from one bank account", async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/plaid/link`, '/v1/budgets/{budgetId}/bank-sync/plaid/link', member, { plaidAccountId: 'acc_card', accountId: actual.accounts.checking });
    expect(res.status).toBe(400);
  });

  it('syncs only what changed, follows edits and withdrawals, and posts pending ones once they clear', async () => {
    plaid.add('item_wf', { id: 'txn_3', accountId: 'acc_chk', date: today, amount: 8, name: 'SANDWICH' });
    // The pending gas purchase posts (Plaid: the pending one goes away, a posted one arrives).
    plaid.remove('item_wf', { id: 'txn_p', accountId: 'acc_chk', date: today, amount: 50, name: 'GAS 1234', pending: true });
    plaid.add('item_wf', { id: 'txn_p2', accountId: 'acc_chk', date: today, amount: 48.5, name: 'GAS 1234' });
    const job = await syncAll();
    expect(job.status).toBe('succeeded');
    expect(job.result.results.find((r: any) => r.accountId === simplefinChecking)).toMatchObject({ newTransactions: 2, error: null, status: 'ok' });
    expect(job.result.results.find((r: any) => r.accountId === actual.accounts.card)).toMatchObject({ newTransactions: 0 });
    let chk = await txnsOf(simplefinChecking);
    expect(chk.filter((t) => t.amount === -800)).toHaveLength(1);
    expect(chk.filter((t) => t.amount === -4850)).toHaveLength(1);

    // The bank corrects the sandwich, then reverses the payroll.
    plaid.modify('item_wf', { id: 'txn_3', accountId: 'acc_chk', date: today, amount: 9, name: 'SANDWICH' });
    plaid.remove('item_wf', { id: 'txn_2', accountId: 'acc_chk', date: daysAgo(3), amount: -2500, name: 'PAYROLL ACME' });
    const again = await waitForJob((await call('POST', `/v1/budgets/${b}/accounts/${simplefinChecking}/bank-sync`, '/v1/budgets/{budgetId}/accounts/{id}/bank-sync', member)).body.id);
    expect(again.result.results).toHaveLength(1);
    chk = await txnsOf(simplefinChecking);
    expect(chk.some((t) => t.amount === -800)).toBe(false);
    expect(chk.filter((t) => t.amount === -900)).toHaveLength(1);
    expect(chk.some((t) => t.amount === 250000)).toBe(false);

    // Nothing new: nothing changes.
    const quiet = await syncAll();
    expect(quiet.result.results.every((r: any) => r.newTransactions === 0 && r.error === null)).toBe(true);
  });

  it('imports older history without duplicating what is there', async () => {
    const before = await txnsOf(simplefinChecking);
    expect(before.some((t) => t.amount === -100)).toBe(false); // 90 days old: before the 30 days linking imported
    const url = `/v1/budgets/${b}/bank-sync/plaid/history`;
    const path = '/v1/budgets/{budgetId}/bank-sync/plaid/history';
    expect((await call('POST', url, path, member, { since: '2999-01-01' })).status).toBe(202); // a job; it fails on the date
    const bad = await waitForJob((await call('POST', url, path, member, { since: '2999-01-01' })).body.id);
    expect(bad.status).toBe('failed');

    const started = await call('POST', url, path, member, { since: daysAgo(400), accountIds: [simplefinChecking] });
    expect(started.status).toBe(202);
    const job = await waitForJob(started.body.id);
    expect(job.status).toBe('succeeded');
    expect(job.result.results).toHaveLength(1);
    expect(job.result.results[0]).toMatchObject({ accountId: simplefinChecking, error: null, status: 'ok' });

    const after = await txnsOf(simplefinChecking);
    expect(after.filter((t) => t.amount === -100)).toHaveLength(1); // the old one arrived
    // Everything else is as it was: no duplicates, and the reversed payroll stays gone.
    for (const amount of [-1234, -900, -4850]) expect(after.filter((t) => t.amount === amount)).toHaveLength(1);
    expect(after.some((t) => t.amount === 250000)).toBe(false);
    expect(store.plaidLink(b, simplefinChecking)!.startDate).toBe(daysAgo(90) < daysAgo(400) ? daysAgo(90) : daysAgo(400));

    // A sync afterwards carries on from the new position.
    const quiet = await syncAll();
    expect(quiet.result.results.find((r: any) => r.accountId === simplefinChecking)).toMatchObject({ newTransactions: 0, error: null });
  });

  it('gives linked accounts a type from the bank, which can be changed or cleared', async () => {
    const accounts = async () => (await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', viewer)).body.items as any[];
    let list = await accounts();
    expect(list.find((a) => a.id === simplefinChecking).kind).toBe('cash');
    expect(list.find((a) => a.id === actual.accounts.card).kind).toBe('credit');
    expect(list.find((a) => a.id === actual.accounts.checking).kind).toBeNull(); // never linked: not set

    const url = `/v1/budgets/${b}/accounts/${actual.accounts.checking}`;
    const path = '/v1/budgets/{budgetId}/accounts/{id}';
    expect((await call('PATCH', url, path, viewer, { kind: 'savings' })).status).toBe(403);
    expect((await call('PATCH', url, path, member, { kind: 'boat' })).status).toBe(400);
    const set = await call('PATCH', url, path, member, { kind: 'savings' });
    expect(set.status).toBe(200);
    expect(set.body.kind).toBe('savings');
    const renamed = await call('PATCH', url, path, member, { name: 'Rainy day' });
    expect(renamed.body).toMatchObject({ name: 'Rainy day', kind: 'savings' }); // renaming keeps the type
    list = await accounts();
    expect(list.find((a) => a.id === actual.accounts.checking).kind).toBe('savings');
    expect((await call('PATCH', url, path, member, { kind: null })).body.kind).toBeNull();

    const created = await call('POST', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member, { name: 'House loan', offBudget: true, kind: 'mortgage' });
    expect(created.status).toBe(201);
    expect(created.body.kind).toBe('mortgage');
  });

  it('reports a bank login that needs signing in again, and recovers through update mode', async () => {
    plaid.item('item_wf').error = 'ITEM_LOGIN_REQUIRED';
    const job = await syncAll();
    const chk = job.result.results.find((r: any) => r.accountId === simplefinChecking);
    expect(chk).toMatchObject({ status: 'reauth-required' });
    expect(chk.error).toMatch(/sign in again/);
    const overview = (await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer)).body;
    expect(overview.plaid.items.find((e: any) => e.id === 'item_wf')).toMatchObject({ status: 'reauth', linkedAccounts: 1 });
    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === simplefinChecking);
    expect(acct.bankSyncStatus).toBe('reauth-required');

    // Update mode: a Link token for the existing login, then the app reports success.
    const token = await call('POST', '/v1/bank-sync/plaid/link-token', '/v1/bank-sync/plaid/link-token', member, { itemId: 'item_wf' });
    expect(token.status).toBe(200);
    const sent = plaid.stats.linkTokens.at(-1)!;
    expect(sent.access_token).toBe('access-production-wf');
    expect(sent.products).toBeUndefined();
    plaid.item('item_wf').error = null;
    const fixed = await call('POST', '/v1/bank-sync/plaid/items', '/v1/bank-sync/plaid/items', member, { itemId: 'item_wf' });
    expect(fixed.status).toBe(201);
    expect(fixed.body.status).toBe('ok');
    const next = await syncAll();
    expect(next.result.results.find((r: any) => r.accountId === simplefinChecking)).toMatchObject({ status: 'ok', error: null });
  });

  it('unlinks an account, keeping its transactions', async () => {
    const res = await call('POST', `/v1/budgets/${b}/accounts/${actual.accounts.card}/unlink`, '/v1/budgets/{budgetId}/accounts/{id}/unlink', member);
    expect(res.status).toBe(204);
    expect((await txnsOf(actual.accounts.card)).some((t) => t.amount === -4567)).toBe(true);
    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === actual.accounts.card);
    expect(acct.syncSource).toBeNull();
  });

  it('only owners remove a bank login; Plaid is told to end access', async () => {
    expect((await call('DELETE', '/v1/bank-sync/plaid/items/item_wf', '/v1/bank-sync/plaid/items/{id}', member)).status).toBe(403);
    expect((await call('DELETE', '/v1/bank-sync/plaid/items/item_wf', '/v1/bank-sync/plaid/items/{id}', owner)).status).toBe(204);
    expect(plaid.stats.removed).toEqual(['item_wf']);
    const overview = (await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body;
    expect(overview.plaid.items.map((e: any) => e.id)).toEqual(['item_disc']);
    expect(store.plaidLinks(b)).toEqual([]);
  });

  it('forgets everything on reset', async () => {
    expect((await call('DELETE', '/v1/bank-sync/plaid', '/v1/bank-sync/plaid', owner)).status).toBe(204);
    expect((await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body.plaid).toMatchObject({ configured: false, items: [] });
    expect(plaid.stats.removed).toEqual(['item_wf', 'item_disc']);
    expect(existsSync(join(dataDir, 'plaid.json'))).toBe(false);
  });
});
