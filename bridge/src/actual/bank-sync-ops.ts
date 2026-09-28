import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { ActualHost, Lib } from './host.js';

type Raw = Record<string, unknown>;
type Send = (name: string, args?: unknown) => Promise<unknown>;
const send = (lib: Lib): Send => lib.send as Send;
const settle = () => new Promise<void>((r) => setTimeout(r, 0));

/** SimpleFIN Bridge asks apps to stay at or under 24 requests a day. */
export const SIMPLEFIN_DAILY_QUOTA = 24;
/** Fields Actual can map SimpleFIN transactions from (see Actual's custom-sync-mappings). */
export const SIMPLEFIN_FIELDS = { date: ['date', 'postedDate', 'transactedDate'], payee: ['payeeName', 'notes'], notes: ['notes', 'payeeName'] } as const;

export type ExternalAccount = { id: string; name: string; institution: string | null; balance: number; currency: string; linkedAccountId: string | null; linkedAccountName: string | null };
export type FieldMapping = { date: string; payee: string; notes: string };
export type BankSyncSettings = {
  importTransactions: boolean;
  importPending: boolean;
  importNotes: boolean;
  reimportDeleted: boolean;
  updateDates: boolean;
  mapping: { payment: FieldMapping; deposit: FieldMapping };
};
export type AccountSyncResult = { accountId: string; name: string; newTransactions: number; matchedTransactions: number; error: string | null; status: string | null };
export type SyncSummary = { accounts: number; newTransactions: number; results: AccountSyncResult[]; simplefinRequests: number };

const DEFAULT_MAPPING: FieldMapping = { date: 'date', payee: 'payeeName', notes: 'notes' };
const BOOLEAN_PREFS = {
  importTransactions: ['sync-import-transactions', true],
  importPending: ['sync-import-pending', true],
  importNotes: ['sync-import-notes', true],
  reimportDeleted: ['sync-reimport-deleted', true],
  updateDates: ['sync-update-dates', false],
} as const;

/** Rejected setup token or access key: what to tell the owner. */
const SIMPLEFIN_ERRORS: Record<string, string> = {
  INVALID_ACCESS_TOKEN: "SimpleFIN didn't accept the credentials. Setup tokens work only once: create a new one at bridge.simplefin.org and connect again.",
  SERVER_DOWN: "Couldn't reach SimpleFIN. Try again in a few minutes.",
  TIMED_OUT: 'SimpleFIN took too long to answer. Try again in a few minutes.',
};

/**
 * Everything Actual does with SimpleFIN, through Actual's own handlers (so it stays in
 * step with Actual): the server-wide setup token, listing SimpleFIN accounts, linking
 * them to new or existing accounts, unlinking, per-account sync options, and syncing.
 * Every call that reaches SimpleFIN is logged against its daily quota.
 */
