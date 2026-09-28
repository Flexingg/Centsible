import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The bits of the SimpleFIN protocol (simplefin.org/protocol) the bridge speaks itself.
 * Everyday syncing goes through Actual's server, but Actual only ever asks SimpleFIN for
 * "since X until now", and SimpleFIN Bridge answers at most 90 days per request. Reaching
 * further back needs explicit start and end dates, so the bridge keeps its own copy of
 * the access URL (it claims the setup token itself, then hands the result to Actual).
 */

export type SimpleFinTransaction = {
  id: string;
  posted: number;
  amount: string;
  description?: string;
  payee?: string;
  memo?: string;
  pending?: boolean;
  transacted_at?: number;
};
export type SimpleFinAccount = { id: string; name?: string; transactions?: SimpleFinTransaction[] };
export type SimpleFinAccountSet = { accounts: SimpleFinAccount[]; messages: string[] };

/** SimpleFIN Bridge returns at most this many days per request. */
export const SIMPLEFIN_MAX_WINDOW_DAYS = 90;

export class SimpleFinError extends Error {
  constructor(
    message: string,
    readonly forbidden = false,
  ) {
    super(message);
  }
}

const ACCESS_URL = /^https?:\/\/[^:/@]+:[^@]+@.+$/;

/** POSTs the claim URL inside a setup token; SimpleFIN answers with the access URL, once. */
export async function claimSetupToken(setupToken: string): Promise<string> {
  const claimUrl = Buffer.from(setupToken.trim(), 'base64').toString();
  let res: Response;
  try {
    res = await fetch(claimUrl, { method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(30_000) });
  } catch {
    throw new SimpleFinError("Couldn't reach SimpleFIN. Try again in a few minutes.");
  }
  const body = (await res.text()).trim();
  if (res.status === 403 || !ACCESS_URL.test(body)) {
    throw new SimpleFinError("SimpleFIN didn't accept the setup token. Setup tokens work only once: create a new one at bridge.simplefin.org and connect again.", true);
  }
  return body;
}

/** GET {access}/accounts for [start, end), without pending transactions (history only). */
export async function fetchWindow(accessUrl: string, accountIds: string[], start: Date, end: Date): Promise<SimpleFinAccountSet> {
  const u = new URL(accessUrl);
  const auth = `Basic ${Buffer.from(`${decodeURIComponent(u.username)}:${decodeURIComponent(u.password)}`).toString('base64')}`;
  u.username = '';
  u.password = '';
  const url = new URL(`${u.toString().replace(/\/$/, '')}/accounts`);
  url.searchParams.set('start-date', String(Math.floor(start.getTime() / 1000)));
  url.searchParams.set('end-date', String(Math.floor(end.getTime() / 1000)));
  for (const id of accountIds) url.searchParams.append('account', id);
  let res: Response;
  try {
    res = await fetch(url, { headers: { authorization: auth }, redirect: 'follow', signal: AbortSignal.timeout(120_000) });
  } catch {
    throw new SimpleFinError("Couldn't reach SimpleFIN. Try again in a few minutes.");
  }
  if (res.status === 403) throw new SimpleFinError('SimpleFIN no longer accepts this connection. Reconnect SimpleFIN with a new setup token.', true);
  if (!res.ok) throw new SimpleFinError(`SimpleFIN answered HTTP ${res.status}. Try again later.`);
  const body = (await res.json()) as { accounts?: SimpleFinAccount[]; errors?: string[]; errlist?: { msg?: string }[] };
  const messages = [...(body.errlist ?? []).map((e) => e.msg ?? ''), ...(body.errors ?? [])].filter(Boolean);
  return { accounts: body.accounts ?? [], messages: [...new Set(messages)] };
}

/** The access URL, kept next to bridge.sqlite with owner-only permissions. */
export class SimpleFinKeyFile {
  private readonly path: string;
  constructor(dataDir: string) {
    this.path = join(dataDir, 'simplefin-access');
  }
  read(): string | null {
    return existsSync(this.path) ? readFileSync(this.path, 'utf8').trim() || null : null;
  }
  write(accessUrl: string) {
    writeFileSync(this.path, accessUrl, { mode: 0o600 });
  }
  clear() {
    rmSync(this.path, { force: true });
  }
}
