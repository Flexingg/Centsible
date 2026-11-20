import * as api from '@actual-app/api';
import type { HouseholdStore, PlaidItemRow, PlaidLinkRow } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { AccountSyncResult } from './bank-sync-ops.js';
import { linkedAccounts } from './bank-sync-ops.js';
import type { ActualHost } from './host.js';
import {
  describePlaidCode,
  PLAID_ENVIRONMENTS,
  PLAID_HOSTS,
  PlaidClient,
  PlaidError,
  type PlaidAccount,
  type PlaidEnvironment,
  type PlaidTransaction,
  type PlaidVault,
} from './plaid-client.js';

type Send = (name: string, args?: unknown) => Promise<unknown>;
const settle = () => new Promise<void>((r) => setTimeout(r, 0));
const DAY = 24 * 3600_000;
const CONFIG_KEY = 'plaid.config';
/** The Android app's package name, which Plaid needs to finish bank sign-ins that go through the bank's own app or site. */
export const ANDROID_PACKAGE = 'app.centsible.budget';

export type PlaidConfig = { clientId: string; environment: PlaidEnvironment };
export type PlaidItemView = { id: string; institution: string; status: PlaidItemRow['status']; message: string | null; linkedAccounts: number; updatedAt: string };
export type PlaidStatus = {
  configured: boolean;
  clientId: string | null;
  environment: PlaidEnvironment | null;
  androidPackageName: string;
  items: PlaidItemView[];
};
export type PlaidAccountView = {
  id: string;
  itemId: string;
  institution: string;
  name: string;
  type: string;
  subtype: string | null;
  mask: string | null;
  /** The current balance as Plaid reports it, in cents, signed the way it shows in Actual (debts negative). */
  balance: number | null;
  currency: string;
  linkedAccountId: string | null;
  linkedAccountName: string | null;
};

const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/**
 * Plaid as a second bank sync provider beside SimpleFIN (Teller's replacement). Actual has
 * no Plaid support, so the bridge does the syncing itself: it pulls posted transactions
 * through /transactions/sync (each link keeps its own cursor, so one bank login can feed
 * accounts in several budgets) and hands them to Actual's importTransactions, which runs
 * the budget's rules. Imported ids are "plaid:<transaction id>".
 *
 * Plaid's amounts are positive for money going out, for every account type, so they are
 * negated on the way in and no per-account sign choice is needed.
 */
