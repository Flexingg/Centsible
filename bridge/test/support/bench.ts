// Times every read the app does against a large budget (12k transactions, 24 months).
//   npx tsx test/support/bench.ts [transactionCount]
import { execFile } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { startActual } from './actual-server.js';
import { startUnclaimedBridge } from './setup-bridge.js';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..', '.test-data', 'bench');
const count = Number(process.argv[2] ?? 12000);
const log = (s: string) => process.stdout.write(`${s}\n`);

const actual = await startActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
await fetch(`${actual.url}/account/bootstrap`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ password: 'bench-pass' }) });
const seedDir = join(root, 'seed');
rmSync(seedDir, { recursive: true, force: true });
mkdirSync(seedDir, { recursive: true });
let t = Date.now();
const { stdout } = await promisify(execFile)(process.execPath, [join(here, 'seed-large.mjs'), actual.url, 'bench-pass', seedDir, String(count)], { maxBuffer: 1 << 26 });
const { budgetId } = JSON.parse(stdout.trim().split('\n').pop()!);
log(`seeded ${count} transactions in ${((Date.now() - t) / 1000).toFixed(1)}s`);

const bridge = await startUnclaimedBridge(join(root, 'bridge'), actual.url);
bridge.config.actual.password = 'bench-pass';
await bridge.host.start();
const owner = bridge.store.createMember({ displayName: 'Jo', role: 'owner' });
const { code } = bridge.store.createPairingCode(owner.id, null);
const token = bridge.store.redeemPairingCode(code, { name: 'bench', platform: 'android' })!.tokens.accessToken;

const now = new Date();
const month = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
const yearAgo = `${now.getFullYear() - 1}-${String(now.getMonth() + 1).padStart(2, '0')}`;
const b = `/v1/budgets/${budgetId}`;

async function time(url: string) {
  const res = await bridge.app.inject({ method: 'GET', url, headers: { authorization: `Bearer ${token}` } });
  if (res.statusCode !== 200) throw new Error(`${url}: ${res.statusCode} ${res.body.slice(0, 200)}`);
  return res;
}
// First call opens and caches the budget; not what the app sees after that.
t = Date.now();
await time(`${b}/accounts`);
log(`first open: ${Date.now() - t} ms`);

const first = JSON.parse((await time(`${b}/transactions?limit=50`)).body);
let cursor: string | undefined = first.nextCursor;
for (let i = 0; i < 20 && cursor; i++) cursor = JSON.parse((await time(`${b}/transactions?limit=50&cursor=${encodeURIComponent(cursor)}`)).body).nextCursor;
const deepCursor = cursor;

const cases: [string, string][] = [
  ['accounts (balances)', `${b}/accounts`],
  ['budget month', `${b}/months/${month}`],
  ['transactions, first page', `${b}/transactions?limit=50`],
  ['transactions, page 21', `${b}/transactions?limit=50&cursor=${encodeURIComponent(deepCursor ?? '')}`],
  ['search "coffee"', `${b}/transactions?limit=50&q=coffee`],
  ['uncategorized', `${b}/transactions?limit=50&uncategorized=true`],
  ['one account', `${b}/transactions?limit=50&accountId=${JSON.parse((await time(`${b}/accounts`)).body).items[2].id}`],
  ['cash flow, 12 months', `${b}/reports/cash-flow?start=${yearAgo}&end=${month}`],
  ['spending, 12 months', `${b}/reports/spending?start=${yearAgo}&end=${month}`],
  ['net worth, 12 months', `${b}/reports/net-worth?months=12`],
  ['merchant stats', `${b}/payees/stats`],
  ['payees', `${b}/payees`],
  ['categories', `${b}/category-groups`],
  ['schedules', `${b}/schedules`],
];
log('\n| request | median ms | max ms |\n|---|---:|---:|');
for (const [name, url] of cases) {
  const runs: number[] = [];
  for (let i = 0; i < 7; i++) {
    const s = performance.now();
    await time(url);
    runs.push(performance.now() - s);
  }
  runs.sort((x, y) => x - y);
  log(`| ${name} | ${runs[3]!.toFixed(0)} | ${runs[6]!.toFixed(0)} |`);
}
// After a restart the budget loads from the bridge's local copy and only syncs changes.
await bridge.host.stop();
await bridge.host.start();
t = Date.now();
await time(`${b}/accounts`);
log(`\nopen after a bridge restart: ${Date.now() - t} ms`);

await bridge.app.close();
await bridge.host.stop();
bridge.store.close();
await actual.stop();
