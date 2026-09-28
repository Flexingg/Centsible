import { createServer, type Server } from 'node:http';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, rmSync } from 'node:fs';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ActualHost } from '../../src/actual/host.js';
import { BRIDGE_VERSION } from '../../src/actual/versions.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { createDeps } from '../../src/deps.js';
import { buildServer, type Deps } from '../../src/http/server.js';
import { ServerOps } from '../../src/server-ops.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Server health, one-tap updates (against a stand-in for Watchtower's HTTP API) and
// backups with restore, against a real Actual server. GitHub is faked too.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'server');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let deps: Deps;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let updater: Server;
const updaterCalls: string[] = [];
let config: BridgeConfig;

function tokenFor(role: 'owner' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [actual.budgetId] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, path, res) as Record<string, any> };
}

/** GitHub's "latest release" answers for Centsible and for Actual. */
const fakeGitHub = (async (url: string | URL, init?: RequestInit) => {
  const u = String(url);
  if (!u.startsWith('https://api.github.com/')) return fetch(url, init); // the updater is a real local server
  const body = u.includes('actualbudget')
    ? { tag_name: 'v26.10.0' }
    : {
        tag_name: 'v0.2.99', html_url: 'https://github.com/Flexingg/Centsible/releases/tag/v0.2.99', published_at: '2026-09-28T12:00:00Z', body: 'Notes',
        assets: [{ name: 'docker-compose.yml', browser_download_url: 'https://x/compose' }, { name: 'centsible-0.2.99.apk', browser_download_url: 'https://x/app.apk' }],
      };
  return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
}) as typeof fetch;

beforeAll(async () => {
  updater = createServer((req, res) => {
    updaterCalls.push(`${req.method} ${req.url} ${req.headers.authorization}`);
    setTimeout(() => res.writeHead(req.headers.authorization === 'Bearer secret-token' ? 200 : 401).end(), 50);
  });
  await new Promise<void>((r) => updater.listen(0, '127.0.0.1', r));

  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  const dataDir = join(root, 'bridge');
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  config = {
    port: 0, host: '127.0.0.1', dataDir, publicUrl: 'https://budget-api.example.com', trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0, accessTokenTtlSec: 3600, refreshTokenTtlSec: 3600, logLevel: 'silent',
  };
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  deps = createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent);
  deps.server = new ServerOps(config, host, store, silent, fakeGitHub);
  app = await buildServer(deps, { logger: false });
  owner = tokenFor('owner');
  viewer = tokenFor('viewer');
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
  await new Promise<void>((r) => updater?.close(() => r()));
});

describe('server status', () => {
  it('reports versions, the newest release with the app, and what can be updated', async () => {
    const res = await call('GET', '/v1/server', '/v1/server', viewer);
    expect(res.status).toBe(200);
    const s = res.body;
    expect(s.bridge).toMatchObject({ version: BRIDGE_VERSION, latest: '0.2.99', updateAvailable: true });
    expect(s.actual).toMatchObject({ pairedVersion: '26.9.0', compatibility: 'ok', newestRelease: '26.10.0', connected: true });
    expect(s.actual.version).toMatch(/^26\.9\./);
    expect(s.app).toMatchObject({ version: '0.2.99', versionCode: 99, apkUrl: 'https://x/app.apk' });
    expect(s.updater).toMatchObject({ configured: false });
    expect(s.disk.free).toBeGreaterThan(0);
    recordFixture('server-status', s);
  });
});

describe('one-tap updates', () => {
  it('explains how to set up the updater when it is missing', async () => {
    expect((await call('POST', '/v1/server/update', '/v1/server/update', viewer)).status).toBe(403);
    const res = await call('POST', '/v1/server/update', '/v1/server/update', owner);
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/docker-compose.yml/);
  });

  it('asks the updater, with its token, and remembers the outcome', async () => {
    const port = (updater.address() as { port: number }).port;
    deps.server = new ServerOps({ ...config, updater: { url: `http://127.0.0.1:${port}`, token: 'secret-token' } }, host, store, silent, fakeGitHub);
    await app.close();
    app = await buildServer(deps, { logger: false });
    const res = await call('POST', '/v1/server/update', '/v1/server/update', owner);
    expect(res.status).toBe(202);
    expect(updaterCalls).toEqual(['GET /v1/update Bearer secret-token']);
    const s = (await call('GET', '/v1/server', '/v1/server', viewer)).body;
    expect(s.updater).toMatchObject({ configured: true, lastUpdatedFrom: BRIDGE_VERSION, lastResult: 'done' });
  });

  it('says so when the updater refuses', async () => {
    const port = (updater.address() as { port: number }).port;
    deps.server = new ServerOps({ ...config, updater: { url: `http://127.0.0.1:${port}`, token: 'wrong' } }, host, store, silent, fakeGitHub);
    await app.close();
    app = await buildServer(deps, { logger: false });
    const res = await call('POST', '/v1/server/update', '/v1/server/update', owner);
    expect(res.status).toBe(502);
    expect(res.body.detail).toMatch(/HTTP 401/);
  });
});

