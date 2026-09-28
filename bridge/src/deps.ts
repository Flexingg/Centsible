import { AccountOps } from './actual/account-ops.js';
import { BankSyncOps } from './actual/bank-sync-ops.js';
import { BudgetOps } from './actual/budget-ops.js';
import type { ActualHost } from './actual/host.js';
import { PlanningOps } from './actual/planning-ops.js';
import { PlanOps } from './actual/plan-ops.js';
import { ReportOps } from './actual/report-ops.js';
import { StructureOps } from './actual/structure-ops.js';
import { TransactionOps } from './actual/transaction-ops.js';
import type { HouseholdStore } from './auth/store.js';
import { BankSyncBackfill } from './bank-sync-backfill.js';
import { BankSyncScheduler } from './bank-sync-scheduler.js';
import { SimpleFinKeyFile } from './actual/simplefin-client.js';
import type { BridgeConfig } from './config.js';
import type { Deps } from './http/server.js';
import { JobStore } from './jobs.js';
import type { SetupService } from './setup.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

/** Everything the HTTP layer needs, wired once (main.ts and the tests share it). */
export function createDeps(config: BridgeConfig, store: HouseholdStore, host: ActualHost, setup: SetupService, log: Logger): Deps {
  const jobs = new JobStore();
  const budgetOps = new BudgetOps(host);
  const keys = new SimpleFinKeyFile(config.dataDir);
  const bankSync = new BankSyncOps(host, store, keys);
  const backfill = new BankSyncBackfill(host, store, keys, log);
  return {
    config,
    store,
    host,
    setup,
    jobs,
    bankSync,
    backfill,
    scheduler: new BankSyncScheduler(host, store, bankSync, jobs, log, Date.now, backfill),
    ops: budgetOps,
    plan: new PlanOps(host, budgetOps),
    transactions: new TransactionOps(host),
    structure: new StructureOps(host),
    planning: new PlanningOps(host),
    accountOps: new AccountOps(host),
    reports: new ReportOps(host),
  };
}