export class BankSyncOps {
  /** The last account listing, so linking right after doesn't cost another request. */
  private listing: { at: number; accounts: Raw[] } | null = null;

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
  ) {}

  requestsToday() {
    return this.store.simpleFinRequestsToday();
  }

  status(): Promise<{ configured: boolean }> {
    return this.host.withServer(async (lib) => {
      const res = (await send(lib)('simplefin-status')) as { configured?: boolean; error?: string };
      return { configured: res?.configured === true };
    });
  }

  /** Stores a new setup token and claims it right away, so a bad token fails here, not at the next sync. */
  async connect(setupToken: string): Promise<{ configured: true; accounts: number }> {
    const token = setupToken.trim();
    let decoded = '';
    try {
      decoded = Buffer.from(token, 'base64').toString();
      const url = new URL(decoded);
      if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new Error('scheme');
    } catch {
      throw ApiError.validation("That isn't a SimpleFIN setup token. Copy the whole token from bridge.simplefin.org (it's a long line of letters and numbers).");
    }
    await this.host.withServer(async (lib) => {
      await send(lib)('secret-set', { name: 'simplefin_accessKey', value: null });
      await send(lib)('secret-set', { name: 'simplefin_token', value: token });
    });
    this.listing = null;
    try {
      const accounts = await this.fetchAccounts();
      return { configured: true, accounts: accounts.length };
    } catch (err) {
      await this.reset(); // don't leave a dead token behind
      throw err;
    }
  }

  async reset(): Promise<void> {
    this.listing = null;
    await this.host.withServer(async (lib) => {
      await send(lib)('secret-set', { name: 'simplefin_token', value: null });
      await send(lib)('secret-set', { name: 'simplefin_accessKey', value: null });
    });
  }

  /** SimpleFIN accounts, with which account in this budget each is linked to. */
  async listAccounts(budgetId: string, refresh = true): Promise<ExternalAccount[]> {
    const raw = refresh || !this.listing || Date.now() - this.listing.at > 10 * 60_000 ? await this.fetchAccounts() : this.listing.accounts;
    return this.host.withBudget(budgetId, 'read', async () => {
      const linked = await linkedAccounts();
      return raw.map((a) => {
        const local = linked.find((l) => l.externalId === String(a.id) && l.source === 'simpleFin');
        return {
          id: String(a.id),
          name: String(a.name ?? 'Account'),
          institution: ((a.org as Raw | undefined)?.name as string | undefined) ?? null,
          balance: Math.round(Number(a.balance ?? 0) * 100),
          currency: String(a.currency ?? 'USD'),
          linkedAccountId: local?.id ?? null,
          linkedAccountName: local?.name ?? null,
        };
      });
    });
  }

  /**
   * Links a SimpleFIN account to an existing account (keeps its history; new bank
   * transactions are matched against it) or creates a new one. Actual imports recent
   * transactions right away.
   */
  async link(budgetId: string, externalId: string, opts: { accountId?: string; offBudget?: boolean; startDate?: string }): Promise<{ accountId: string }> {
    let raw = this.listing?.accounts.find((a) => String(a.id) === externalId);
    if (!raw) raw = (await this.fetchAccounts()).find((a) => String(a.id) === externalId);
    if (!raw) throw ApiError.notFound('That account is no longer in your SimpleFIN connection. Check it at bridge.simplefin.org.');
    const org = (raw.org ?? {}) as Raw;
    const externalAccount = {
      account_id: String(raw.id),
      name: String(raw.name ?? 'Account'),
      institution: (org.name as string | undefined) ?? null,
      orgDomain: (org.domain as string | undefined) ?? null,
      orgId: (org.id as string | undefined) ?? null,
      balance: raw.balance,
    };
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const linked = await linkedAccounts();
      const existing = linked.find((l) => l.externalId === externalId);
      if (existing) throw ApiError.validation(`That SimpleFIN account is already linked to "${existing.name}". Unlink it first.`);
      if (opts.accountId) {
        const target = linked.find((l) => l.id === opts.accountId);
        if (!target) throw ApiError.notFound('Account not found');
        if (target.closed) throw ApiError.validation('Reopen the account before linking it.');
        if (target.source) throw ApiError.validation(`"${target.name}" is already linked to a bank. Unlink it first.`);
      }
      this.store.recordSimpleFinRequest(); // linking syncs the account once
      const res = await send(lib)('simplefin-accounts-link', {
        externalAccount,
        upgradingId: opts.accountId,
        offBudget: opts.offBudget ?? false,
        ...(opts.startDate ? { startingDate: opts.startDate } : {}),
      });
      if (res !== 'ok') throw ApiError.validation(`Actual couldn't link the account: ${JSON.stringify(res)}`);
      await settle();
      const now = await linkedAccounts();
      const id = now.find((l) => l.externalId === externalId && l.source === 'simpleFin')?.id;
      if (!id) throw new Error('Linked account not found after linking');
      return { accountId: id };
    });
  }

  unlink(budgetId: string, accountId: string): Promise<void> {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const acct = (await linkedAccounts()).find((l) => l.id === accountId);
      if (!acct) throw ApiError.notFound('Account not found');
      if (!acct.source) return;
      await send(lib)('account-unlink', { id: accountId });
      await settle();
    });
  }

  settings(budgetId: string, accountId: string): Promise<BankSyncSettings> {
    return this.host.withBudget(budgetId, 'read', async () => readSettings(accountId));
  }

  updateSettings(budgetId: string, accountId: string, patch: Partial<BankSyncSettings>): Promise<BankSyncSettings> {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      if (!(await linkedAccounts()).some((l) => l.id === accountId)) throw ApiError.notFound('Account not found');
      const save = (id: string, value: string) => send(lib)('preferences/save', { id, value });
      for (const [key, [prefix]] of Object.entries(BOOLEAN_PREFS) as [keyof typeof BOOLEAN_PREFS, readonly [string, boolean]][]) {
        const v = patch[key];
        if (typeof v === 'boolean') await save(`${prefix}-${accountId}`, String(v));
      }
      if (patch.mapping) {
        for (const kind of ['payment', 'deposit'] as const) {
          const m = patch.mapping[kind];
          for (const field of ['date', 'payee', 'notes'] as const) {
            if (!(SIMPLEFIN_FIELDS[field] as readonly string[]).includes(m[field])) {
              throw ApiError.validation(`"${m[field]}" can't be used for ${field}. Choose one of: ${SIMPLEFIN_FIELDS[field].join(', ')}.`);
            }
          }
        }
        await save(`custom-sync-mappings-${accountId}`, JSON.stringify(patch.mapping));
      }
      await settle();
      return readSettings(accountId);
    });
  }

  /**
   * Syncs every linked account the way Actual's "Sync all" does: SimpleFIN accounts in
   * one batched request (quota-friendly), other providers one by one.
   */
  syncAll(budgetId: string): Promise<SyncSummary> {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const linked = (await linkedAccounts()).filter((l) => l.source && !l.closed);
      const simplefin = linked.filter((l) => l.source === 'simpleFin');
      const others = linked.filter((l) => l.source !== 'simpleFin');
      if (!linked.length) throw ApiError.validation('No accounts are linked to a bank yet. Link one in Settings → Bank sync.');
      const results: AccountSyncResult[] = [];
      if (simplefin.length) {
        this.store.recordSimpleFinRequest();
        const res = (await send(lib)('simplefin-batch-sync', { ids: simplefin.map((a) => a.id) })) as { accountId: string; res: SyncResponse }[];
        for (const r of res ?? []) results.push(toResult(linked, r.accountId, r.res));
      }
      // One call per account, so counts and errors belong to the right account.
      for (const a of others) {
        const res = (await send(lib)('accounts-bank-sync', { ids: [a.id] })) as SyncResponse;
        results.push(toResult(linked, a.id, res));
      }
      await settle();
      return withStatuses(results, simplefin.length ? 1 : 0);
    });
  }

  /** One account now (a SimpleFIN account costs one request). */
  syncOne(budgetId: string, accountId: string): Promise<SyncSummary> {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const linked = await linkedAccounts();
      const acct = linked.find((l) => l.id === accountId);
      if (!acct) throw ApiError.notFound('Account not found');
      if (!acct.source) throw ApiError.validation("This account isn't linked to a bank. Link it in Settings → Bank sync.");
      if (acct.source === 'simpleFin') this.store.recordSimpleFinRequest();
      const res = (await send(lib)('accounts-bank-sync', { ids: [accountId] })) as SyncResponse;
      await settle();
      return withStatuses([toResult(linked, accountId, res)], acct.source === 'simpleFin' ? 1 : 0);
    });
  }

  private async fetchAccounts(): Promise<Raw[]> {
    this.store.recordSimpleFinRequest();
    const res = (await this.host.withServer((lib) => send(lib)('simplefin-accounts'))) as { accounts?: Raw[]; error_code?: string; reason?: string; error?: string };
    if (res?.error === 'unauthorized') throw ApiError.actualUnavailable('The bridge is signed out of Actual.');
    const code = res?.error_code;
    if (code) throw new ApiError(502, 'bank_sync_failed', 'SimpleFIN error', SIMPLEFIN_ERRORS[code] ?? res.reason ?? `SimpleFIN error: ${code}`);
    if (!Array.isArray(res?.accounts)) throw new ApiError(502, 'bank_sync_failed', 'SimpleFIN error', 'SimpleFIN is not set up. Connect it with a setup token first.');
    this.listing = { at: Date.now(), accounts: res.accounts };
    return res.accounts;
  }
}