describe('backups', () => {
  let backupId: string;

  it('are daily by default, and owners only', async () => {
    expect((await call('GET', '/v1/server/backups', '/v1/server/backups', viewer)).status).toBe(403);
    const o = (await call('GET', '/v1/server/backups', '/v1/server/backups', owner)).body;
    expect(o).toMatchObject({ intervalHours: 24, keep: 14, items: [], lastRunAt: null });
  });

  it('back up every budget and the household', async () => {
    const res = await call('POST', '/v1/server/backups', '/v1/server/backups', owner);
    expect(res.status).toBe(201);
    backupId = res.body.id;
    expect(res.body).toMatchObject({ trigger: 'manual', household: true, skipped: [] });
    expect(res.body.budgets).toEqual([expect.objectContaining({ budgetId: actual.budgetId, name: 'Household' })]);
    expect(res.body.size).toBeGreaterThan(1000);
    const o = (await call('GET', '/v1/server/backups', '/v1/server/backups', owner)).body;
    expect(o.items.map((b: any) => b.id)).toEqual([backupId]);
    recordFixture('backups', o);
  });

  it('can be downloaded: a budget is Actual’s own .zip', async () => {
    const file = (await call('GET', '/v1/server/backups', '/v1/server/backups', owner)).body.items[0].budgets[0].file;
    const res = await app.inject({ method: 'GET', url: `/v1/server/backups/${backupId}/files/${file}`, headers: { authorization: `Bearer ${owner}` } });
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toBe('application/zip');
    expect(res.rawPayload.subarray(0, 2).toString()).toBe('PK');
    const bad = await app.inject({ method: 'GET', url: `/v1/server/backups/${backupId}/files/..%2Fbridge.sqlite`, headers: { authorization: `Bearer ${owner}` } });
    expect(bad.statusCode).toBe(404);
  });

  it('restore as a new budget beside the original', async () => {
    const res = await call('POST', `/v1/server/backups/${backupId}/restore`, '/v1/server/backups/{id}/restore', owner, { budgetId: actual.budgetId });
    expect(res.status).toBe(201);
    expect(res.body.name).toMatch(/^Household \(restored \d{4}-\d{2}-\d{2}\)$/);
    expect(res.body.budgetId).not.toBe(actual.budgetId);
    const budgets = (await call('GET', '/v1/budgets', '/v1/budgets', owner)).body.items as any[];
    expect(budgets.map((b) => b.name).sort()).toEqual(['Household', res.body.name]);
    const copy = (await call('GET', `/v1/budgets/${res.body.budgetId}/transactions?limit=100`, '/v1/budgets/{budgetId}/transactions', owner)).body.items as any[];
    const original = (await call('GET', `/v1/budgets/${actual.budgetId}/transactions?limit=100`, '/v1/budgets/{budgetId}/transactions', owner)).body.items as any[];
    expect(copy.length).toBe(original.length);
  });

  it('follow the schedule and keep only the newest', async () => {
    const o = (await call('PUT', '/v1/server/backups/settings', '/v1/server/backups/settings', owner, { keep: 1, intervalHours: 168 })).body;
    expect(o).toMatchObject({ keep: 1, intervalHours: 168 });
    expect((await call('PUT', '/v1/server/backups/settings', '/v1/server/backups/settings', owner, { intervalHours: 5 })).status).toBe(400);
    await new Promise((r) => setTimeout(r, 1100)); // backup ids are per second
    await call('POST', '/v1/server/backups', '/v1/server/backups', owner);
    const items = (await call('GET', '/v1/server/backups', '/v1/server/backups', owner)).body.items as any[];
    expect(items.length).toBe(1);
    expect(items[0].id).not.toBe(backupId);
    expect(await deps.backups.tick()).toBe(false); // a week isn't up
  });

  it('can be deleted', async () => {
    const id = (await call('GET', '/v1/server/backups', '/v1/server/backups', owner)).body.items[0].id;
    expect((await call('DELETE', `/v1/server/backups/${id}`, '/v1/server/backups/{id}', owner)).status).toBe(204);
    expect((await call('DELETE', `/v1/server/backups/${id}`, '/v1/server/backups/{id}', owner)).status).toBe(404);
  });
});
