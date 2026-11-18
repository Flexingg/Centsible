// Starts Actual's server and the Centsible bridge in one Home Assistant app.
//
// Home Assistant writes the app's options to /data/options.json and keeps /data (included
// in its backups). Layout:
//   /data/actual   Actual's server files (what ./actual-data was with Docker Compose)
//   /data/bridge   the bridge's household database, Teller certificate, backups
// Moving from Docker Compose: copy actual-data and bridge-data into the app's config
// folder under import/ (/addon_configs/<id>_centsible/import/), then start the app. They
// are copied in once, only while /data is still empty.
//
// Both run as the unprivileged "node" user. If either stops, this exits so Home
// Assistant's watchdog restarts the app.
import { spawn } from 'node:child_process';
import { chownSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, statSync } from 'node:fs';
import { join } from 'node:path';

const DATA = process.env.CENTSIBLE_DATA ?? '/data';
const CONFIG = process.env.CENTSIBLE_CONFIG ?? '/config';
const APP = process.env.CENTSIBLE_APP ?? '/app';
const ACTUAL_PORT = 5006;
const BRIDGE_PORT = 8787;
const NODE_UID = 1000;
const NODE_GID = 1000;

const log = (msg) => process.stdout.write(`[centsible] ${msg}\n`);

function readOptions() {
  const file = join(DATA, 'options.json');
  if (!existsSync(file)) return {};
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (err) {
    log(`Couldn't read the app options (${err.message}); using defaults.`);
    return {};
  }
}

const isEmpty = (dir) => !existsSync(dir) || readdirSync(dir).length === 0;

/** Copies a folder from Docker Compose in once, if this app has no data of its own yet. */
function importOnce(name, target, marker) {
  const from = join(CONFIG, 'import', name);
  if (!existsSync(from) || !statSync(from).isDirectory()) return;
  if (!isEmpty(target) && existsSync(join(target, marker))) {
    log(`Found import/${name}, but this app already has its own data. Leaving it alone (delete import/${name} to stop this message).`);
    return;
  }
  log(`Importing ${name} from the app's config folder…`);
  cpSync(from, target, { recursive: true, force: true });
  renameSync(from, `${from}.imported`);
  log(`Imported ${name}. The original is kept as import/${name}.imported.`);
}

function chownTree(dir) {
  try {
    chownSync(dir, NODE_UID, NODE_GID);
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name);
      if (entry.isDirectory()) chownTree(p);
      else chownSync(p, NODE_UID, NODE_GID);
    }
  } catch {
    /* not root (local testing): nothing to hand over */
  }
}

const options = readOptions();
const actualDir = join(DATA, 'actual');
const bridgeDir = join(DATA, 'bridge');
mkdirSync(actualDir, { recursive: true });
mkdirSync(bridgeDir, { recursive: true });
importOnce('actual-data', actualDir, 'server-files');
importOnce('bridge-data', bridgeDir, 'bridge.sqlite');
chownTree(actualDir);
chownTree(bridgeDir);

const publicUrl = String(options.public_url ?? process.env.BRIDGE_PUBLIC_URL ?? '').trim();
if (!publicUrl) {
  log('Set "Public address" in the app\'s Configuration tab (the https address the phones use, e.g. https://budget-api.example.com), then restart the app.');
  // Stay up without crash-looping, so the log stays readable; the watchdog sees no health yet.
}
const level = ['debug', 'info', 'warn', 'error'].includes(options.log_level) ? options.log_level : 'info';
const budgetPasswords = Array.isArray(options.budget_passwords)
  ? options.budget_passwords.filter((b) => b?.budget_id && b?.password).map((b) => `${b.budget_id}=${b.password}`).join(',')
  : '';

const asNode = process.getuid?.() === 0 ? { uid: NODE_UID, gid: NODE_GID } : {};
const children = [];
let stopping = false;

function start(name, args, env) {
  const child = spawn(process.execPath, args, { env: { PATH: process.env.PATH, HOME: '/tmp', NODE_ENV: 'production', ...env }, stdio: ['ignore', 'pipe', 'pipe'], ...asNode });
  const prefix = (stream, out) => {
    let buf = '';
    stream.on('data', (chunk) => {
      buf += chunk;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        out.write(`[${name}] ${buf.slice(0, i)}\n`);
        buf = buf.slice(i + 1);
      }
    });
  };
  prefix(child.stdout, process.stdout);
  prefix(child.stderr, process.stderr);
  child.on('exit', (code, signal) => {
    if (stopping) return;
    log(`${name} stopped (${signal ?? `exit ${code}`}). Restarting the app.`);
    shutdown(1);
  });
  children.push(child);
  return child;
}

async function waitFor(url, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (res.ok) return true;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

function shutdown(code) {
  if (stopping) return;
  stopping = true;
  for (const c of children) c.kill('SIGTERM');
  const force = setTimeout(() => {
    for (const c of children) c.kill('SIGKILL');
    process.exit(code);
  }, 15_000);
  force.unref();
  Promise.all(children.map((c) => (c.exitCode !== null || c.signalCode !== null ? null : new Promise((r) => c.once('exit', r))))).then(() => process.exit(code));
}
process.on('SIGTERM', () => shutdown(0));
process.on('SIGINT', () => shutdown(0));

log(`Starting Centsible ${process.env.BRIDGE_RELEASE || '(development build)'} with Actual ${process.env.ACTUAL_VERSION || ''}`.trim());

start('actual', [join(APP, 'actual/node_modules/@actual-app/sync-server/build/bin/actual-server.js')], {
  ACTUAL_DATA_DIR: actualDir,
  ACTUAL_PORT: String(ACTUAL_PORT),
  ACTUAL_HOSTNAME: '0.0.0.0',
});

if (!(await waitFor(`http://127.0.0.1:${ACTUAL_PORT}/health`, 120_000))) {
  log("Actual didn't start within two minutes; the bridge will keep trying to reach it.");
}

if (publicUrl) {
  start('bridge', [join(APP, 'bridge/dist/main.js')], {
    BRIDGE_DATA_DIR: bridgeDir,
    BRIDGE_PORT: String(BRIDGE_PORT),
    BRIDGE_HOST: '0.0.0.0',
    BRIDGE_PUBLIC_URL: publicUrl,
    BRIDGE_TRUST_PROXY: 'true',
    BRIDGE_MANAGED_BY: 'home-assistant',
    BRIDGE_RELEASE: process.env.BRIDGE_RELEASE ?? '',
    ACTUAL_SERVER_URL: `http://127.0.0.1:${ACTUAL_PORT}`,
    // Read-only use: history import reaches the SimpleFIN connection made in Actual's web app.
    ACTUAL_DATA_DIR: actualDir,
    LOG_LEVEL: level,
    ...(options.cloudflare_access_client_id && options.cloudflare_access_client_secret
      ? { CF_ACCESS_CLIENT_ID: options.cloudflare_access_client_id, CF_ACCESS_CLIENT_SECRET: options.cloudflare_access_client_secret }
      : {}),
    ...(options.setup_code ? { BRIDGE_SETUP_CODE: options.setup_code } : {}),
    ...(options.actual_password ? { ACTUAL_PASSWORD: options.actual_password } : {}),
    ...(budgetPasswords ? { ACTUAL_BUDGET_PASSWORDS: budgetPasswords } : {}),
  });
}
