import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';

export type BridgeConfig = {
  port: number;
  host: string;
  dataDir: string;
  /** Public URL the phone uses (your Cloudflare hostname). Embedded in pairing QR codes. */
  publicUrl: string;
  trustProxy: boolean;
  actual: {
    serverUrl: string;
    password?: string;
    sessionToken?: string;
    /** E2E encryption passwords keyed by budget sync id. */
    budgetPasswords: Record<string, string>;
  };
  /** Optional Cloudflare Access service token, handed to devices inside the pairing QR. */
  cfAccess?: { clientId: string; clientSecret: string };
  /** Reads re-sync with the Actual server when the last sync is older than this. */
  syncMaxAgeMs: number;
  accessTokenTtlSec: number;
  refreshTokenTtlSec: number;
  logLevel: string;
};

function required(env: NodeJS.ProcessEnv, key: string): string {
  const v = env[key];
  if (!v) throw new Error(`Missing required env var ${key}`);
  return v;
}

function parseBudgetPasswords(raw: string | undefined): Record<string, string> {
  if (!raw) return {};
  // Format: "<syncId>=<password>,<syncId>=<password>"
  return Object.fromEntries(
    raw
      .split(',')
      .map((pair) => pair.split('='))
      .filter((kv): kv is [string, string] => kv.length === 2 && !!kv[0] && !!kv[1]),
  );
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
  const dataDir = resolve(env.BRIDGE_DATA_DIR ?? './data');
  mkdirSync(join(dataDir, 'actual'), { recursive: true });

  const password = env.ACTUAL_PASSWORD;
  const sessionToken = env.ACTUAL_SESSION_TOKEN;
  if (!password && !sessionToken) {
    throw new Error('Set ACTUAL_PASSWORD (password login) or ACTUAL_SESSION_TOKEN (OIDC / multi-user)');
  }

  const cfId = env.CF_ACCESS_CLIENT_ID;
  const cfSecret = env.CF_ACCESS_CLIENT_SECRET;

  return {
    port: Number(env.BRIDGE_PORT ?? 8787),
    host: env.BRIDGE_HOST ?? '0.0.0.0',
    dataDir,
    publicUrl: required(env, 'BRIDGE_PUBLIC_URL').replace(/\/+$/, ''),
    trustProxy: env.BRIDGE_TRUST_PROXY === 'true',
    actual: {
      serverUrl: required(env, 'ACTUAL_SERVER_URL').replace(/\/+$/, ''),
      password,
      sessionToken,
      budgetPasswords: parseBudgetPasswords(env.ACTUAL_BUDGET_PASSWORDS),
    },
    cfAccess: cfId && cfSecret ? { clientId: cfId, clientSecret: cfSecret } : undefined,
    syncMaxAgeMs: Number(env.BRIDGE_SYNC_MAX_AGE_MS ?? 5000),
    accessTokenTtlSec: Number(env.BRIDGE_ACCESS_TTL_SEC ?? 3600),
    refreshTokenTtlSec: Number(env.BRIDGE_REFRESH_TTL_SEC ?? 90 * 24 * 3600),
    logLevel: env.LOG_LEVEL ?? 'info',
  };
}
