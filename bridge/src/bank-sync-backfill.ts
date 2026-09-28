import * as api from '@actual-app/api';
import { linkedAccounts, readSettings, SIMPLEFIN_DAILY_QUOTA, type BankSyncSettings } from './actual/bank-sync-ops.js';
import type { ActualHost } from './actual/host.js';
import { fetchWindow, SIMPLEFIN_MAX_WINDOW_DAYS, SimpleFinError, type SimpleFinKeyFile, type SimpleFinTransaction } from './actual/simplefin-client.js';
import type { HouseholdStore } from './auth/store.js';
import { ApiError } from './errors.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

const DAY = 24 * 3600_000;
/** Consecutive windows overlap by this much, as SimpleFIN recommends; imported ids dedupe the overlap. */
const OVERLAP_DAYS = 5;
const STEP_DAYS = SIMPLEFIN_MAX_WINDOW_DAYS - OVERLAP_DAYS;
/** Requests per rolling day left for scheduled and manual syncs while a backfill runs. */
const RESERVE = 8;
/** A year of windows with nothing in them: the bank has no older history to give. */
const EMPTY_WINDOWS_TO_STOP = 4;
/** After a network error, wait this long before trying the same window again. */
const RETRY_MS = 15 * 60_000;
export const BACKFILL_MAX_YEARS = 10;

export type BackfillStatus = 'running' | 'waiting' | 'done' | 'failed' | 'cancelled';
export type BackfillState = {
  budgetId: string;
  accountIds: string[];
  since: string;
  status: BackfillStatus;
  /** Where the walk has reached: everything from here to today has been fetched. */
  reachedDate: string;
  windowsDone: number;
  windowsTotal: number;
  transactionsFound: number;
  transactionsAdded: number;
  message: string | null;
  startedAt: string;
  updatedAt: string;
  /** Internal: exclusive end of the next window, and when a retry is allowed. */
  windowEnd: string;
  retryAt: string | null;
  emptyStreak: number;
};

const KEY = 'bank_sync.backfill';
const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10);
const utc = (d: string) => Date.parse(`${d}T00:00:00Z`);

/**
 * Pulls older SimpleFIN history than Actual's own sync reaches (Actual asks for the last
 * 90 days at most), walking back in 90-day windows, SimpleFIN Bridge's per-request limit.
 * One request covers every selected account. It spends at most the daily quota minus a
 * reserve for normal syncing, then waits and carries on by itself (the scheduler ticks
 * it every minute), so ten years can take a few days. Progress is saved after every
 * window, so a restart picks up where it stopped.
 *
 * Imported history lands before the account's opening balance, so the opening balance
 * moves back to the oldest imported transaction and is reduced by what was added. The
 * account's current balance (which the bank reports) doesn't change.
 */
