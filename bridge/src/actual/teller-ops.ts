import * as api from '@actual-app/api';
import type { HouseholdStore, TellerEnrollmentRow, TellerLinkRow } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { AccountSyncResult } from './bank-sync-ops.js';
import { linkedAccounts } from './bank-sync-ops.js';
import type { ActualHost } from './host.js';
import {
  checkCredentials,
  describeTellerCode,
  TELLER_API,
  TELLER_ENVIRONMENTS,
  TellerClient,
  TellerError,
  type TellerAccount,
  type TellerEnvironment,
  type TellerTransaction,
  type TellerVault,
} from './teller-client.js';

type Send = (name: string, args?: unknown) => Promise<unknown>;
const settle = () => new Promise<void>((r) => setTimeout(r, 0));
const DAY = 24 * 3600_000;
/** Each sync looks back this far before the last one, so late-posting transactions still land (imported ids dedupe the overlap). */
const OVERLAP_DAYS = 14;
const CONFIG_KEY = 'teller.config';

export type TellerConfig = { applicationId: string; environment: TellerEnvironment };
export type TellerEnrollmentView = { id: string; institution: string; status: TellerEnrollmentRow['status']; message: string | null; linkedAccounts: number; updatedAt: string };
export type TellerStatus = {
  configured: boolean;
  applicationId: string | null;
  environment: TellerEnvironment | null;
  hasCertificate: boolean;
  enrollments: TellerEnrollmentView[];
};
export type TellerAccountView = {
  id: string;
  enrollmentId: string;
  institution: string;
  name: string;
  type: string;
  subtype: string;
  lastFour: string | null;
  /** The bank's ledger balance as Teller reports it, in cents (its sign is the bank's). */
  balance: number | null;
  currency: string;
  linkedAccountId: string | null;
  linkedAccountName: string | null;
  flip: boolean;
};
export type PreviewTransaction = { date: string; payee: string; amount: number; pending: boolean };

const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const cents = (amount: string | null | undefined) => {
  const n = Number(amount);
  return amount === null || amount === undefined || amount === '' || !Number.isFinite(n) ? null : Math.round(n * 100);
};

/**
 * Teller as a second bank sync provider beside SimpleFIN. Actual has no Teller support, so
 * the bridge does the syncing itself: it pulls posted transactions from Teller and hands
 * them to Actual's importTransactions, which runs the budget's rules and matches them
 * against what's already there (so switching an account over from SimpleFIN doesn't
 * duplicate anything). Imported ids are "teller:<transaction id>".
 *
 * Teller's amount sign is whatever the bank reports, and banks differ for credit cards, so
 * each link has a "flip" switch, chosen while looking at a preview before anything imports.
 */