export class PlaidOps {
  /** The last account listing per bank login, so linking right after doesn't ask Plaid again. */
  private listing = new Map<string, { at: number; accounts: PlaidAccount[] }>();

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly vault: PlaidVault,
    /** Overrides Plaid's address (tests point this at a stand-in). */
    private readonly apiUrl?: string,
  ) {}

  private config(): PlaidConfig | null {
    return this.store.getSetting<PlaidConfig>(CONFIG_KEY);
  }

  private client(config = this.config(), secret = this.vault.secret()) {
    if (!config || !secret) throw ApiError.validation('Set up Plaid first (client id and secret from the Plaid dashboard).');
    return new PlaidClient({ clientId: config.clientId, secret }, this.apiUrl ?? PLAID_HOSTS[config.environment]);
  }

  /** True when at least one account (in [budgetId], or anywhere) syncs through Plaid. */
  hasLinks(budgetId?: string) {
    return this.store.plaidLinks(budgetId).length > 0;
  }

  links(budgetId: string): PlaidLinkRow[] {
    return this.store.plaidLinks(budgetId);
  }

  status(): PlaidStatus {
    const config = this.config();
    const links = this.store.plaidLinks();
    return {
      configured: config != null && this.vault.secret() != null,
      clientId: config?.clientId ?? null,
      environment: config?.environment ?? null,
      androidPackageName: ANDROID_PACKAGE,
      items: this.store.plaidItems().map((e) => ({
        id: e.id,
        institution: e.institution,
        status: e.status,
        message: e.status === 'ok' ? null : describePlaidCode(e.statusCode),
        linkedAccounts: links.filter((l) => l.itemId === e.id).length,
        updatedAt: e.updatedAt,
      })),
    };
  }

  /**
   * Owners. The client id and secret from the Plaid dashboard (Developers → Keys) for the
   * chosen environment. Checked with Plaid right away. The secret can be left out to keep
   * the stored one.
   */
  async configure(input: { clientId: string; secret?: string; environment: PlaidEnvironment }): Promise<PlaidStatus> {
    const clientId = input.clientId.trim();
    if (!/^[A-Za-z0-9]{16,40}$/.test(clientId)) throw ApiError.validation("That isn't a Plaid client id. Copy it from the Plaid dashboard (Developers → Keys).");
    if (!PLAID_ENVIRONMENTS.includes(input.environment)) throw ApiError.validation('Choose sandbox or production.');
    const secret = input.secret?.trim() || this.vault.secret();
    if (!secret) throw ApiError.validation('Paste the secret from the Plaid dashboard (Developers → Keys) too.');
    if (!/^[A-Za-z0-9]{16,60}$/.test(secret)) throw ApiError.validation("That isn't a Plaid secret. Copy it from the Plaid dashboard (Developers → Keys), for the environment you picked.");
    const config: PlaidConfig = { clientId, environment: input.environment };
    try {
      // A link token is the cheapest call that proves the keys and the Transactions product work.
      await this.client(config, secret).linkToken({ userId: 'centsible-setup-check' });
    } catch (err) {
      throw toApiError(err, true);
    }
    this.vault.setSecret(secret);
    this.store.setSetting(CONFIG_KEY, config);
    this.listing.clear();
    return this.status();
  }

  /** Owners. Forgets Plaid entirely: keys, bank logins and links (imported transactions stay). */
  async reset(): Promise<void> {
    if (this.config() && this.vault.secret()) {
      for (const e of this.store.plaidItems()) {
        const token = this.vault.token(e.id);
        if (token) await this.client().remove(token).catch(() => undefined);
      }
    }
    this.store.clearPlaid();
    this.store.setSetting(CONFIG_KEY, null);
    this.vault.clear();
    this.listing.clear();
  }

  /**
   * Members. A token that opens Plaid Link on the phone, for a new bank login or (with
   * [itemId]) to sign in to an existing one again.
   */
  async linkToken(input: { memberId: string; itemId?: string | null }): Promise<{ linkToken: string; expiration: string }> {
    let accessToken: string | null = null;
    if (input.itemId) {
      if (!this.store.plaidItem(input.itemId)) throw ApiError.notFound('That bank connection is gone. Connect the bank again.');
      accessToken = this.vault.token(input.itemId);
      if (!accessToken) throw ApiError.validation('The bridge lost this bank connection (plaid.json is missing). Remove it and connect the bank again.');
    }
    try {
      const res = await this.client().linkToken({ userId: input.memberId, androidPackageName: ANDROID_PACKAGE, accessToken });
      return { linkToken: res.link_token, expiration: res.expiration };
    } catch (err) {
      throw toApiError(err, true);
    }
  }

  /**
   * Members. Saves a bank login from Plaid Link's onSuccess: exchanges the public token
   * for the access token and reads its accounts. With [itemId] (after update mode) it only
   * checks the login works again.
   */
  async addItem(input: { publicToken?: string | null; itemId?: string | null; institution?: string | null }): Promise<PlaidItemView> {
    const client = this.client();
    let itemId: string;
    let token: string;
    if (input.itemId) {
      const existing = this.store.plaidItem(input.itemId);
      const stored = this.vault.token(input.itemId);
      if (!existing || !stored) throw ApiError.notFound('That bank connection is gone. Connect the bank again.');
      itemId = input.itemId;
      token = stored;
    } else {
      if (!input.publicToken) throw ApiError.validation('Missing the public token from Plaid Link.');
      try {
        const ex = await client.exchange(input.publicToken.trim());
        itemId = ex.item_id;
        token = ex.access_token;
      } catch (err) {
        throw toApiError(err);
      }
    }
    let accounts: PlaidAccount[];
    let institution = input.institution?.trim() || this.store.plaidItem(itemId)?.institution || '';
    try {
      const res = await client.accounts(token);
      accounts = res.accounts;
      institution ||= res.item.institution_name?.trim() || 'Bank';
    } catch (err) {
      throw toApiError(err);
    }
    this.vault.setToken(itemId, token);
    this.store.savePlaidItem({ id: itemId, institution });
    this.listing.set(itemId, { at: Date.now(), accounts });
    for (const l of this.store.plaidLinks()) if (l.itemId === itemId) this.store.updatePlaidLink(l.budgetId, l.accountId, { lastError: null });
    return this.status().items.find((e) => e.id === itemId)!;
  }

  /** Owners. Stops syncing a bank login everywhere and ends Plaid's access. Imported transactions stay. */
  async removeItem(id: string): Promise<void> {
    if (!this.store.plaidItem(id)) throw ApiError.notFound('That bank connection is gone already.');
    const token = this.vault.token(id);
    if (token && this.config() && this.vault.secret()) await this.client().remove(token).catch(() => undefined);
    this.vault.setToken(id, null);
    this.store.deletePlaidItem(id);
    this.listing.delete(id);
  }

  /** Every account across the household's bank logins, with what each feeds in [budgetId]. */
  async listAccounts(budgetId: string, refresh = true): Promise<PlaidAccountView[]> {
    const out: { account: PlaidAccount; itemId: string; institution: string }[] = [];
    const errors: string[] = [];
    for (const e of this.store.plaidItems()) {
      const token = this.vault.token(e.id);
      if (!token) continue;
      try {
        for (const a of await this.accountsOf(e.id, token, refresh)) out.push({ account: a, itemId: e.id, institution: e.institution });
      } catch (err) {
        this.noteFailure(e.id, err);
        errors.push(`${e.institution}: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
    if (!out.length && errors.length) throw new ApiError(502, 'bank_sync_failed', 'Plaid error', errors.join(' '));
    const links = this.store.plaidLinks(budgetId);
    const names = await this.host.withBudget(budgetId, 'read', async () => new Map((await linkedAccounts()).map((a) => [a.id, a.name])));
    return out.map(({ account: a, itemId, institution }) => {
      const link = links.find((l) => l.plaidAccountId === a.account_id);
      return {
        id: a.account_id,
        itemId,
        institution,
        name: a.name,
        type: a.type,
        subtype: a.subtype ?? null,
        mask: a.mask ?? null,
        balance: balanceOf(a),
        currency: a.balances?.iso_currency_code ?? 'USD',
        linkedAccountId: link?.accountId ?? null,
        linkedAccountName: link ? (names.get(link.accountId) ?? null) : null,
      };
    });
  }

  /**
   * Links a Plaid account to an existing account in the budget and imports from
   * [startDate]. If the account was syncing through SimpleFIN, that link is removed first
   * (two providers feeding one account would race each other).
   */
  async link(budgetId: string, input: { plaidAccountId: string; accountId: string; startDate?: string }): Promise<{ accountId: string; result: AccountSyncResult }> {
    const { itemId, account } = await this.find(input.plaidAccountId);
    const startDate = input.startDate ?? iso(Date.now() - 30 * DAY);
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const local = (await linkedAccounts()).find((a) => a.id === input.accountId);
      if (!local) throw ApiError.notFound('Account not found');
      if (local.closed) throw ApiError.validation('Reopen the account before linking it.');
      const existing = this.store.plaidLinks(budgetId);
      if (existing.some((l) => l.plaidAccountId === input.plaidAccountId)) throw ApiError.validation('That bank account already feeds another account. Unlink it there first.');
      if (existing.some((l) => l.accountId === input.accountId)) throw ApiError.validation(`"${local.name}" already syncs through Plaid. Unlink it first.`);
      if (local.source) {
        await (lib.send as Send)('account-unlink', { id: input.accountId });
        await settle();
      }
      this.store.addPlaidLink({ budgetId, accountId: input.accountId, plaidAccountId: input.plaidAccountId, itemId, plaidAccountName: account.name, startDate });
      const [result] = await this.syncIn(budgetId, [input.accountId]);
      return { accountId: input.accountId, result: result! };
    });
  }

  /** Stops Plaid syncing for an account (its transactions stay). False when it wasn't linked. */
  unlink(budgetId: string, accountId: string): boolean {
    if (!this.store.plaidLink(budgetId, accountId)) return false;
    this.store.deletePlaidLink(budgetId, accountId);
    return true;
  }

  /** What account listings show for Plaid-linked accounts (Actual itself doesn't know about Plaid). */
  overlay(budgetId: string): Map<string, { syncSource: string; lastSync: string | null; bankSyncStatus: string | null }> {
    const items = new Map(this.store.plaidItems().map((e) => [e.id, e]));
    return new Map(
      this.store.plaidLinks(budgetId).map((l) => {
        const e = items.get(l.itemId);
        const status = !e || e.status !== 'ok' ? 'reauth-required' : l.lastError ? 'attention-required' : 'ok';
        return [l.accountId, { syncSource: 'plaid', lastSync: l.lastSyncAt, bankSyncStatus: status }];
      }),
    );
  }

  /**
   * Imports new posted transactions for the budget's Plaid-linked accounts (all, or
   * [only]). Must run inside a withBudget session for [budgetId]; one account failing
   * doesn't stop the others.
   */
  async syncIn(budgetId: string, only?: string[]): Promise<AccountSyncResult[]> {
    const links = this.store.plaidLinks(budgetId).filter((l) => !only || only.includes(l.accountId));
    if (!links.length) return [];
    const accounts = await linkedAccounts();
    const client = this.client();
    const results: AccountSyncResult[] = [];
    for (const l of links) {
      const local = accounts.find((a) => a.id === l.accountId);
      const name = local?.name ?? l.plaidAccountName;
      const result: AccountSyncResult = { accountId: l.accountId, name, newTransactions: 0, matchedTransactions: 0, error: null, status: 'ok' };
      results.push(result);
      if (!local || local.closed) {
        result.error = local ? 'The account is closed.' : 'The account is gone from the budget.';
        result.status = 'account-missing';
        continue;
      }
      const token = this.vault.token(l.itemId);
      if (!token) {
        result.error = 'The bridge lost this bank connection (plaid.json is missing). Remove it in Bank sync → Plaid and connect the bank again.';
        result.status = 'reauth-required';
        this.store.updatePlaidLink(budgetId, l.accountId, { lastError: result.error });
        continue;
      }
      try {
        const changes = await client.syncAll(token, l.cursor);
        const mine = (t: PlaidTransaction) => t.account_id === l.plaidAccountId && !t.pending && /^\d{4}-\d{2}-\d{2}$/.test(t.date) && t.date >= l.startDate && Number.isFinite(t.amount);
        const corrected = await applyCorrections(l.accountId, changes.modified.filter(mine));
        const corrections = new Set(corrected);
        const entities = [...changes.added, ...changes.modified].filter((t) => mine(t) && !corrections.has(t.transaction_id)).map(
          (t) =>
            ({
              date: t.date,
              amount: -Math.round(t.amount * 100),
              payee_name: payeeOf(t),
              imported_payee: t.name?.trim() || payeeOf(t),
              imported_id: `plaid:${t.transaction_id}`,
              cleared: true,
            }) as ImportEntity,
        );
        const adopted = await adoptExisting(l.accountId, entities, l.cursor ? iso(Date.now() - 30 * DAY) : l.startDate);
        result.matchedTransactions = adopted;
        const fresh = entities.filter((e) => !(e as { adopted?: boolean }).adopted);
        if (fresh.length) {
          const res = (await api.importTransactions(l.accountId, fresh, { defaultCleared: true })) as unknown as { added?: unknown[]; updated?: unknown[]; errors?: { message?: string }[] };
          result.newTransactions = res.added?.length ?? 0;
          result.matchedTransactions += res.updated?.length ?? 0;
          if (res.errors?.length) result.error = res.errors.map((e) => e.message ?? 'Import error').join('; ');
        }
        await removeGone(l.accountId, changes.removed);
        // Plaid is still gathering the first history: start over next time rather than skip it.
        const notReady = !l.cursor && changes.status === 'NOT_READY';
        if (notReady && !result.error) result.error = 'Plaid is still fetching this account\'s history. It comes in at the next sync.';
        this.store.updatePlaidLink(budgetId, l.accountId, { lastSyncAt: new Date().toISOString(), lastError: result.error, cursor: notReady ? null : changes.cursor || l.cursor });
        if (this.store.plaidItem(l.itemId)?.status !== 'ok') this.store.setPlaidItemStatus(l.itemId, 'ok', null);
      } catch (err) {
        this.noteFailure(l.itemId, err);
        result.error = err instanceof Error ? err.message : String(err);
        result.status =
          err instanceof PlaidError ? (err.reauth || err.gone ? 'reauth-required' : err.status === 429 || err.code === 'RATE_LIMIT_EXCEEDED' ? 'rate-limit-exceeded' : 'attention-required') : 'attention-required';
        this.store.updatePlaidLink(budgetId, l.accountId, { lastError: result.error });
      }
    }
    await settle();
    return results;
  }

  private noteFailure(itemId: string, err: unknown) {
    if (!(err instanceof PlaidError)) return;
    if (err.reauth) this.store.setPlaidItemStatus(itemId, 'reauth', err.code);
    else if (err.gone) this.store.setPlaidItemStatus(itemId, 'gone', err.code);
  }

  private async accountsOf(itemId: string, token: string, refresh: boolean): Promise<PlaidAccount[]> {
    const cached = this.listing.get(itemId);
    if (!refresh && cached && Date.now() - cached.at < 10 * 60_000) return cached.accounts;
    const { accounts } = await this.client().accounts(token);
    this.listing.set(itemId, { at: Date.now(), accounts });
    if (this.store.plaidItem(itemId)?.status !== 'ok') this.store.setPlaidItemStatus(itemId, 'ok', null);
    return accounts;
  }

  /** Which bank login a Plaid account belongs to (from the recent listing, else asking Plaid). */
  private async find(plaidAccountId: string): Promise<{ itemId: string; token: string; account: PlaidAccount }> {
    for (const pass of [false, true]) {
      for (const e of this.store.plaidItems()) {
        const token = this.vault.token(e.id);
        if (!token) continue;
        const accounts = pass ? await this.accountsOf(e.id, token, true).catch((err: unknown) => (this.noteFailure(e.id, err), [] as PlaidAccount[])) : (this.listing.get(e.id)?.accounts ?? []);
        const account = accounts.find((a) => a.account_id === plaidAccountId);
        if (account) return { itemId: e.id, token, account };
      }
    }
    throw ApiError.notFound("That account isn't in any connected bank. Connect the bank in Bank sync → Plaid.");
  }
}

type ImportEntity = Parameters<typeof api.importTransactions>[1][number];

/** Debts (credit cards, loans) show negative in Actual; Plaid reports what's owed as a positive number. */
function balanceOf(a: PlaidAccount): number | null {
  const v = a.balances?.current ?? a.balances?.available;
  if (v === null || v === undefined || !Number.isFinite(v)) return null;
  const cents = Math.round(v * 100);
  return a.type === 'credit' || a.type === 'loan' ? -cents : cents;
}

/**
 * Actual only fuzzy-matches an imported transaction against ones that have no imported id,
 * so after moving an account from SimpleFIN or Teller (or a file import) to Plaid, the same
 * purchase would come in twice. Before importing, each Plaid transaction not seen before
 * takes over one existing transaction from another source with the same amount within 3
 * days: that transaction gets the Plaid id (so later syncs match it by id) instead of a
 * duplicate being added. One-to-one, so two genuine identical purchases stay two.
 */
async function adoptExisting(accountId: string, entities: ImportEntity[], since: string): Promise<number> {
  if (!entities.length) return 0;
  const earliest = entities.reduce((m, e) => ((e.date as string) < m ? (e.date as string) : m), since);
  const from = iso(Date.parse(`${earliest}T00:00:00Z`) - 4 * DAY);
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ account: accountId, date: { $gte: from }, is_parent: false }).select(['id', 'date', 'amount', 'imported_id']),
  )) as { data: { id: string; date: string; amount: number; imported_id: string | null }[] };
  const ids = new Set(data.map((t) => t.imported_id).filter(Boolean));
  const candidates = data.filter((t) => t.imported_id && !t.imported_id.startsWith('plaid:'));
  const claimed = new Set<string>();
  let adopted = 0;
  const day = (d: string) => Date.parse(`${d}T00:00:00Z`);
  for (const e of entities) {
    const id = e.imported_id as string;
    if (ids.has(id)) continue; // seen before: Actual matches it by id
    const match = candidates
      .filter((t) => !claimed.has(t.id) && t.amount === e.amount && Math.abs(day(t.date) - day(e.date as string)) <= 3 * DAY)
      .sort((x, y) => Math.abs(day(x.date) - day(e.date as string)) - Math.abs(day(y.date) - day(e.date as string)))[0];
    if (!match) continue;
    claimed.add(match.id);
    await api.updateTransaction(match.id, { imported_id: id });
    (e as { adopted?: boolean }).adopted = true;
    adopted++;
  }
  return adopted;
}

/**
 * Plaid changed these after they were imported (the bank corrected an amount, date or
 * description). Actual's import matches by id but keeps the old amount, so update them
 * directly. Returns the ids handled; ones not imported yet go through the normal import.
 */
async function applyCorrections(accountId: string, modified: PlaidTransaction[]): Promise<string[]> {
  if (!modified.length) return [];
  const byId = new Map(modified.map((t) => [`plaid:${t.transaction_id}`, t]));
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ account: accountId, imported_id: { $oneof: [...byId.keys()] } }).select(['id', 'imported_id', 'amount', 'date']),
  )) as { data: { id: string; imported_id: string; amount: number; date: string }[] };
  const done: string[] = [];
  for (const row of data) {
    const t = byId.get(row.imported_id)!;
    const amount = -Math.round(t.amount * 100);
    if (row.amount !== amount || row.date !== t.date) await api.updateTransaction(row.id, { amount, date: t.date });
    done.push(t.transaction_id);
  }
  return done;
}

/** Plaid withdrew these (a posted transaction the bank reversed): remove them from the account too. */
async function removeGone(accountId: string, removed: string[]) {
  if (!removed.length) return;
  const wanted = removed.map((id) => `plaid:${id}`);
  const { data } = (await api.aqlQuery(api.q('transactions').filter({ account: accountId, imported_id: { $oneof: wanted } }).select(['id']))) as { data: { id: string }[] };
  for (const t of data) await api.deleteTransaction(t.id);
}

function payeeOf(t: PlaidTransaction): string {
  return t.merchant_name?.trim() || t.counterparties?.find((c) => c.name?.trim())?.name?.trim() || t.name?.trim() || 'Unknown';
}

function toApiError(err: unknown, setup = false): unknown {
  if (err instanceof PlaidError) {
    if (setup && (err.code === 'INVALID_API_KEYS' || err.code === 'INVALID_FIELD' || err.type === 'INVALID_REQUEST')) return ApiError.validation(err.message);
    return new ApiError(502, 'bank_sync_failed', 'Plaid error', err.message);
  }
  return err;
}