type SyncResponse = { errors?: { accountId?: string; message?: string }[]; newTransactions?: string[]; matchedTransactions?: string[] };
type Linked = { id: string; name: string; externalId: string | null; source: string | null; closed: boolean };

async function linkedAccounts(): Promise<Linked[]> {
  const { data } = (await api.aqlQuery(api.q('accounts').select(['id', 'name', 'account_id', 'account_sync_source', 'closed']))) as { data: Raw[] };
  return data.map((a) => ({
    id: String(a.id),
    name: String(a.name),
    externalId: (a.account_id as string | null) ?? null,
    source: typeof a.account_sync_source === 'string' && a.account_sync_source ? a.account_sync_source : null,
    closed: Boolean(a.closed),
  }));
}

function toResult(linked: Linked[], accountId: string, res: SyncResponse | undefined): AccountSyncResult {
  const errors = res?.errors ?? [];
  return {
    accountId,
    name: linked.find((l) => l.id === accountId)?.name ?? accountId,
    newTransactions: res?.newTransactions?.length ?? 0,
    matchedTransactions: res?.matchedTransactions?.length ?? 0,
    error: errors[0]?.message ?? null,
    status: null,
  };
}

/** Adds Actual's recorded per-account status: ok, reauth-required, attention-required, rate-limit-exceeded, ... */
async function withStatuses(results: AccountSyncResult[], simplefinRequests: number): Promise<SyncSummary> {
  const { data } = (await api.aqlQuery(api.q('accounts').select(['id', 'bank_sync_status']))) as { data: Raw[] };
  for (const r of results) r.status = (data.find((a) => a.id === r.accountId)?.bank_sync_status as string | null) ?? null;
  return {
    accounts: results.length,
    newTransactions: results.reduce((n, r) => n + r.newTransactions, 0),
    results,
    simplefinRequests,
  };
}

async function readSettings(accountId: string): Promise<BankSyncSettings> {
  const { data } = (await api.aqlQuery(api.q('preferences').select(['id', 'value']))) as { data: { id: string; value: string }[] };
  const pref = (id: string) => data.find((p) => p.id === id)?.value;
  const bools = Object.fromEntries(
    Object.entries(BOOLEAN_PREFS).map(([key, [prefix, fallback]]) => {
      const v = pref(`${prefix}-${accountId}`);
      return [key, v === undefined || v === null ? fallback : String(v) === 'true'];
    }),
  ) as Omit<BankSyncSettings, 'mapping'>;
  let mapping = { payment: { ...DEFAULT_MAPPING }, deposit: { ...DEFAULT_MAPPING } };
  const raw = pref(`custom-sync-mappings-${accountId}`);
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as Partial<Record<'payment' | 'deposit', Partial<FieldMapping>>>;
      mapping = { payment: { ...DEFAULT_MAPPING, ...parsed.payment }, deposit: { ...DEFAULT_MAPPING, ...parsed.deposit } };
    } catch {
      /* Actual would reject it too; show defaults */
    }
  }
  return { ...bools, mapping };
}