export class TellerOps {
  /** The last account listing per enrollment, so linking right after doesn't ask Teller again. */
  private listing = new Map<string, { at: number; accounts: TellerAccount[] }>();

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly vault: TellerVault,
    private readonly apiUrl: string = TELLER_API,
  ) {}

  private config(): TellerConfig | null {
    return this.store.getSetting<TellerConfig>(CONFIG_KEY);
  }

  private client() {
    return new TellerClient(this.vault.credentials(), this.apiUrl);
  }

  /** True when at least one account in [budgetId] syncs through Teller. */
  hasLinks(budgetId?: string) {
    return this.store.tellerLinks(budgetId).length > 0;
  }

  links(budgetId: string): TellerLinkRow[] {
    return this.store.tellerLinks(budgetId);
  }

  status(): TellerStatus {
    const config = this.config();
    const links = this.store.tellerLinks();
    return {
      configured: config != null && (config.environment === 'sandbox' || this.vault.credentials() != null),
      applicationId: config?.applicationId ?? null,
      environment: config?.environment ?? null,
      hasCertificate: this.vault.credentials() != null,
      enrollments: this.store.tellerEnrollments().map((e) => ({
        id: e.id,
        institution: e.institution,
        status: e.status,
        message: e.status === 'ok' ? null : e.status === 'revoked' ? 'Teller no longer accepts this bank connection. Remove it and connect the bank again.' : describeTellerCode(e.statusCode),
        linkedAccounts: links.filter((l) => l.enrollmentId === e.id).length,
        updatedAt: e.updatedAt,
      })),
    };
  }

  /**
   * The page the app opens in a WebView to run Teller Connect. Its result goes to the app
   * through the WebView's CentsibleTeller interface; the app then posts it to
   * /v1/bank-sync/teller/enrollments with its own credentials.
   */
  connectPage(enrollmentId?: string): string {
    const config = this.config();
    if (!config) throw ApiError.validation('Set up Teller first.');
    if (enrollmentId && !/^[A-Za-z0-9_-]{1,200}$/.test(enrollmentId)) throw ApiError.validation('Invalid enrollment id');
    const cfg = JSON.stringify({ applicationId: config.applicationId, environment: config.environment, enrollmentId: enrollmentId ?? null }).replace(/</g, '\\u003c');
    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Connect a bank</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:32px 24px;background:#f7f3ea;color:#14221c}p{font-size:16px;line-height:1.5}@media (prefers-color-scheme:dark){body{background:#0e1a15;color:#e9efe9}}</style>
</head><body><p id="msg">Opening Teller…</p>
<script src="https://cdn.teller.io/connect/connect.js"></script>
<script>
(function () {
  var cfg = ${cfg};
  var msg = document.getElementById('msg');
  function post(kind, data) { if (window.CentsibleTeller) window.CentsibleTeller.post(kind, JSON.stringify(data || {})); }
  if (!window.TellerConnect) { msg.textContent = "Couldn't load Teller Connect. Check the phone's connection and try again."; post('failure', { message: 'Teller Connect did not load' }); return; }
  var options = {
    applicationId: cfg.applicationId,
    environment: cfg.environment,
    products: ['transactions', 'balance'],
    onSuccess: function (e) {
      msg.textContent = 'Connected. Saving…';
      post('success', { accessToken: e.accessToken, enrollmentId: e.enrollment.id, institution: (e.enrollment.institution && e.enrollment.institution.name) || '', userId: e.user ? e.user.id : null });
    },
    onExit: function () { msg.textContent = 'Closed.'; post('exit'); },
    onFailure: function (f) { msg.textContent = (f && f.message) || 'Teller Connect failed.'; post('failure', { message: f && f.message, code: f && f.code }); }
  };
  if (cfg.enrollmentId) options.enrollmentId = cfg.enrollmentId;
  TellerConnect.setup(options).open();
})();
</script></body></html>`;
  }

  /** Owners. The application id from the Teller dashboard, its environment and (outside the sandbox) its certificate. */
  configure(input: { applicationId: string; environment: TellerEnvironment; certificate?: string; privateKey?: string }): TellerStatus {
    const applicationId = input.applicationId.trim();
    if (!/^app_[A-Za-z0-9]+$/.test(applicationId)) throw ApiError.validation('That isn\'t a Teller application id. Copy it from the Teller dashboard (it starts with "app_").');
    if (!TELLER_ENVIRONMENTS.includes(input.environment)) throw ApiError.validation('Choose sandbox, development or production.');
    const hasCert = input.certificate?.trim() || input.privateKey?.trim();
    if (hasCert) {
      const creds = { certificate: input.certificate?.trim() ?? '', privateKey: input.privateKey?.trim() ?? '' };
      const problem = checkCredentials(creds);
      if (problem) throw ApiError.validation(problem);
      this.vault.setCredentials(creds);
    } else if (input.environment !== 'sandbox' && !this.vault.credentials()) {
      throw ApiError.validation('Development and production need the certificate and private key from the Teller dashboard (Certificates → Create certificate).');
    }
    this.store.setSetting(CONFIG_KEY, { applicationId, environment: input.environment } satisfies TellerConfig);
    this.listing.clear();
    return this.status();
  }

  /** Owners. Forgets Teller entirely: certificate, bank logins and links (imported transactions stay). */
  async reset(): Promise<void> {
    for (const e of this.store.tellerEnrollments()) {
      const token = this.vault.token(e.id);
      if (token) await this.client().revoke(token).catch(() => undefined);
    }
    this.store.clearTeller();
    this.store.setSetting(CONFIG_KEY, null);
    this.vault.clear();
    this.listing.clear();
  }

  /**
   * Saves a bank login from Teller Connect's onSuccess. Checked against Teller right away,
   * so a wrong environment or missing certificate fails here rather than at the next sync.
   * Reconnecting an enrollment (same id) just marks it healthy again.
   */
  async addEnrollment(input: { accessToken: string; enrollmentId: string; institution: string; userId?: string | null }): Promise<TellerEnrollmentView> {
    if (!this.config()) throw ApiError.validation('Set up Teller first (application id and certificate).');
    const token = input.accessToken.trim();
    let accounts: TellerAccount[];
    try {
      accounts = await this.client().accounts(token);
    } catch (err) {
      throw toApiError(err);
    }
    this.vault.setToken(input.enrollmentId, token);
    const institution = input.institution.trim() || accounts[0]?.institution?.name || 'Bank';
    this.store.saveTellerEnrollment({ id: input.enrollmentId, institution, userId: input.userId ?? null });
    this.listing.set(input.enrollmentId, { at: Date.now(), accounts });
    for (const l of this.store.tellerLinks()) if (l.enrollmentId === input.enrollmentId) this.store.updateTellerLink(l.budgetId, l.accountId, { lastError: null });
    return this.status().enrollments.find((e) => e.id === input.enrollmentId)!;
  }

  /** Stops syncing a bank login everywhere and asks Teller to revoke it. Imported transactions stay. */
  async removeEnrollment(id: string): Promise<void> {
    if (!this.store.tellerEnrollment(id)) throw ApiError.notFound('That bank connection is gone already.');
    const token = this.vault.token(id);
    if (token) await this.client().revoke(token).catch(() => undefined);
    this.vault.setToken(id, null);
    this.store.deleteTellerEnrollment(id);
    this.listing.delete(id);
  }

  /** Every account Teller can see across the household's bank logins, with what each is linked to in [budgetId]. */
  async listAccounts(budgetId: string, refresh = true): Promise<TellerAccountView[]> {
    const out: { account: TellerAccount; institution: string; balance: number | null }[] = [];
    const errors: string[] = [];
    for (const e of this.store.tellerEnrollments()) {
      const token = this.vault.token(e.id);
      if (!token) continue;
      try {
        const accounts = await this.accountsOf(e.id, token, refresh);
        for (const a of accounts.filter((x) => x.status !== 'closed')) {
          const bal = refresh ? await this.client().balances(token, a.id).catch(() => null) : null;
          out.push({ account: a, institution: a.institution?.name ?? e.institution, balance: cents(bal?.ledger ?? bal?.available) });
        }
      } catch (err) {
        this.noteFailure(e.id, err);
        errors.push(`${e.institution}: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
    if (!out.length && errors.length) throw new ApiError(502, 'bank_sync_failed', 'Teller error', errors.join(' '));
    const links = this.store.tellerLinks(budgetId);
    const names = await this.host.withBudget(budgetId, 'read', async () => new Map((await linkedAccounts()).map((a) => [a.id, a.name])));
    return out.map(({ account: a, institution, balance }) => {
      const link = links.find((l) => l.tellerAccountId === a.id);
      return {
        id: a.id,
        enrollmentId: a.enrollment_id,
        institution,
        name: a.name,
        type: a.type,
        subtype: a.subtype,
        lastFour: a.last_four ?? null,
        balance,
        currency: a.currency ?? 'USD',
        linkedAccountId: link?.accountId ?? null,
        linkedAccountName: link ? (names.get(link.accountId) ?? null) : null,
        flip: link?.flip ?? false,
      };
    });
  }

  /**
   * The account's latest posted transactions, signed the way they would import, so the
   * person can check that purchases come out negative before linking.
   */
  async preview(tellerAccountId: string, flip: boolean): Promise<{ transactions: PreviewTransaction[] }> {
    const { token } = await this.find(tellerAccountId);
    let txns: TellerTransaction[];
    try {
      txns = await this.client().transactions(token, tellerAccountId, iso(Date.now() - 60 * DAY), 20);
    } catch (err) {
      throw toApiError(err);
    }
    return {
      transactions: txns.slice(0, 8).map((t) => ({
        date: t.date,
        payee: payeeOf(t),
        amount: (cents(t.amount) ?? 0) * (flip ? -1 : 1),
        pending: t.status === 'pending',
      })),
    };
  }

  /**
   * Links a Teller account to an existing account in the budget and imports from
   * [startDate]. If the account was syncing through SimpleFIN, that link is removed first
   * (two providers feeding one account would race each other).
   */
  async link(budgetId: string, input: { tellerAccountId: string; accountId: string; flip: boolean; startDate?: string }): Promise<{ accountId: string; result: AccountSyncResult }> {
    const { enrollmentId, account } = await this.find(input.tellerAccountId);
    const startDate = input.startDate ?? iso(Date.now() - 30 * DAY);
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const local = (await linkedAccounts()).find((a) => a.id === input.accountId);
      if (!local) throw ApiError.notFound('Account not found');
      if (local.closed) throw ApiError.validation('Reopen the account before linking it.');
      const existing = this.store.tellerLinks(budgetId);
      const taken = existing.find((l) => l.tellerAccountId === input.tellerAccountId);
      if (taken) throw ApiError.validation(`That Teller account already feeds another account. Unlink it there first.`);
      if (existing.some((l) => l.accountId === input.accountId)) throw ApiError.validation(`"${local.name}" already syncs through Teller. Unlink it first.`);
      if (local.source) {
        await (lib.send as Send)('account-unlink', { id: input.accountId });
        await settle();
      }
      this.store.addTellerLink({
        budgetId,
        accountId: input.accountId,
        tellerAccountId: input.tellerAccountId,
        enrollmentId,
        tellerAccountName: account.name,
        flip: input.flip,
        startDate,
      });
      const [result] = await this.syncIn(budgetId, [input.accountId]);
      return { accountId: input.accountId, result: result! };
    });
  }

  /** Stops Teller syncing for an account (its transactions stay). False when it wasn't linked. */
  unlink(budgetId: string, accountId: string): boolean {
    if (!this.store.tellerLink(budgetId, accountId)) return false;
    this.store.deleteTellerLink(budgetId, accountId);
    return true;
  }

  setFlip(budgetId: string, accountId: string, flip: boolean) {
    if (!this.store.tellerLink(budgetId, accountId)) throw ApiError.notFound("That account doesn't sync through Teller.");
    this.store.updateTellerLink(budgetId, accountId, { flip });
  }

  /** What account listings show for Teller-linked accounts (Actual itself doesn't know about Teller). */
  overlay(budgetId: string): Map<string, { syncSource: string; lastSync: string | null; bankSyncStatus: string | null }> {
    const enrollments = new Map(this.store.tellerEnrollments().map((e) => [e.id, e]));
    return new Map(
      this.store.tellerLinks(budgetId).map((l) => {
        const e = enrollments.get(l.enrollmentId);
        const status = !e || e.status !== 'ok' ? 'reauth-required' : l.lastError ? 'attention-required' : 'ok';
        return [l.accountId, { syncSource: 'teller', lastSync: l.lastSyncAt, bankSyncStatus: status }];
      }),
    );
  }

  /**
   * Imports new posted transactions for the budget's Teller-linked accounts (all, or
   * [only]). Must run inside a withBudget session for [budgetId]; one account failing
   * doesn't stop the others.
   */
  async syncIn(budgetId: string, only?: string[]): Promise<AccountSyncResult[]> {
    const links = this.store.tellerLinks(budgetId).filter((l) => !only || only.includes(l.accountId));
    if (!links.length) return [];
    const accounts = await linkedAccounts();
    const client = this.client();
    const results: AccountSyncResult[] = [];
    for (const l of links) {
      const local = accounts.find((a) => a.id === l.accountId);
      const name = local?.name ?? l.tellerAccountName;
      const result: AccountSyncResult = { accountId: l.accountId, name, newTransactions: 0, matchedTransactions: 0, error: null, status: 'ok' };
      results.push(result);
      if (!local || local.closed) {
        result.error = local ? 'The account is closed.' : 'The account is gone from the budget.';
        result.status = 'account-missing';
        continue;
      }
      const token = this.vault.token(l.enrollmentId);
      if (!token) {
        result.error = 'The bridge lost this bank connection (teller.json is missing). Connect the bank again.';
        result.status = 'reauth-required';
        this.store.updateTellerLink(budgetId, l.accountId, { lastError: result.error });
        continue;
      }
      const since = l.lastSyncAt ? iso(Math.max(Date.parse(`${l.startDate}T00:00:00Z`), Date.parse(l.lastSyncAt) - OVERLAP_DAYS * DAY)) : l.startDate;
      try {
        const txns = (await client.transactions(token, l.tellerAccountId, since)).filter((t) => t.status !== 'pending');
        const entities = txns.flatMap((t) => {
          const amount = cents(t.amount);
          if (amount === null || !/^\d{4}-\d{2}-\d{2}$/.test(t.date)) return [];
          return [
            {
              date: t.date,
              amount: l.flip ? -amount : amount,
              payee_name: payeeOf(t),
              imported_payee: t.description ?? payeeOf(t),
              imported_id: `teller:${t.id}`,
              cleared: true,
            } as Parameters<typeof api.importTransactions>[1][number],
          ];
        });
        const adopted = await adoptExisting(l.accountId, entities, since);
        result.matchedTransactions = adopted;
        const fresh = entities.filter((e) => e !== null && !(e as { adopted?: boolean }).adopted);
        if (fresh.length) {
          const res = (await api.importTransactions(l.accountId, fresh, { defaultCleared: true })) as unknown as { added?: unknown[]; updated?: unknown[]; errors?: { message?: string }[] };
          result.newTransactions = res.added?.length ?? 0;
          result.matchedTransactions += res.updated?.length ?? 0;
          if (res.errors?.length) result.error = res.errors.map((e) => e.message ?? 'Import error').join('; ');
        }
        this.store.updateTellerLink(budgetId, l.accountId, { lastSyncAt: new Date().toISOString(), lastError: result.error });
        if (this.store.tellerEnrollment(l.enrollmentId)?.status !== 'ok') this.store.setTellerEnrollmentStatus(l.enrollmentId, 'ok', null);
      } catch (err) {
        this.noteFailure(l.enrollmentId, err);
        result.error = err instanceof Error ? err.message : String(err);
        result.status = err instanceof TellerError ? (err.disconnected || err.revoked ? 'reauth-required' : err.status === 410 ? 'account-missing' : err.status === 429 ? 'rate-limit-exceeded' : 'attention-required') : 'attention-required';
        this.store.updateTellerLink(budgetId, l.accountId, { lastError: result.error });
      }
    }
    await settle();
    return results;
  }

  private noteFailure(enrollmentId: string, err: unknown) {
    if (!(err instanceof TellerError)) return;
    if (err.disconnected) this.store.setTellerEnrollmentStatus(enrollmentId, 'disconnected', err.code);
    else if (err.revoked) this.store.setTellerEnrollmentStatus(enrollmentId, 'revoked', err.code);
  }

  private async accountsOf(enrollmentId: string, token: string, refresh: boolean): Promise<TellerAccount[]> {
    const cached = this.listing.get(enrollmentId);
    if (!refresh && cached && Date.now() - cached.at < 10 * 60_000) return cached.accounts;
    const accounts = await this.client().accounts(token);
    this.listing.set(enrollmentId, { at: Date.now(), accounts });
    if (this.store.tellerEnrollment(enrollmentId)?.status !== 'ok') this.store.setTellerEnrollmentStatus(enrollmentId, 'ok', null);
    return accounts;
  }

  /** Which bank login a Teller account belongs to (from the recent listing, else asking Teller). */
  private async find(tellerAccountId: string): Promise<{ enrollmentId: string; token: string; account: TellerAccount }> {
    for (const pass of [false, true]) {
      for (const e of this.store.tellerEnrollments()) {
        const token = this.vault.token(e.id);
        if (!token) continue;
        const accounts = pass ? await this.accountsOf(e.id, token, true).catch((err: unknown) => (this.noteFailure(e.id, err), [] as TellerAccount[])) : (this.listing.get(e.id)?.accounts ?? []);
        const account = accounts.find((a) => a.id === tellerAccountId);
        if (account) return { enrollmentId: e.id, token, account };
      }
    }
    throw ApiError.notFound("That account isn't in any connected bank. Connect the bank in Bank sync → Teller.");
  }
}

