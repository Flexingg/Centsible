import type { ActualHost } from './actual/host.js';
import { SIMPLEFIN_DAILY_QUOTA, type BankSyncOps, type SyncSummary } from './actual/bank-sync-ops.js';
import type { HouseholdStore } from './auth/store.js';
import type { BankSyncBackfill } from './bank-sync-backfill.js';
import type { JobStore } from './jobs.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

/** Choices offered in the app. SimpleFIN refreshes bank data about once a day. */
export const SYNC_INTERVALS = [0, 2, 4, 6, 12, 24] as const;
/** Scheduled runs stop short of SimpleFIN's daily quota so manual syncs still work. */
const HEADROOM = 4;

export type ScheduleState = {
  intervalHours: number;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastResult: { newTransactions: number; accounts: number; errors: string[]; skipped: string | null } | null;
};

/**
 * Background bank sync, run by the bridge (always on) rather than each phone: every
 * [intervalHours] it syncs every budget that has linked accounts, through the same job
 * queue the app's "Sync now" uses.
 */
export class BankSyncScheduler {
  private timer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly ops: BankSyncOps,
    private readonly jobs: JobStore,
    private readonly log: Logger,
    private readonly now: () => number = Date.now,
    private readonly backfill?: BankSyncBackfill,
  ) {}

  start() {
    this.timer = setInterval(() => {
      void this.tick();
      void this.backfill?.tick(); // resumes a history import once SimpleFIN's quota frees up
    }, 60_000);
    this.timer.unref();
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
  }

  state(): ScheduleState {
    const intervalHours = this.store.getSetting<number>('bank_sync.interval_hours') ?? 0;
    const lastRunAt = this.store.getSetting<string>('bank_sync.last_run_at');
    const next = intervalHours ? (lastRunAt ? Date.parse(lastRunAt) + intervalHours * 3600_000 : this.now()) : null;
    return {
      intervalHours,
      lastRunAt,
      nextRunAt: next ? new Date(Math.max(next, this.now())).toISOString() : null,
      lastResult: this.store.getSetting<ScheduleState['lastResult']>('bank_sync.last_result'),
    };
  }

  setInterval(hours: number): ScheduleState {
    this.store.setSetting('bank_sync.interval_hours', hours);
    return this.state();
  }

  /** Runs a sync if one is due. Called every minute; tests call it directly. */
  async tick(): Promise<boolean> {
    const { intervalHours, lastRunAt } = this.state();
    if (!intervalHours || this.running || !this.host.connected) return false;
    if (lastRunAt && this.now() - Date.parse(lastRunAt) < intervalHours * 3600_000) return false;
    await this.run();
    return true;
  }

  async run(): Promise<ScheduleState['lastResult']> {
    this.running = true;
    try {
      this.store.setSetting('bank_sync.last_run_at', new Date(this.now()).toISOString());
      const used = this.store.simpleFinRequestsToday();
      if (used >= SIMPLEFIN_DAILY_QUOTA - HEADROOM) {
        const result = { newTransactions: 0, accounts: 0, errors: [], skipped: `Skipped: ${used} SimpleFIN requests in the last day (SimpleFIN allows about ${SIMPLEFIN_DAILY_QUOTA}).` };
        this.store.setSetting('bank_sync.last_result', result);
        this.log.warn(result, 'scheduled bank sync skipped');
        return result;
      }
      const result = { newTransactions: 0, accounts: 0, errors: [] as string[], skipped: null };
      for (const budget of await this.host.listBudgets()) {
        const summary = await this.runJob(budget.id).catch((err: unknown) => {
          const message = err instanceof Error ? err.message : String(err);
          // A budget with nothing linked isn't an error worth reporting.
          if (!/No accounts are linked/.test(message)) result.errors.push(`${budget.name}: ${message}`);
          return null;
        });
        if (!summary) continue;
        result.accounts += summary.accounts;
        result.newTransactions += summary.newTransactions;
        for (const r of summary.results) if (r.error) result.errors.push(`${r.name}: ${r.error}`);
      }
      this.store.setSetting('bank_sync.last_result', result);
      this.log.info(result, 'scheduled bank sync finished');
      return result;
    } finally {
      this.running = false;
    }
  }

  /** Same job as the app's button, so a phone opening the app sees it in progress. */
  private runJob(budgetId: string): Promise<SyncSummary> {
    return new Promise((resolve, reject) => {
      const existing = this.jobs.running('bank-sync', budgetId);
      const job = existing ?? this.jobs.start('bank-sync', budgetId, () => this.ops.syncAll(budgetId));
      const poll = setInterval(() => {
        if (job.status === 'running') return;
        clearInterval(poll);
        if (job.status === 'succeeded') resolve(job.result as SyncSummary);
        else reject(new Error(job.error ?? 'Bank sync failed'));
      }, 200);
    });
  }
}
