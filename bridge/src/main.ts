import { join } from 'node:path';
import type { FastifyBaseLogger } from 'fastify';
import { BudgetOps } from './actual/budget-ops.js';
import { AccountOps } from './actual/account-ops.js';
import { PlanningOps } from './actual/planning-ops.js';
import { ReportOps } from './actual/report-ops.js';
import { JobStore } from './jobs.js';
import { StructureOps } from './actual/structure-ops.js';
import { TransactionOps } from './actual/transaction-ops.js';
import { ActualHost } from './actual/host.js';
import { HouseholdStore } from './auth/store.js';
import { loadConfig } from './config.js';
import { buildServer } from './http/server.js';

const config = loadConfig();
const store = new HouseholdStore(join(config.dataDir, 'bridge.sqlite'), {
  accessSec: config.accessTokenTtlSec,
  refreshSec: config.refreshTokenTtlSec,
});

// The host needs a logger before the server exists; reuse Fastify's once built.
let log: FastifyBaseLogger | undefined;
const host = new ActualHost(config, {
  debug: (o, m) => log?.debug(o, m),
  info: (o, m) => log?.info(o, m),
  warn: (o, m) => log?.warn(o, m),
});
const app = await buildServer({ config, store, host, ops: new BudgetOps(host), transactions: new TransactionOps(host), structure: new StructureOps(host), planning: new PlanningOps(host), accountOps: new AccountOps(host), reports: new ReportOps(host), jobs: new JobStore() });
log = app.log;

try {
  await host.start();
  app.log.info({ server: host.actualServerVersion, api: host.apiVersion, compatibility: host.compatibility() }, 'connected to Actual');
} catch (err) {
  // Keep serving: /v1/capabilities reports "unavailable" and the app shows a banner.
  app.log.error({ err }, 'could not connect to Actual; retrying in the background');
  const retry = setInterval(async () => {
    try {
      await host.start();
      clearInterval(retry);
      app.log.info('connected to Actual');
    } catch {
      /* keep retrying */
    }
  }, 30_000);
}

if (store.listMembers().length === 0) {
  app.log.warn('No household members yet. Create the first owner with: node dist/admin/cli.js add-member --name <you> --role owner --pair');
}

setInterval(() => store.pruneIdempotency(), 6 * 3600 * 1000).unref();

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.once(sig, async () => {
    app.log.info({ sig }, 'shutting down');
    await app.close();
    await host.stop();
    store.close();
    process.exit(0);
  });
}

await app.listen({ port: config.port, host: config.host });