export class BankSyncBackfill {
  private busy = false;

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly keys: SimpleFinKeyFile,
    private readonly log: Logger,
    private readonly now: () => number = Date.now,
  ) {}

  state(): BackfillState | null {
    return this.store.getSetting<BackfillState>(KEY);
  }

  async start(budgetId: string, opts: { years: number; accountIds?: string[] }): Promise<BackfillState> {
    const current = this.state();
    if (current && (current.status === 'running' || current.status === 'waiting')) {
      throw ApiError.validation('A history import is already in progress. Cancel it first to start another.');
    }
    if (!this.keys.read()) {
      throw ApiError.validation('Importing older history needs SimpleFIN connected through Centsible. Paste a new setup token from bridge.simplefin.org to reconnect (linked accounts stay linked).');
    }
    const years = Math.min(Math.max(Math.floor(opts.years), 1), BACKFILL_MAX_YEARS);
    const { accounts, oldest } = await this.host.withBudget(budgetId, 'read', async () => {
      const linked = (await linkedAccounts()).filter((a) => a.source === 'simpleFin' && !a.closed && a.externalId);
      const chosen = opts.accountIds?.length ? linked.filter((a) => opts.accountIds!.includes(a.id)) : linked;
      if (opts.accountIds?.some((id) => !linked.some((a) => a.id === id))) throw ApiError.validation('Choose accounts that are linked to SimpleFIN.');
      if (!chosen.length) throw ApiError.validation('No accounts are linked to SimpleFIN yet. Link one first.');
      // Each account's history already starts at its oldest transaction; the walk starts at
      // the latest of those so no account is left with a gap (the others just overlap).
      const dates = await Promise.all(chosen.map((a) => oldestTransaction(a.id)));
      return { accounts: chosen, oldest: dates.map((d) => d ?? iso(this.now())) };
    });
    const now = this.now();
    const startFrom = oldest.reduce((a, b) => (a > b ? a : b));
    const windowEnd = iso(Math.min(utc(startFrom) + (OVERLAP_DAYS + 1) * DAY, now + DAY));
    const since = iso(Date.UTC(new Date(now).getUTCFullYear() - years, new Date(now).getUTCMonth(), new Date(now).getUTCDate()));
    if (utc(windowEnd) <= utc(since)) throw ApiError.validation(`These accounts already have history back to ${startFrom}.`);
    const state: BackfillState = {
      budgetId,
      accountIds: accounts.map((a) => a.id),
      since,
      status: 'running',
      reachedDate: startFrom,
      windowsDone: 0,
      windowsTotal: Math.ceil((utc(windowEnd) - utc(since)) / (STEP_DAYS * DAY)),
      transactionsFound: 0,
      transactionsAdded: 0,
      message: null,
      startedAt: new Date(now).toISOString(),
      updatedAt: new Date(now).toISOString(),
      windowEnd,
      retryAt: null,
      emptyStreak: 0,
    };
    this.save(state);
    void this.tick();
    return state;
  }

  cancel(): BackfillState | null {
    const s = this.state();
    if (!s || (s.status !== 'running' && s.status !== 'waiting')) return s;
    return this.save({ ...s, status: 'cancelled', message: `Stopped. History back to ${s.reachedDate} was kept.` });
  }

  /** Fetches as many windows as the quota allows. Called every minute and after start. */
  async tick(): Promise<void> {
    if (this.busy || !this.host.connected) return;
    let s = this.state();
    if (!s || (s.status !== 'running' && s.status !== 'waiting')) return;
    if (s.retryAt && this.now() < Date.parse(s.retryAt)) return;
    this.busy = true;
    try {
      while (s && (s.status === 'running' || s.status === 'waiting')) {
        const left = SIMPLEFIN_DAILY_QUOTA - RESERVE - this.store.simpleFinRequestsToday();
        if (left <= 0) {
          if (s.status !== 'waiting' || s.retryAt) {
            s = this.save({ ...s, status: 'waiting', retryAt: null, message: "Paused to stay within SimpleFIN's daily limit. It carries on by itself as the limit frees up." });
          }
          return;
        }
        s = await this.step(s);
        if (this.state()?.status === 'cancelled') return; // cancelled while the window was in flight
      }
    } finally {
      this.busy = false;
    }
  }

  private async step(s: BackfillState): Promise<BackfillState> {
    const accessUrl = this.keys.read();
    if (!accessUrl) return this.save({ ...s, status: 'failed', message: 'SimpleFIN was disconnected. Reconnect it and start the import again.' });
    const end = utc(s.windowEnd);
    const start = Math.max(end - SIMPLEFIN_MAX_WINDOW_DAYS * DAY, utc(s.since));
    const external = await this.host.withBudget(s.budgetId, 'read', async () => {
      const linked = await linkedAccounts();
      return s.accountIds.map((id) => linked.find((a) => a.id === id)).filter((a) => a?.source === 'simpleFin' && a.externalId) as { id: string; externalId: string; name: string }[];
    });
    if (!external.length) return this.save({ ...s, status: 'failed', message: 'The accounts were unlinked, so the import stopped.' });

    let window;
    try {
      this.store.recordSimpleFinRequest();
      window = await fetchWindow(accessUrl, external.map((a) => a.externalId), new Date(start), new Date(end));
    } catch (err) {
      if (err instanceof SimpleFinError && err.forbidden) return this.save({ ...s, status: 'failed', message: err.message });
      const message = err instanceof Error ? err.message : String(err);
      this.log.warn({ err: message }, 'history import window failed; will retry');
      return this.save({ ...s, status: 'waiting', retryAt: new Date(this.now() + RETRY_MS).toISOString(), message: `${message} Retrying in 15 minutes.` });
    }

    let found = 0;
    let added = 0;
    await this.host.withBudget(s.budgetId, 'write', async () => {
      for (const acct of external) {
        const txns = (window.accounts.find((a) => a.id === acct.externalId)?.transactions ?? []).filter(
          (t) => !t.pending && t.posted > 0 && t.posted * 1000 >= start && t.posted * 1000 < end,
        );
        found += txns.length;
        if (txns.length) added += await importInto(acct.id, txns, await readSettings(acct.id));
      }
    });

    const emptyStreak = found ? 0 : s.emptyStreak + 1;
    const next = {
      ...s,
      status: 'running' as BackfillStatus,
      retryAt: null,
      windowsDone: s.windowsDone + 1,
      transactionsFound: s.transactionsFound + found,
      transactionsAdded: s.transactionsAdded + added,
      reachedDate: iso(start),
      windowEnd: iso(start + OVERLAP_DAYS * DAY),
      emptyStreak,
      message: window.messages.length ? `SimpleFIN says: ${window.messages.join(' ')}` : null,
    };
    if (start <= utc(s.since)) {
      return this.save({ ...next, status: 'done', message: `Done. Imported ${next.transactionsAdded} older transactions, back to ${s.since}.` });
    }
    if (emptyStreak >= EMPTY_WINDOWS_TO_STOP) {
      const oldest = iso(start + EMPTY_WINDOWS_TO_STOP * STEP_DAYS * DAY);
      return this.save({
        ...next,
        status: 'done',
        windowsTotal: next.windowsDone,
        message: `Done. Your bank didn't share anything older than about ${oldest}, so the import stopped there. Imported ${next.transactionsAdded} older transactions.`,
      });
    }
    return this.save(next);
  }

  private save(s: BackfillState): BackfillState {
    const next = { ...s, updatedAt: new Date(this.now()).toISOString() };
    this.store.setSetting(KEY, next);
    if (next.status !== 'running') this.log.info({ status: next.status, windows: next.windowsDone, added: next.transactionsAdded }, 'history import');
    return next;
  }
}

