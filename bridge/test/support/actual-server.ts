import { execFile, spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, openSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));

export type SeededActual = {
  url: string;
  password: string;
  budgetId: string;
  accounts: { checking: string; card: string };
  categories: Record<string, string>;
  month: string;
  stop: () => Promise<void>;
};

/**
 * Boots the real @actual-app/sync-server pinned in package.json (the same version the
 * bridge's @actual-app/api targets), bootstraps it, and seeds a household budget.
 * This is the upgrade gate: bump both packages, and these tests say whether it's safe.
 */
export async function startSeededActual(root: string, port: number): Promise<SeededActual> {
  rmSync(root, { recursive: true, force: true });
  const serverData = join(root, 'server');
  const seedData = join(root, 'seed');
  mkdirSync(serverData, { recursive: true });
  mkdirSync(seedData, { recursive: true });

  const bin = join(dirname(require.resolve('@actual-app/sync-server/package.json')), 'build/bin/actual-server.js');
  const logFile = join(root, 'actual-server.log');
  const logFd = openSync(logFile, 'w');
  const child: ChildProcess = spawn(process.execPath, [bin], {
    // IPv4 loopback: some CI sandboxes have no IPv6.
    env: { ...process.env, NODE_ENV: 'production', ACTUAL_HOSTNAME: '127.0.0.1', ACTUAL_PORT: String(port), ACTUAL_DATA_DIR: serverData },
    stdio: ['ignore', logFd, logFd],
  });

  const url = `http://127.0.0.1:${port}`;
  try {
    await waitFor(async () => (await fetch(`${url}/info`)).ok, 60_000);
  } catch (err) {
    child.kill();
    throw new Error(`actual-server did not start:\n${readFileSync(logFile, 'utf8').slice(-2000)}`);
  }

  const password = 'household-test-pass';
  const boot = await fetch(`${url}/account/bootstrap`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (!boot.ok) throw new Error(`bootstrap failed: ${boot.status} ${await boot.text()}`);

  const now = new Date();
  const month = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const { stdout } = await promisify(execFile)(process.execPath, [join(here, 'seed-budget.mjs'), url, password, seedData, month], {
    maxBuffer: 64 * 1024 * 1024,
  });
  const seeded = JSON.parse(stdout.trim().split('\n').pop()!);

  return {
    url,
    password,
    month,
    ...seeded,
    stop: async () => {
      child.kill();
      await new Promise((r) => child.once('exit', r));
    },
  };
}

async function waitFor(check: () => Promise<boolean>, timeoutMs: number) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      if (await check()) return;
    } catch {
      /* not up yet */
    }
    if (Date.now() > deadline) throw new Error('actual-server did not start');
    await new Promise((r) => setTimeout(r, 200));
  }
}
