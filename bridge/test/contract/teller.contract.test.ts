import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, statSync } from 'node:fs';
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
import { startFakeTeller } from '../support/fake-teller.js';

// Teller end to end: a real Actual server, a stand-in for Teller's API, and (for moving an
// account over) a stand-in SimpleFIN Bridge.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'teller');
const silent = { debug() {}, info() {}, warn() {} };
const today = new Date().toISOString().slice(0, 10);
const daysAgo = (n: number) => new Date(Date.now() - n * 86400_000).toISOString().slice(0, 10);

let actual: SeededActual;
let teller: Awaited<ReturnType<typeof startFakeTeller>>;
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

beforeAll(async () => {
  teller = await startFakeTeller([
    {
      token: 'token_wf', enrollmentId: 'enr_wf',
      accounts: [
        {
          id: 'acc_chk', name: 'Everyday Checking', type: 'depository', subtype: 'checking', lastFour: '6975', institution: 'Wells Fargo', ledger: '1159.18',
          transactions: [
            { id: 'txn_1', date: today, amount: '-12.34', description: 'COFFEE SHOP 123', counterparty: 'Coffee Shop' },
            { id: 'txn_2', date: daysAgo(3), amount: '2500.00', description: 'PAYROLL ACME' },
            { id: 'txn_p', date: today, amount: '-50.00', description: 'GAS 1234', status: 'pending' },
            { id: 'txn_old', date: daysAgo(90), amount: '-1.00', description: 'TOO OLD' },
          ],
        },
      ],
    },
    {
      token: 'token_cap1', enrollmentId: 'enr_cap1',
      accounts: [
        {
          // A bank that reports card purchases as positive: the link flips it.
          id: 'acc_card', name: 'Discover it Card', type: 'credit', subtype: 'credit_card', lastFour: '4242', institution: 'Capital One', ledger: '245.04',
          transactions: [
            { id: 'ctxn_1', date: daysAgo(1), amount: '45.67', description: 'GROCERY STORE', counterparty: 'Grocery Store' },
            { id: 'ctxn_2', date: daysAgo(2), amount: '-200.00', description: 'PAYMENT THANK YOU' },
          ],
        },
      ],
    },
  ]);
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
  const config: BridgeConfig = {
    port: 0, host: '127.0.0.1', dataDir, publicUrl: 'https://budget-api.example.com', trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0, accessTokenTtlSec: 3600, refreshTokenTtlSec: 3600, logLevel: 'silent',
    tellerApiUrl: teller.url,
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
  await teller?.stop();
  await sf?.stop();
});

describe('Teller setup', () => {
  it('starts out not set up', async () => {
    const res = await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer);
    expect(res.body.teller).toEqual({ configured: false, applicationId: null, environment: null, hasCertificate: false, enrollments: [] });
  });

  it('only owners can set it up', async () => {
    const res = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', member, { applicationId: 'app_abc123', environment: 'sandbox' });
    expect(res.status).toBe(403);
  });

  it('wants a certificate outside the sandbox, and checks it', async () => {
    let res = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'app_abc123', environment: 'development' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/certificate and private key/);
    res = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'app_abc123', environment: 'development', certificate: 'nope', privateKey: 'nope' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/isn't a certificate/);
    res = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'not-an-app', environment: 'sandbox' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/starts with "app_"/);
  });

  it('accepts a matching certificate and key, and keeps them out of the household database', async () => {
    const dir = join(root, 'cert');
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    const gen = (name: string) =>
      execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-days', '30', '-subj', `/CN=${name}`, '-keyout', join(dir, `${name}.key`), '-out', join(dir, `${name}.pem`)], { stdio: 'ignore' });
    gen('a');
    gen('b');
    const certA = readFileSync(join(dir, 'a.pem'), 'utf8');
    const keyB = readFileSync(join(dir, 'b.key'), 'utf8');
    const mismatch = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'app_abc123', environment: 'development', certificate: certA, privateKey: keyB });
    expect(mismatch.status).toBe(400);
    expect(mismatch.body.detail).toMatch(/doesn't belong to that certificate/);

    const keyA = readFileSync(join(dir, 'a.key'), 'utf8');
    const res = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'app_abc123', environment: 'development', certificate: certA, privateKey: keyA });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ configured: true, applicationId: 'app_abc123', environment: 'development', hasCertificate: true });
    expect(statSync(join(dataDir, 'teller.json')).mode & 0o777).toBe(0o600);
    expect(readFileSync(join(dataDir, 'bridge.sqlite')).includes('PRIVATE KEY')).toBe(false);

    // Saving again without the certificate keeps the stored one.
    const again = await call('PUT', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner, { applicationId: 'app_abc123', environment: 'development' });
    expect(again.body.hasCertificate).toBe(true);
    recordFixture('teller-status', again.body);
  });

  it('serves the Teller Connect page without a sign-in, with only the public app id', async () => {
    const res = await app.inject({ method: 'GET', url: '/v1/bank-sync/teller/connect?enrollmentId=enr_wf' });
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toMatch(/text\/html/);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body).toContain('https://cdn.teller.io/connect/connect.js');
    expect(res.body).toContain('"applicationId":"app_abc123"');
    expect(res.body).toContain('"enrollmentId":"enr_wf"');
    expect(res.body).not.toContain('PRIVATE KEY');
    const bad = await app.inject({ method: 'GET', url: '/v1/bank-sync/teller/connect?enrollmentId=%3Cscript%3E' });
    expect(bad.statusCode).toBe(400);
  });

  it('rejects a bank login Teller does not recognize', async () => {
    const res = await call('POST', '/v1/bank-sync/teller/enrollments', '/v1/bank-sync/teller/enrollments', member, { accessToken: 'token_bogus', enrollmentId: 'enr_x', institution: 'Nope' });
    expect(res.status).toBe(502);
    expect(res.body.detail).toMatch(/no longer accepts/);
  });

  it('saves bank logins from Teller Connect', async () => {
    const wf = await call('POST', '/v1/bank-sync/teller/enrollments', '/v1/bank-sync/teller/enrollments', member, { accessToken: 'token_wf', enrollmentId: 'enr_wf', institution: 'Wells Fargo', userId: 'usr_1' });
    expect(wf.status).toBe(201);
    expect(wf.body).toMatchObject({ id: 'enr_wf', institution: 'Wells Fargo', status: 'ok', message: null, linkedAccounts: 0 });
    expect((await call('POST', '/v1/bank-sync/teller/enrollments', '/v1/bank-sync/teller/enrollments', member, { accessToken: 'token_cap1', enrollmentId: 'enr_cap1', institution: '' })).body.institution).toBe('Capital One');
    expect(readFileSync(join(dataDir, 'bridge.sqlite')).includes('token_wf')).toBe(false);
  });
});