type ImportEntity = Parameters<typeof api.importTransactions>[1][number];

/**
 * Actual only fuzzy-matches an imported transaction against ones that have no imported id,
 * so after moving an account from SimpleFIN (or a file import) to Teller, the same purchase
 * would come in twice. Before importing, each Teller transaction not seen before takes over
 * one existing transaction from another source with the same amount within 3 days: that
 * transaction gets the Teller id (so later syncs match it by id) instead of a duplicate
 * being added. One-to-one, so two genuine identical purchases stay two.
 */
async function adoptExisting(accountId: string, entities: ImportEntity[], since: string): Promise<number> {
  if (!entities.length) return 0;
  const from = iso(Date.parse(`${since}T00:00:00Z`) - 4 * DAY);
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ account: accountId, date: { $gte: from }, is_parent: false }).select(['id', 'date', 'amount', 'imported_id']),
  )) as { data: { id: string; date: string; amount: number; imported_id: string | null }[] };
  const ids = new Set(data.map((t) => t.imported_id).filter(Boolean));
  const candidates = data.filter((t) => t.imported_id && !t.imported_id.startsWith('teller:'));
  const claimed = new Set<string>();
  let adopted = 0;
  const near = (a: string, b: string) => Math.abs(Date.parse(`${a}T00:00:00Z`) - Date.parse(`${b}T00:00:00Z`)) <= 3 * DAY;
  // Closest date first, so a purchase on the 3rd takes the 3rd's match, not the 1st's.
  for (const e of entities) {
    const id = e.imported_id as string;
    if (ids.has(id)) continue; // seen before: Actual matches it by id
    const match = candidates
      .filter((t) => !claimed.has(t.id) && t.amount === e.amount && near(t.date, e.date as string))
      .sort((x, y) => Math.abs(Date.parse(x.date) - Date.parse(e.date as string)) - Math.abs(Date.parse(y.date) - Date.parse(e.date as string)))[0];
    if (!match) continue;
    claimed.add(match.id);
    await api.updateTransaction(match.id, { imported_id: id });
    (e as { adopted?: boolean }).adopted = true;
    adopted++;
  }
  return adopted;
}

function payeeOf(t: TellerTransaction): string {
  return t.details?.counterparty?.name?.trim() || t.description?.trim() || 'Unknown';
}

function toApiError(err: unknown): unknown {
  if (err instanceof TellerError) return new ApiError(502, 'bank_sync_failed', 'Teller error', err.message);
  return err;
}

