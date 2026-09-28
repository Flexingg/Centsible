import { statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { loadConfig } from '../../src/config.js';
import { savedActualPasswordPath } from '../../src/setup.js';
import { startActual } from '../support/actual-server.js';
import { recordFixture } from '../support/contract.js';
import { call, startUnclaimedBridge } from '../support/setup-bridge.js';

// A brand-new Actual server (no password yet) and a bridge with no owner and no
// ACTUAL_PASSWORD: the app sets up both.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'setup-new');
let actual: Awaited<ReturnType<typeof startActual>>;
let bridge: Awaited<ReturnType<typeof startUnclaimedBridge>>;
let ownerToken: string;
const claim = { setupCode: 'test-code', displayName: 'Jo', deviceName: 'Pixel 9', platform: 'android' };

beforeAll(async () => {
  actual = await startActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  bridge = await startUnclaimedBridge(join(root, 'bridge'), actual.url);
});

afterAll(async () => {
  await bridge?.app.close();
  await bridge?.host.stop();
  bridge?.store.close();
  await actual?.stop();
});

describe('first-run setup on a new Actual server', () => {
  it('reports that it needs an owner and an Actual password', async () => {
    const res = await call(bridge.app, 'GET', '/v1/setup', '/v1/setup');
    expect(res.body).toEqual({ needsOwner: true, actual: 'needs-password' });
    recordFixture('setup-status', res.body);
  });

  it('rejects a wrong setup code', async () => {
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, setupCode: 'WRONG-CODE', actualPassword: 'household-pass' });
    expect(res.status).toBe(401);
  });

  it('insists on a real Actual password', async () => {
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'short' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/at least 8/);
  });

  it('sets the Actual password, creates the owner and signs this phone in', async () => {
    // Case and dashes don't matter when typing the code.
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'household-pass' });
    expect(res.status).toBe(200);
    expect(res.body.member).toMatchObject({ displayName: 'Jo', role: 'owner' });
    expect(res.body.device).toMatchObject({ name: 'Pixel 9', platform: 'android' });
    ownerToken = res.body.accessToken;
    expect(bridge.host.connected).toBe(true);

    const boot = (await (await fetch(`${actual.url}/account/needs-bootstrap`)).json()) as { data: { bootstrapped: boolean } };
    expect(boot.data.bootstrapped).toBe(true);
  });

  it('keeps the password for restarts, readable only by the bridge', () => {
    const file = savedActualPasswordPath(bridge.config.dataDir);
    expect(statSync(file).mode & 0o777).toBe(0o600);
    const reloaded = loadConfig({ BRIDGE_DATA_DIR: bridge.config.dataDir, BRIDGE_PUBLIC_URL: 'https://x.example.com', ACTUAL_SERVER_URL: actual.url });
    expect(reloaded.actual.password).toBe('household-pass');
  });

  it('says nothing more once it has an owner', async () => {
    const res = await call(bridge.app, 'GET', '/v1/setup', '/v1/setup');
    expect(res.body).toEqual({ needsOwner: false });
  });

  it('cannot be claimed twice', async () => {
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'household-pass' });
    expect(res.status).toBe(403);
  });

  it('lets the owner create the first budget', async () => {
    const created = await call(bridge.app, 'POST', '/v1/budgets', '/v1/budgets', { name: 'Our Budget' }, ownerToken);
    expect(created.status).toBe(201);
    expect(created.body).toMatchObject({ name: 'Our Budget', encrypted: false });
    recordFixture('budget-created', created.body);

    const list = await call(bridge.app, 'GET', '/v1/budgets', '/v1/budgets', undefined, ownerToken);
    expect(list.body.items).toContainEqual(created.body);
    const accounts = await call(bridge.app, 'GET', `/v1/budgets/${created.body.id}/accounts`, '/v1/budgets/{budgetId}/accounts', undefined, ownerToken);
    expect(accounts.body.items).toEqual([]);
  });

  it('only lets owners create budgets', async () => {
    const viewer = bridge.store.createMember({ displayName: 'Riley', role: 'viewer' });
    const { code } = bridge.store.createPairingCode(viewer.id, null);
    const paired = bridge.store.redeemPairingCode(code, { name: 'Phone', platform: 'android' })!;
    const res = await call(bridge.app, 'POST', '/v1/budgets', '/v1/budgets', { name: 'Nope' }, paired.tokens.accessToken);
    expect(res.status).toBe(403);
  });
});
