import { join } from 'node:path';
import type { FastifyBaseLogger } from 'fastify';
import { ActualHost } from './actual/host.js';
import { HouseholdStore } from './auth/store.js';
import { loadConfig } from './config.js';
import { buildServer } from './http/server.js';
import { SetupService } from './setup.js';
import { createDeps } from './deps.js';

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
async function connect() {
  await host.start();
  log?.info({ server: host.actualServerVersion, api: host.apiVersion, compatibility: host.compatibility() }, 'connected to Actual');
  void host.warmUp(); // background: first phone request shouldn't wait for a download
}
const setup = new SetupService(config, store, host, { info: (o, m) => log?.info(o, m), warn: (o, m) => log?.warn(o, m) }, connect);
const logProxy = { info: (o: unknown, m?: string) => log?.info(o, m), warn: (o: unknown, m?: string) => log?.warn(o, m) };
const deps = createDeps(config, store, host, setup, logProxy);
const app = await buildServer(deps);
log = app.log;

if (!host.hasCredentials) {
  // No ACTUAL_PASSWORD yet: the owner enters it in the app during setup.
  app.log.info('Waiting for setup in the app to sign in to Actual');
} else {
  try {
    await connect();
  } catch (err) {
    // Keep serving: /v1/capabilities reports "unavailable" and the app shows a banner.
    app.log.error({ err }, 'could not connect to Actual; retrying in the background');
    const retry = setInterval(async () => {
      try {
        await connect();
        clearInterval(retry);
      } catch {
        /* keep retrying */
      }
    }, 30_000);
  }
}

const setupCode = setup.prepare();
if (setupCode) {
  // Printed plainly so `docker compose logs bridge` shows it at a glance.
  const lines = [
    '',
    '============================================================',
    '  Centsible bridge: first-time setup',
    `  1. In the app, choose "Set up a new bridge" and enter ${config.publicUrl}`,
    `  2. Setup code: ${setupCode}`,
    '  The code works once, until the first owner is created.',
    '============================================================',
    '',
  ];
  process.stdout.write(`${lines.join('\n')}\n`);
}

deps.scheduler.start(); // background bank sync, if the owner turned it on

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
