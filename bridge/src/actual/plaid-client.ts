import { chmodSync, existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The parts of Plaid's API (plaid.com/docs/api) the bridge uses. Every call is a JSON POST
 * carrying the household's client id and secret; a bank login ("Item") is reached through
 * its access token.
 */

export const PLAID_ENVIRONMENTS = ['sandbox', 'production'] as const;
export type PlaidEnvironment = (typeof PLAID_ENVIRONMENTS)[number];
export const PLAID_HOSTS: Record<PlaidEnvironment, string> = {
  sandbox: 'https://sandbox.plaid.com',
  production: 'https://production.plaid.com',
};

export type PlaidAccount = {
  account_id: string;
  name: string;
  official_name?: string | null;
  mask?: string | null;
  type: string;
  subtype?: string | null;
  balances?: { current?: number | null; available?: number | null; iso_currency_code?: string | null } | null;
};
export type PlaidItem = { item_id: string; institution_id?: string | null; institution_name?: string | null };
export type PlaidTransaction = {
  transaction_id: string;
  account_id: string;
  /** Positive is money leaving the account (a purchase), for every account type. */
  amount: number;
  date: string;
  name?: string | null;
  merchant_name?: string | null;
  pending?: boolean;
  counterparties?: { name?: string | null }[] | null;
};
export type PlaidSyncPage = {
  added: PlaidTransaction[];
  modified: PlaidTransaction[];
  removed: { transaction_id: string; account_id?: string }[];
  next_cursor: string;
  has_more: boolean;
  transactions_update_status?: string;
};

export class PlaidError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly type: string | null,
    readonly code: string | null,
  ) {
    super(message);
  }
  /** The person has to sign in to the bank again (Link in update mode fixes it). */
  get reauth() {
    return ['ITEM_LOGIN_REQUIRED', 'PENDING_EXPIRATION', 'PENDING_DISCONNECT', 'ACCESS_NOT_GRANTED', 'NO_ACCOUNTS', 'INSUFFICIENT_CREDENTIALS', 'USER_SETUP_REQUIRED', 'ITEM_LOCKED', 'MFA_NOT_SUPPORTED'].includes(this.code ?? '');
  }
  /** The bank login is gone for good: remove it and connect again. */
  get gone() {
    return this.code === 'ITEM_NOT_FOUND' || this.code === 'INVALID_ACCESS_TOKEN';
  }
}

/** What each error means for the person, and what they can do. */
export function describePlaidCode(code: string | null, fallback?: string | null): string {
  switch (code) {
    case 'ITEM_LOGIN_REQUIRED':
      return 'The bank wants you to sign in again (a changed password, a new verification step or an expired consent). Reconnect the bank.';
    case 'PENDING_EXPIRATION':
    case 'PENDING_DISCONNECT':
      return "The bank's permission for this connection is about to run out. Reconnect the bank to renew it.";
    case 'ACCESS_NOT_GRANTED':
    case 'NO_ACCOUNTS':
      return "The bank didn't share any accounts this time. Reconnect the bank and tick the accounts to share.";
    case 'ITEM_LOCKED':
      return 'The bank locked the login after too many attempts. Unlock it on the bank\'s website, then reconnect.';
    case 'USER_SETUP_REQUIRED':
      return 'The bank wants something done on its website first (often accepting new terms). Do that, then reconnect.';
    case 'ITEM_NOT_FOUND':
    case 'INVALID_ACCESS_TOKEN':
      return 'Plaid no longer knows this bank connection. Remove it and connect the bank again.';
    case 'INSTITUTION_DOWN':
    case 'INSTITUTION_NOT_RESPONDING':
    case 'INSTITUTION_NOT_AVAILABLE':
      return 'The bank is unavailable right now. It will try again at the next sync.';
    case 'RATE_LIMIT_EXCEEDED':
      return 'Plaid asked the bridge to slow down. It will try again at the next sync.';
    case 'INVALID_API_KEYS':
      return "Plaid doesn't accept that client id and secret. Copy them again from the Plaid dashboard (Developers → Keys), and pick the matching environment.";
    case 'INVALID_PRODUCT':
    case 'PRODUCTS_NOT_SUPPORTED':
      return "This Plaid account can't use Transactions yet. Check the Plaid dashboard that the Trial plan (or Production) is active.";
    default:
      return fallback?.trim() || 'Plaid had a problem with this bank connection. Reconnect the bank.';
  }
}

export type PlaidKeys = { clientId: string; secret: string };

export class PlaidClient {
  constructor(
    private readonly keys: PlaidKeys,
    private readonly baseUrl: string,
  ) {}

  /**
   * A short-lived token that opens Plaid Link on the phone: a new bank login, or (with
   * [accessToken]) the same one in update mode to sign in again.
   */
  linkToken(input: { userId: string; androidPackageName?: string | null; accessToken?: string | null; daysRequested?: number }): Promise<{ link_token: string; expiration: string }> {
    return this.post('/link/token/create', {
      client_name: 'Centsible',
      language: 'en',
      country_codes: ['US', 'CA'],
      user: { client_user_id: input.userId },
      ...(input.androidPackageName ? { android_package_name: input.androidPackageName } : {}),
      ...(input.accessToken
        ? { access_token: input.accessToken }
        : { products: ['transactions'], transactions: { days_requested: input.daysRequested ?? 730 } }),
    }) as Promise<{ link_token: string; expiration: string }>;
  }

