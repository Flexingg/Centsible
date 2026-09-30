import { AccountOps } from './actual/account-ops.js';
import { BankSyncOps } from './actual/bank-sync-ops.js';
import { BudgetOps } from './actual/budget-ops.js';
import type { ActualHost } from './actual/host.js';
import { PlanningOps } from './actual/planning-ops.js';
import { PlanOps } from './actual/plan-ops.js';
import { InsightsOps } from './actual/insights-ops.js';
import { BackupService } from './backups.js';
import { ServerOps } from './server-ops.js';
import { ReportOps } from './actual/report-ops.js';
import { StructureOps } from './actual/structure-ops.js';
import { RuleTools } from './actual/rule-tools.js';
import { AutomationOps } from './actual/automation-ops.js';
import { ReviewOps } from './actual/review-ops.js';
import { TargetOps } from './actual/target-ops.js';
import { TransferOps } from './actual/transfer-ops.js';
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
  const keys = new SimpleFinKeyFile(config.dataDir, config.actualDataDir);
  const bankSync = new BankSyncOps(host, store, keys);
  const transfers = new TransferOps(host, store);
  bankSync.afterSync = (budgetId, lib) => transfers.autoLink(budgetId, lib);
  const backfill = new BankSyncBackfill(host, store, keys, log);
  const backups = new BackupService(config, host, store, log);
  return {
    config,
    store,
    host,
    setup,
    jobs,
    bankSync,
    backfill,
    backups,
    server: new ServerOps(config, host, store, log),
    scheduler: new BankSyncScheduler(host, store, bankSync, jobs, log, Date.now, backfill, backups),
    ops: budgetOps,
    plan: new PlanOps(host, budgetOps),
    insights: new InsightsOps(host, store),
    transactions: new TransactionOps(host),
    review: new ReviewOps(host, store),
    transfers,
    targets: new TargetOps(host, store),
    automations: new AutomationOps(host),
    ruleTools: new RuleTools(host),
    structure: new StructureOps(host),
    planning: new PlanningOps(host),
    accountOps: new AccountOps(host),
    reports: new ReportOps(host),
  };
}