async function oldestTransaction(accountId: string): Promise<string | null> {
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ account: accountId, starting_balance_flag: false }).select(['date']).orderBy('date').limit(1),
  )) as { data: { date: string }[] };
  return data[0]?.date ?? null;
}

/**
 * Imports one window into one account the way Actual's SimpleFIN sync would (same
 * imported ids, field mapping and rules), then moves the opening balance behind it.
 */
async function importInto(accountId: string, txns: SimpleFinTransaction[], settings: BankSyncSettings): Promise<number> {
  if (!settings.importTransactions) return 0;
  const day = (s: number | undefined) => (s ? new Date(s * 1000).toISOString().slice(0, 10) : undefined);
  const rows = txns.map((t) => {
    // Field names as Actual's server hands them to its sync (payeeName, notes, postedDate...).
    const fields: Record<string, string | undefined> = {
      date: day(t.posted),
      postedDate: day(t.posted),
      transactedDate: day(t.transacted_at),
      payeeName: t.payee || undefined,
      notes: t.description || undefined,
    };
    const amount = Math.round(Number(t.amount) * 100);
    const m = settings.mapping[amount <= 0 ? 'payment' : 'deposit'];
    const notes = settings.importNotes ? fields[m.notes] : undefined;
    return {
      account: accountId,
      date: fields[m.date] ?? fields.date!,
      amount,
      payee_name: fields[m.payee] ?? fields.payeeName ?? fields.notes ?? 'Unknown',
      notes: notes ? notes.trim().replace(/#/g, '##') : undefined,
      imported_id: t.id,
      cleared: true,
    };
  });
  const res = (await api.importTransactions(accountId, rows, {
    defaultCleared: true,
    dryRun: false,
    reimportDeleted: false, // old history the owner deleted stays deleted
    payeeNameNormalization: 'original',
  } as Parameters<typeof api.importTransactions>[2])) as { added: string[]; errors: { message: string }[] };
  if (res.errors?.length) throw new Error(res.errors[0]!.message);
  if (!res.added.length) return 0;

  const { data: addedRows } = (await api.aqlQuery(
    api.q('transactions').filter({ id: { $oneof: res.added } }).select(['amount', 'date']).options({ splits: 'none' }),
  )) as { data: { amount: number; date: string }[] };
  const sum = addedRows.reduce((n, r) => n + r.amount, 0);
  const oldest = addedRows.reduce((d, r) => (r.date < d ? r.date : d), addedRows[0]?.date ?? '9999-12-31');
  const { data: opening } = (await api.aqlQuery(
    api.q('transactions').filter({ account: accountId, starting_balance_flag: true }).select(['id', 'amount', 'date']).limit(1),
  )) as { data: { id: string; amount: number; date: string }[] };
  if (opening[0]) {
    await api.updateTransaction(opening[0].id, { amount: opening[0].amount - sum, date: oldest < opening[0].date ? oldest : opening[0].date });
  }
  return addedRows.length;
}