describe('linking and syncing', () => {
  let simplefinChecking: string;

  it('lists the accounts across bank logins', async () => {
    const res = await call('GET', `/v1/budgets/${b}/bank-sync/teller/accounts`, '/v1/budgets/{budgetId}/bank-sync/teller/accounts', member);
    expect(res.body.items).toEqual([
      { id: 'acc_chk', enrollmentId: 'enr_wf', institution: 'Wells Fargo', name: 'Everyday Checking', type: 'depository', subtype: 'checking', lastFour: '6975', balance: 115918, currency: 'USD', linkedAccountId: null, linkedAccountName: null, flip: false },
      { id: 'acc_card', enrollmentId: 'enr_cap1', institution: 'Capital One', name: 'Discover it Card', type: 'credit', subtype: 'credit_card', lastFour: '4242', balance: 24504, currency: 'USD', linkedAccountId: null, linkedAccountName: null, flip: false },
    ]);
    recordFixture('teller-accounts', res.body);
  });

  it('previews transactions as they would import, flipped or not', async () => {
    const url = `/v1/budgets/${b}/bank-sync/teller/preview`;
    const path = '/v1/budgets/{budgetId}/bank-sync/teller/preview';
    const raw = (await call('POST', url, path, member, { tellerAccountId: 'acc_card' })).body.transactions;
    expect(raw).toEqual([
      { date: daysAgo(1), payee: 'Grocery Store', amount: 4567, pending: false },
      { date: daysAgo(2), payee: 'PAYMENT THANK YOU', amount: -20000, pending: false },
    ]);
    const flipped = (await call('POST', url, path, member, { tellerAccountId: 'acc_card', flip: true })).body.transactions;
    expect(flipped.map((t: any) => t.amount)).toEqual([-4567, 20000]);
    recordFixture('teller-preview', { transactions: flipped });
  });

  it('moves an account over from SimpleFIN without duplicating what is already there', async () => {
    await call('PUT', '/v1/bank-sync/simplefin', '/v1/bank-sync/simplefin', owner, { setupToken: sf.setupToken });
    simplefinChecking = (await call('POST', `/v1/budgets/${b}/bank-sync/simplefin/link`, '/v1/budgets/{budgetId}/bank-sync/simplefin/link', member, { externalId: 'SF-CHK' })).body.accountId;
    const before = await txnsOf(simplefinChecking);
    expect(before.some((t) => t.amount === -1234)).toBe(true);

    const res = await call('POST', `/v1/budgets/${b}/bank-sync/teller/link`, '/v1/budgets/{budgetId}/bank-sync/teller/link', member, { tellerAccountId: 'acc_chk', accountId: simplefinChecking });
    expect(res.status).toBe(201);
    // The coffee is already there from SimpleFIN (matched); payroll is new; pending and too-old ones are left out.
    expect(res.body.result).toMatchObject({ accountId: simplefinChecking, newTransactions: 1, matchedTransactions: 1, error: null, status: 'ok' });
    recordFixture('teller-link', res.body);

    const after = await txnsOf(simplefinChecking);
    expect(after.filter((t) => t.amount === -1234)).toHaveLength(1);
    expect(after.some((t) => t.amount === 250000)).toBe(true);
    expect(after.some((t) => t.amount === -5000 || t.amount === -100)).toBe(false);

    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === simplefinChecking);
    expect(acct).toMatchObject({ syncSource: 'teller', bankSyncStatus: 'ok' });
    expect(acct.lastSync).not.toBeNull();
  });

  it('links a card with its signs flipped', async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/teller/link`, '/v1/budgets/{budgetId}/bank-sync/teller/link', member, { tellerAccountId: 'acc_card', accountId: actual.accounts.card, flip: true, startDate: daysAgo(10) });
    expect(res.body.result).toMatchObject({ newTransactions: 2, error: null });
    const card = await txnsOf(actual.accounts.card);
    expect(card.filter((t) => t.amount === -4567 || t.amount === 20000)).toHaveLength(2);
  });

  it("won't feed two accounts from one Teller account", async () => {
    const res = await call('POST', `/v1/budgets/${b}/bank-sync/teller/link`, '/v1/budgets/{budgetId}/bank-sync/teller/link', member, { tellerAccountId: 'acc_card', accountId: actual.accounts.checking });
    expect(res.status).toBe(400);
  });

  it('syncs Teller accounts with Sync now, without duplicates', async () => {
    teller.enrollments[0]!.accounts[0]!.transactions.push({ id: 'txn_3', date: today, amount: '-8.00', description: 'SANDWICH' });
    const started = await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member);
    const job = await waitForJob(started.body.id);
    expect(job.status).toBe('succeeded');
    const chk = job.result.results.find((r: any) => r.accountId === simplefinChecking);
    expect(chk).toMatchObject({ newTransactions: 1, error: null, status: 'ok' });
    expect(job.result.results.find((r: any) => r.accountId === actual.accounts.card)).toMatchObject({ newTransactions: 0 });
    const again = await waitForJob((await call('POST', `/v1/budgets/${b}/accounts/${simplefinChecking}/bank-sync`, '/v1/budgets/{budgetId}/accounts/{id}/bank-sync', member)).body.id);
    expect(again.result.results).toHaveLength(1);
    expect(again.result.results[0]).toMatchObject({ newTransactions: 0 });
    expect((await txnsOf(simplefinChecking)).filter((t) => t.amount === -800)).toHaveLength(1);
  });

  it('reports a bank login that needs signing in again, and recovers once reconnected', async () => {
    teller.enrollments[0]!.disconnected = 'enrollment.disconnected.user_action.mfa_required';
    const job = await waitForJob((await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member)).body.id);
    const chk = job.result.results.find((r: any) => r.accountId === simplefinChecking);
    expect(chk).toMatchObject({ status: 'reauth-required' });
    expect(chk.error).toMatch(/verification code/);
    const overview = (await call('GET', '/v1/bank-sync', '/v1/bank-sync', viewer)).body;
    expect(overview.teller.enrollments.find((e: any) => e.id === 'enr_wf')).toMatchObject({ status: 'disconnected', linkedAccounts: 1 });
    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === simplefinChecking);
    expect(acct.bankSyncStatus).toBe('reauth-required');

    // Teller Connect with enrollmentId repairs it; the app posts the result again.
    teller.enrollments[0]!.disconnected = null;
    const fixed = await call('POST', '/v1/bank-sync/teller/enrollments', '/v1/bank-sync/teller/enrollments', member, { accessToken: 'token_wf', enrollmentId: 'enr_wf', institution: 'Wells Fargo' });
    expect(fixed.body.status).toBe('ok');
    const next = await waitForJob((await call('POST', `/v1/budgets/${b}/bank-sync`, '/v1/budgets/{budgetId}/bank-sync', member)).body.id);
    expect(next.result.results.find((r: any) => r.accountId === simplefinChecking)).toMatchObject({ status: 'ok', error: null });
  });

  it('changes the sign setting later', async () => {
    const res = await call('PATCH', `/v1/budgets/${b}/accounts/${actual.accounts.card}/teller`, '/v1/budgets/{budgetId}/accounts/{id}/teller', member, { flip: false });
    expect(res.body).toEqual({ flip: false });
    const list = (await call('GET', `/v1/budgets/${b}/bank-sync/teller/accounts?refresh=false`, '/v1/budgets/{budgetId}/bank-sync/teller/accounts', member)).body.items as any[];
    expect(list.find((a) => a.id === 'acc_card')).toMatchObject({ linkedAccountName: 'Visa', flip: false });
  });

  it('unlinks an account, keeping its transactions', async () => {
    const res = await call('POST', `/v1/budgets/${b}/accounts/${actual.accounts.card}/unlink`, '/v1/budgets/{budgetId}/accounts/{id}/unlink', member);
    expect(res.status).toBe(204);
    expect((await txnsOf(actual.accounts.card)).some((t) => t.amount === -4567)).toBe(true);
    const acct = ((await call('GET', `/v1/budgets/${b}/accounts`, '/v1/budgets/{budgetId}/accounts', member)).body.items as any[]).find((a) => a.id === actual.accounts.card);
    expect(acct.syncSource).toBeNull();
  });

  it('only owners remove a bank login; Teller is asked to revoke it', async () => {
    expect((await call('DELETE', '/v1/bank-sync/teller/enrollments/enr_wf', '/v1/bank-sync/teller/enrollments/{id}', member)).status).toBe(403);
    expect((await call('DELETE', '/v1/bank-sync/teller/enrollments/enr_wf', '/v1/bank-sync/teller/enrollments/{id}', owner)).status).toBe(204);
    expect(teller.stats.revoked).toEqual(['enr_wf']);
    const overview = (await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body;
    expect(overview.teller.enrollments.map((e: any) => e.id)).toEqual(['enr_cap1']);
    expect(store.tellerLinks(b)).toEqual([]);
  });

  it('forgets everything on reset', async () => {
    expect((await call('DELETE', '/v1/bank-sync/teller', '/v1/bank-sync/teller', owner)).status).toBe(204);
    expect((await call('GET', '/v1/bank-sync', '/v1/bank-sync', owner)).body.teller).toMatchObject({ configured: false, enrollments: [], hasCertificate: false });
  });
});
