import { mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { buildServer } from '../../src/http/server.js';
import { createDeps } from '../../src/deps.js';
import { SetupService } from '../../src/setup.js';
import { expectContract } from './contract.js';

const silent = { debug() {}, info() {}, warn() {} };

/** A bridge as after a first `docker compose up`: no owner, no Actual password in .env. */
export async function startUnclaimedBridge(dataDir: string, actualUrl: string, setupCode = 'TEST-CODE') {
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  const config: BridgeConfig = {
    port: 0,
    host: '127.0.0.1',
    dataDir,
    publicUrl: 'https://budget-api.example.com',
    trustProxy: false,
    actual: { serverUrl: actualUrl, budgetPasswords: {} },
    setupCode,
    syncMaxAgeMs: 0,
    accessTokenTtlSec: 3600,
    refreshTokenTtlSec: 3600,
    logLevel: 'silent',
  };
  const store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  const host = new ActualHost(config, silent);
  const setup = new SetupService(config, store, host, silent, () => host.start());
  setup.prepare();
  const app = await buildServer(
    createDeps(config, store, host, setup, silent),
    { logger: false },
  );
  return { config, store, host, setup, app };
}

export async function call(app: FastifyInstance, method: string, url: string, path: string, body?: unknown, token?: string) {
  const res = await app.inject({
    method: method as 'GET',
    url,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    ...(body !== undefined ? { payload: body as object } : {}),
  });
  return { status: res.statusCode, body: expectContract(method, path, res) as Record<string, any> };
}