  exchange(publicToken: string): Promise<{ access_token: string; item_id: string }> {
    return this.post('/item/public_token/exchange', { public_token: publicToken }) as Promise<{ access_token: string; item_id: string }>;
  }

  accounts(accessToken: string): Promise<{ accounts: PlaidAccount[]; item: PlaidItem }> {
    return this.post('/accounts/get', { access_token: accessToken }) as Promise<{ accounts: PlaidAccount[]; item: PlaidItem }>;
  }

  syncPage(accessToken: string, cursor: string | null, count = 500): Promise<PlaidSyncPage> {
    return this.post('/transactions/sync', { access_token: accessToken, count, ...(cursor ? { cursor } : {}) }) as Promise<PlaidSyncPage>;
  }

  /**
   * Every change after [cursor] up to now. Starts over from [cursor] when the data changes
   * mid-way (Plaid's TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION).
   */
  async syncAll(accessToken: string, cursor: string | null): Promise<{ added: PlaidTransaction[]; modified: PlaidTransaction[]; removed: string[]; cursor: string; status: string | null }> {
    for (let attempt = 0; ; attempt++) {
      const added: PlaidTransaction[] = [];
      const modified: PlaidTransaction[] = [];
      const removed: string[] = [];
      let next = cursor;
      let status: string | null = null;
      try {
        for (let page = 0; page < 200; page++) {
          const res = await this.syncPage(accessToken, next);
          added.push(...res.added);
          modified.push(...res.modified);
          removed.push(...res.removed.map((r) => r.transaction_id));
          next = res.next_cursor;
          status = res.transactions_update_status ?? null;
          if (!res.has_more) break;
        }
        return { added, modified, removed, cursor: next ?? '', status };
      } catch (err) {
        if (err instanceof PlaidError && err.code === 'TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION' && attempt < 3) continue;
        throw err;
      }
    }
  }

  /** Ends Plaid's access to the bank login (best effort when removing it). */
  async remove(accessToken: string): Promise<void> {
    await this.post('/item/remove', { access_token: accessToken });
  }

  private async post(path: string, body: Record<string, unknown>): Promise<unknown> {
    let res: Response;
    try {
      res = await fetch(new URL(path, this.baseUrl), {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'plaid-version': '2020-09-14' },
        body: JSON.stringify({ client_id: this.keys.clientId, secret: this.keys.secret, ...body }),
        signal: AbortSignal.timeout(60_000),
      });
    } catch (e) {
      throw new PlaidError(`Couldn't reach Plaid: ${e instanceof Error ? e.message : String(e)}`, 0, null, null);
    }
    const text = await res.text();
    let json: Record<string, unknown> | null = null;
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : null;
    } catch {
      /* not JSON */
    }
    if (res.ok) return json;
    const code = typeof json?.error_code === 'string' ? json.error_code : null;
    const type = typeof json?.error_type === 'string' ? json.error_type : null;
    const plaidMessage = (typeof json?.display_message === 'string' && json.display_message) || (typeof json?.error_message === 'string' ? json.error_message : null);
    let message = describePlaidCode(code, plaidMessage ?? `Plaid answered HTTP ${res.status}`);
    if (code === 'INVALID_FIELD' && /android_package_name/i.test(String(json?.error_message ?? ''))) {
      message = 'Plaid doesn\'t know the Centsible app yet. In the Plaid dashboard, open Developers → API → Allowed Android package names, add app.centsible.budget, and try again.';
    }
    throw new PlaidError(message, res.status, type, code);
  }
}

/**
 * Plaid's secret and each bank login's access token. Kept in an owner-only file beside
 * bridge.sqlite rather than in it, because the household database goes into backups
 * (which can be downloaded).
 */
export class PlaidVault {
  private readonly path: string;
  constructor(dataDir: string) {
    this.path = join(dataDir, 'plaid.json');
  }

  private read(): { secret?: string; tokens: Record<string, string> } {
    if (!existsSync(this.path)) return { tokens: {} };
    try {
      const v = JSON.parse(readFileSync(this.path, 'utf8')) as { secret?: string; tokens?: Record<string, string> };
      return { ...v, tokens: v.tokens ?? {} };
    } catch {
      return { tokens: {} };
    }
  }

  private write(v: ReturnType<PlaidVault['read']>) {
    writeFileSync(this.path, JSON.stringify(v), { mode: 0o600 });
    chmodSync(this.path, 0o600); // an existing file keeps its old mode otherwise
  }

  secret(): string | null {
    return this.read().secret ?? null;
  }
  setSecret(secret: string) {
    this.write({ ...this.read(), secret });
  }
  token(itemId: string): string | null {
    return this.read().tokens[itemId] ?? null;
  }
  setToken(itemId: string, token: string | null) {
    const v = this.read();
    if (token) v.tokens[itemId] = token;
    else delete v.tokens[itemId];
    this.write(v);
  }
  clear() {
    rmSync(this.path, { force: true });
  }
}
