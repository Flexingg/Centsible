import { join } from 'node:path';
import * as api from '@actual-app/api';
import type { BridgeConfig } from '../config.js';
import { ApiError } from '../errors.js';
import { ACTUAL_API_VERSION, compareVersions, type Compatibility } from './versions.js';

type Lib = Awaited<ReturnType<typeof api.init>>;
type Logger = { debug: (o: unknown, m?: string) => void; info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

export type RemoteBudget = { id: string; name: string; encrypted: boolean };

/**
 * The only object that talks to @actual-app/api.
 *
 * The API is a process-wide singleton with one open budget at a time, so every call is
 * funnelled through a serial queue. Callers name the budget they need and the host
 * switches to it (cheap: files are cached locally and only new CRDT messages sync).
 */
export class ActualHost {
  private lib: Lib | null = null;
  private queue: Promise<unknown> = Promise.resolve();
  private openBudgetId: string | null = null;
  private lastSyncAt = 0;
  private serverVersion: string | null = null;
  private readonly disabledFeatures = new Set<string>();

  constructor(
    private readonly config: BridgeConfig,
    private readonly log: Logger,
  ) {}

  async start(): Promise<void> {
    routeConsoleToDebug(this.log);
    const { serverUrl, password, sessionToken } = this.config.actual;
    const dataDir = join(this.config.dataDir, 'actual');
    this.lib = await api.init(
      sessionToken ? { serverURL: serverUrl, sessionToken, dataDir } : { serverURL: serverUrl, password: password!, dataDir },
    );
    await this.refreshServerVersion();
  }

  async stop(): Promise<void> {
    await this.enqueue(async () => {
      if (this.lib) await api.shutdown();
      this.lib = null;
      this.openBudgetId = null;
    });
  }

  get apiVersion() {
    return ACTUAL_API_VERSION;
  }

  get actualServerVersion() {
    return this.serverVersion;
  }

  compatibility(): Compatibility {
    return compareVersions(ACTUAL_API_VERSION, this.serverVersion);
  }

  isFeatureDisabled(feature: string) {
    return this.disabledFeatures.has(feature);
  }

  async refreshServerVersion(): Promise<void> {
    try {
      const res = await api.getServerVersion();
      this.serverVersion = 'version' in res ? res.version : null;
    } catch (err) {
      this.log.warn({ err }, 'could not read Actual server version');
      this.serverVersion = null;
    }
  }

  /** Remote budget files on the Actual server. */
  listBudgets(): Promise<RemoteBudget[]> {
    return this.enqueue(async () => {
      this.requireLib();
      const files = await api.getBudgets();
      const seen = new Map<string, RemoteBudget>();
      for (const f of files) {
        if (!f.groupId || f.state !== 'remote') continue;
        seen.set(f.groupId, { id: f.groupId, name: f.name, encrypted: !!f.encryptKeyId });
      }
      return [...seen.values()].sort((a, b) => a.name.localeCompare(b.name));
    });
  }

  /**
   * Run `fn` with `budgetId` open. Reads sync first when the local copy is stale;
   * writes always sync afterwards so other household devices see them.
   */
  withBudget<T>(budgetId: string, mode: 'read' | 'write', fn: (lib: Lib) => Promise<T>): Promise<T> {
    return this.enqueue(async () => {
      const lib = this.requireLib();
      await this.open(budgetId);
      if (mode === 'read' && Date.now() - this.lastSyncAt > this.config.syncMaxAgeMs) {
        await this.trySync();
      }
      const result = await fn(lib);
      if (mode === 'write') await this.trySync();
      return result;
    });
  }

  /**
   * Call an internal loot-core handler (adapter tier 3). If an Actual upgrade removed or
   * renamed it, the feature is switched off instead of crashing, and reported via
   * capabilities.
   */
  async internal<T = unknown>(feature: string, lib: Lib, handler: string, args: unknown): Promise<T> {
    if (this.disabledFeatures.has(feature)) throw ApiError.featureUnavailable(feature);
    try {
      return (await (lib.send as (n: string, a: unknown) => Promise<T>)(handler, args)) as T;
    } catch (err) {
      if (err instanceof Error && /handler is not a function|unknown method/i.test(err.message)) {
        this.disabledFeatures.add(feature);
        this.log.warn({ feature, handler }, 'internal Actual handler missing; feature disabled');
        throw ApiError.featureUnavailable(feature);
      }
      throw err;
    }
  }

  private async open(budgetId: string): Promise<void> {
    if (this.openBudgetId === budgetId) return;
    const password = this.config.actual.budgetPasswords[budgetId];
    const files = await api.getBudgets();
    const local = files.find((f) => f.groupId === budgetId && f.id && f.state !== 'remote');
    const remote = files.find((f) => f.groupId === budgetId && f.state === 'remote');
    if (!local && !remote) throw ApiError.notFound(`Budget ${budgetId} not found on the Actual server`);

    // Loading replaces whatever was open, so forget it until this one succeeds.
    this.openBudgetId = null;
    try {
      try {
        if (local?.id) {
          await api.loadBudget(local.id);
          await api.sync();
        } else {
          await api.downloadBudget(budgetId, password ? { password } : undefined);
        }
      } catch (err) {
        if (encryptionProblem(err)) throw err;
        this.log.warn({ err, budgetId }, 'loading cached budget failed; downloading a fresh copy');
        await api.downloadBudget(budgetId, password ? { password } : undefined);
      }
    } catch (err) {
      const problem = encryptionProblem(err);
      if (problem === 'missing') {
        throw ApiError.budgetEncrypted(
          `This budget uses end-to-end encryption. Add "${budgetId}=<password>" to ACTUAL_BUDGET_PASSWORDS on the bridge and restart it.`,
        );
      }
      if (problem === 'wrong') {
        throw ApiError.budgetEncrypted('The encryption password set for this budget in ACTUAL_BUDGET_PASSWORDS is wrong.');
      }
      throw err;
    }
    this.openBudgetId = budgetId;
    this.lastSyncAt = Date.now();
    this.log.info({ budgetId }, 'budget opened');
  }

  private async trySync(): Promise<void> {
    try {
      await api.sync();
      this.lastSyncAt = Date.now();
    } catch (err) {
      // Serve the local copy; the next request retries. Writes are already in the
      // local CRDT log and will be pushed on the next successful sync.
      this.log.warn({ err }, 'sync with Actual server failed; serving local copy');
    }
  }

  private requireLib(): Lib {
    if (!this.lib) throw ApiError.actualUnavailable('Bridge is not connected to Actual');
    return this.lib;
  }

  private enqueue<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.queue.then(fn, fn);
    this.queue = run.catch(() => undefined);
    return run;
  }
}

/** @actual-app/api logs breadcrumbs through console.*; keep them out of the bridge's logs. */
function routeConsoleToDebug(log: Logger) {
  const toDebug = (...args: unknown[]) => log.debug({ actual: args.map(String).join(' ') });
  console.log = toDebug;
  console.info = toDebug;
  console.debug = toDebug;
}

/**
 * Actual tags encryption failures with error codes (verified on 26.9.0); the message
 * check is a fallback in case a release drops the code.
 */
function encryptionProblem(err: unknown): 'missing' | 'wrong' | null {
  if (!(err instanceof Error)) return null;
  const code = (err as Error & { code?: string }).code;
  if (code === 'missing-key' || /is encrypted\. Please provide a password/i.test(err.message)) return 'missing';
  if (code === 'decrypt-failure' || code === 'invalid-key' || /unable to decrypt/i.test(err.message)) return 'wrong';
  return null;
}
