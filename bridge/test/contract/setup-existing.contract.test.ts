import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startActual } from '../support/actual-server.js';
import { call, startUnclaimedBridge } from '../support/setup-bridge.js';

// Actual already has a password (you've used it on the web), the bridge doesn't know it.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'setup-existing');
let actual: Awaited<ReturnType<typeof startActual>>;
let bridge: Awaited<ReturnType<typeof startUnclaimedBridge>>;
const claim = { setupCode: 'TESTCODE', displayName: 'Jo', deviceName: 'Pixel 9', platform: 'android' };

beforeAll(async () => {
  actual = await startActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  await fetch(`${actual.url}/account/bootstrap`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ password: 'my-actual-pass' }) });
  bridge = await startUnclaimedBridge(join(root, 'bridge'), actual.url);
});

afterAll(async () => {
  await bridge?.app.close();
  await bridge?.host.stop();
  bridge?.store.close();
  await actual?.stop();
});

describe('first-run setup with an existing Actual server', () => {
  it('asks for the existing password', async () => {
    const res = await call(bridge.app, 'GET', '/v1/setup', '/v1/setup');
    expect(res.body).toEqual({ needsOwner: true, actual: 'needs-login' });
  });

  it('rejects the wrong Actual password without using up the code', async () => {
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'not-my-password' });
    expect(res.status).toBe(400);
    expect(res.body.detail).toMatch(/isn't your Actual server's password/);
    expect(bridge.setup.needsOwner).toBe(true);
  });

  it('signs in with the right one', async () => {
    const res = await call(bridge.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'my-actual-pass' });
    expect(res.status).toBe(200);
    expect(bridge.host.connected).toBe(true);
    const budgets = await call(bridge.app, 'GET', '/v1/budgets', '/v1/budgets', undefined, res.body.accessToken);
    expect(budgets.body.items).toEqual([]); // a new server has none yet
  });

  it('reports an unreachable Actual server clearly', async () => {
    const other = await startUnclaimedBridge(join(root, 'bridge-2'), 'http://127.0.0.1:9');
    const res = await call(other.app, 'GET', '/v1/setup', '/v1/setup');
    expect(res.body).toEqual({ needsOwner: true, actual: 'unreachable' });
    const claimed = await call(other.app, 'POST', '/v1/setup/claim', '/v1/setup/claim', { ...claim, actualPassword: 'x'.repeat(10) });
    expect(claimed.status).toBe(503);
    expect(claimed.body.detail).toContain('http://127.0.0.1:9');
    await other.app.close();
    other.store.close();
  });
});
