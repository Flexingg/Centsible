// Boots a seeded Actual server plus the real bridge on a real port, for the Android
// end-to-end suite (android/core/engine-bridge ... EndToEndTest). Prints one JSON line
// {"bridgeUrl","pairingUri","budgetId"} once ready, then serves until killed.
//
//   npx tsx test/support/e2e-server.ts [port]
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BudgetOps } from '../../src/actual/budget-ops.js';
import { AccountOps } from '../../src/actual/account-ops.js';
import { PlanningOps } from '../../src/actual/planning-ops.js';
import { ReportOps } from '../../src/actual/report-ops.js';
import { StructureOps } from '../../src/actual/structure-ops.js';
import { TransactionOps } from '../../src/actual/transaction-ops.js';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import { pairingUri } from '../../src/auth/pairing.js';
import type { BridgeConfig } from '../../src/config.js';
import { buildServer } from '../../src/http/server.js';
import { JobStore } from '../../src/jobs.js';
import { startSeededActual } from './actual-server.js';

const port = Number(process.argv[2] ?? 8787);
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'e2e');
rmSync(root, { recursive: true, force: true });
const silent = { debug() {}, info() {}, warn() {} };

const actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
const dataDir = join(root, 'bridge');
mkdirSync(join(dataDir, 'actual'), { recursive: true });
const config: BridgeConfig = {
  port,
  host: '127.0.0.1',
  dataDir,
  publicUrl: `http://127.0.0.1:${port}`,
  trustProxy: false,
  actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
  cfAccess: { clientId: 'cf-id.access', clientSecret: 'cf-secret' }, // exercises the header path
  syncMaxAgeMs: 0,
  accessTokenTtlSec: 3600,
  refreshTokenTtlSec: 3600,
  logLevel: 'silent',
};
const store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
const host = new ActualHost(config, silent);
await host.start();
const app = await buildServer(
  { config, store, host, ops: new BudgetOps(host), transactions: new TransactionOps(host), structure: new StructureOps(host), planning: new PlanningOps(host), accountOps: new AccountOps(host), reports: new ReportOps(host), jobs: new JobStore() },
  { logger: false },
);
await app.listen({ port, host: '127.0.0.1' });

const owner = store.createMember({ displayName: 'Jo', role: 'owner' });
const { code } = store.createPairingCode(owner.id, null);
// console.log is silenced once @actual-app/api loads, so write to stdout directly.
process.stdout.write(`${JSON.stringify({ bridgeUrl: config.publicUrl, pairingUri: pairingUri(config, code), budgetId: actual.budgetId })}\n`);

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.once(sig, async () => {
    await app.close();
    await host.stop();
    store.close();
    await actual.stop();
    process.exit(0);
  });
}
