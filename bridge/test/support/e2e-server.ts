// Boots Actual plus the real bridge on a real port, for the Android end-to-end suite
// (android/core/engine-bridge ... EndToEndTest). Prints one JSON line once ready, then
// serves until killed.
//
//   npx tsx test/support/e2e-server.ts [port]           seeded budget, owner invite
//     -> {"bridgeUrl","pairingUri","budgetId"}
//   npx tsx test/support/e2e-server.ts [port] --fresh   first run: new Actual, no owner
//     -> {"bridgeUrl","setupCode"}
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import { pairingUri } from '../../src/auth/pairing.js';
import type { BridgeConfig } from '../../src/config.js';
import { buildServer } from '../../src/http/server.js';
import { createDeps } from '../../src/deps.js';
import { SetupService } from '../../src/setup.js';
import { startActual, startSeededActual } from './actual-server.js';
import { day, startFakeSimpleFin } from './fake-simplefin.js';

const port = Number(process.argv[2] ?? 8787);
const fresh = process.argv.includes('--fresh');
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', fresh ? 'e2e-fresh' : 'e2e');
rmSync(root, { recursive: true, force: true });
const silent = { debug() {}, info() {}, warn() {} };

const actualPort = 5100 + Math.floor(Math.random() * 800);
const actual = fresh ? { ...(await startActual(join(root, 'actual'), actualPort)), password: undefined, budgetId: undefined } : await startSeededActual(join(root, 'actual'), actualPort);
const dataDir = join(root, 'bridge');
mkdirSync(join(dataDir, 'actual'), { recursive: true });
const config: BridgeConfig = {
  port,
  host: '127.0.0.1',
  dataDir,
  publicUrl: `http://127.0.0.1:${port}`,
  trustProxy: false,
  actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
  setupCode: fresh ? 'E2E-SETUP' : undefined,
  cfAccess: { clientId: 'cf-id.access', clientSecret: 'cf-secret' }, // exercises the header path
  syncMaxAgeMs: 0,
  accessTokenTtlSec: 3600,
  refreshTokenTtlSec: 3600,
  logLevel: 'silent',
};
const store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
const host = new ActualHost(config, silent);
if (!fresh) await host.start(); // fresh: the app's setup signs the bridge in
const setup = new SetupService(config, store, host, silent, () => host.start());
const app = await buildServer(
  createDeps(config, store, host, setup, silent),
  { logger: false },
);
await app.listen({ port, host: '127.0.0.1' });

// console.log is silenced once @actual-app/api loads, so write to stdout directly.
if (fresh) {
  process.stdout.write(`${JSON.stringify({ bridgeUrl: config.publicUrl, setupCode: setup.prepare() })}\n`);
} else {
  const owner = store.createMember({ displayName: 'Jo', role: 'owner' });
  const { code } = store.createPairingCode(owner.id, null);
  // A stand-in SimpleFIN Bridge for the bank sync steps.
  const today = new Date().toISOString().slice(0, 10);
  const simplefin = await startFakeSimpleFin([
    {
      id: 'SF-CHK', name: 'Everyday Checking', org: { domain: 'bank.example', name: 'Example Bank' }, balance: '1234.56',
      transactions: [{ id: 'T1', posted: day(today), amount: '-12.34', description: 'COFFEE SHOP 123', payee: 'Coffee Shop' }],
    },
  ]);
  process.stdout.write(`${JSON.stringify({ bridgeUrl: config.publicUrl, pairingUri: pairingUri(config, code), budgetId: actual.budgetId, simplefinToken: simplefin.setupToken })}\n`);
}

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.once(sig, async () => {
    await app.close();
    await host.stop();
    store.close();
    await actual.stop();
    process.exit(0);
  });
}
