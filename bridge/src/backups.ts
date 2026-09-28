import { mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import AdmZip from 'adm-zip';
import type { ActualHost } from './actual/host.js';
import type { HouseholdStore } from './auth/store.js';
import type { BridgeConfig } from './config.js';
import { ApiError } from './errors.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

/** Choices offered in the app (hours; 0 = off). Daily is the default. */
export const BACKUP_INTERVALS = [0, 6, 12, 24, 168] as const;
const DEFAULT_INTERVAL = 24;
const DEFAULT_KEEP = 14;

export type BackupBudget = { budgetId: string; name: string; file: string; size: number };
export type Backup = {
  id: string;
  createdAt: string;
  trigger: 'scheduled' | 'manual';
  size: number;
  budgets: BackupBudget[];
  /** Budgets that couldn't be exported (e.g. encrypted without a password), with why. */
  skipped: { budgetId: string; name: string; reason: string }[];
  /** The bridge's own database (household members, devices, settings) is in it too. */
  household: boolean;
};

const ID = /^\d{8}T\d{6}Z$/;
const FILE = /^[\w.-]+\.(zip|sqlite)$/;
const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'budget';

/**
 * Backups of every budget as Actual's own export (.zip, the same file Actual's web app
 * makes and imports), plus the bridge's household database, in bridge-data/backups.
 * On a schedule and on demand; the oldest beyond [keep] are removed. Restoring imports a
 * backup as a new budget beside the original, so nothing is ever overwritten.
 */
export class BackupService {
  private running = false;
  private readonly dir: string;

  constructor(
    config: BridgeConfig,
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly log: Logger,
    private readonly now: () => number = Date.now,
  ) {
    this.dir = join(config.dataDir, 'backups');
  }

  settings() {
    return {
      intervalHours: this.store.getSetting<number>('backups.interval_hours') ?? DEFAULT_INTERVAL,
      keep: this.store.getSetting<number>('backups.keep') ?? DEFAULT_KEEP,
    };
  }

  setSettings(patch: { intervalHours?: number; keep?: number }) {
    if (patch.intervalHours !== undefined) this.store.setSetting('backups.interval_hours', patch.intervalHours);
    if (patch.keep !== undefined) this.store.setSetting('backups.keep', patch.keep);
    return this.overview();
  }

  async overview() {
    const { intervalHours, keep } = this.settings();
    const items = await this.list();
    const lastRunAt = this.store.getSetting<string>('backups.last_run_at');
    const next = intervalHours ? (lastRunAt ? Date.parse(lastRunAt) + intervalHours * 3600_000 : this.now()) : null;
    return {
      intervalHours,
      keep,
      intervals: [...BACKUP_INTERVALS],
      lastRunAt,
      lastError: this.store.getSetting<string>('backups.last_error'),
      nextRunAt: next ? new Date(Math.max(next, this.now())).toISOString() : null,
      totalSize: items.reduce((s, b) => s + b.size, 0),
      items,
    };
  }

  /** Called every minute by the scheduler. */
  async tick(): Promise<boolean> {
    const { intervalHours } = this.settings();
    if (!intervalHours || this.running || !this.host.connected) return false;
    const last = this.store.getSetting<string>('backups.last_run_at');
    if (last && this.now() - Date.parse(last) < intervalHours * 3600_000) return false;
    await this.run('scheduled').catch(() => undefined);
    return true;
  }

  async run(trigger: Backup['trigger']): Promise<Backup> {
    if (this.running) throw ApiError.validation('A backup is already running.');
    this.running = true;
    const createdAt = new Date(this.now()).toISOString();
    const id = createdAt.replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
    const folder = join(this.dir, id);
    try {
      this.store.setSetting('backups.last_run_at', createdAt);
      await mkdir(folder, { recursive: true });
      const budgets: BackupBudget[] = [];
      const skipped: Backup['skipped'] = [];
      for (const b of await this.host.listBudgets()) {
        try {
          const zip = await this.host.exportBudget(b.id);
          const file = `${slug(b.name)}-${b.id.slice(0, 8)}.zip`;
          await writeFile(join(folder, file), zip);
          budgets.push({ budgetId: b.id, name: b.name, file, size: zip.byteLength });
        } catch (err) {
          const reason = err instanceof ApiError ? (err.detail ?? err.message) : err instanceof Error ? err.message : String(err);
          skipped.push({ budgetId: b.id, name: b.name, reason });
        }
      }
      await this.store.backupTo(join(folder, 'household.sqlite'));
      const backup: Backup = {
        id,
        createdAt,
        trigger,
        size: 0,
        budgets,
        skipped,
        household: true,
      };
      await writeFile(join(folder, 'manifest.json'), JSON.stringify(backup, null, 2));
      this.store.setSetting('backups.last_error', skipped.length ? `Skipped ${skipped.map((s) => `${s.name} (${s.reason})`).join(', ')}` : null);
      await this.prune();
      this.log.info({ id, budgets: budgets.length, skipped: skipped.length }, 'backup finished');
      return { ...backup, size: await folderSize(folder) };
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.store.setSetting('backups.last_error', message);
      this.log.warn({ err }, 'backup failed');
      await rm(folder, { recursive: true, force: true });
      throw err;
    } finally {
      this.running = false;
    }
  }

  async list(): Promise<Backup[]> {
    const names = await readdir(this.dir).catch(() => [] as string[]);
    const out: Backup[] = [];
    for (const name of names.filter((n) => ID.test(n))) {
      const manifest = await readFile(join(this.dir, name, 'manifest.json'), 'utf8').then((t) => JSON.parse(t) as Backup).catch(() => null);
      if (manifest) out.push({ ...manifest, size: await folderSize(join(this.dir, name)) });
    }
    return out.sort((a, b) => b.id.localeCompare(a.id));
  }

  async remove(id: string) {
    await this.folder(id);
    await rm(join(this.dir, id), { recursive: true, force: true });
  }

  /** A file from a backup, for downloading to the phone. */
  async file(id: string, file: string): Promise<{ data: Buffer; name: string }> {
    if (!FILE.test(file)) throw ApiError.notFound('No such file');
    const folder = await this.folder(id);
    const data = await readFile(join(folder, file)).catch(() => null);
    if (!data) throw ApiError.notFound('No such file');
    return { data, name: `centsible-${id}-${file}` };
  }

  /** Imports a budget from a backup as a new budget, "<name> (restored <date>)". */
  async restore(id: string, budgetId: string): Promise<{ budgetId: string; name: string }> {
    const backup = (await this.list()).find((b) => b.id === id);
    if (!backup) throw ApiError.notFound('Backup not found');
    const entry = backup.budgets.find((b) => b.budgetId === budgetId);
    if (!entry) throw ApiError.notFound('That budget is not in this backup');
    const zip = new AdmZip(await readFile(join(this.dir, id, entry.file)));
    const name = `${entry.name} (restored ${backup.createdAt.slice(0, 10)})`;
    const meta = zip.getEntry('metadata.json');
    if (meta) {
      const parsed = JSON.parse(meta.getData().toString('utf8')) as Record<string, unknown>;
      parsed.budgetName = name;
      zip.updateFile(meta, Buffer.from(JSON.stringify(parsed)));
    }
    const restoredId = await this.host.importBudget(new Uint8Array(zip.toBuffer()));
    this.log.info({ backup: id, from: budgetId, to: restoredId }, 'budget restored as a copy');
    return { budgetId: restoredId, name };
  }

  private async prune() {
    const { keep } = this.settings();
    const items = await this.list();
    for (const old of items.slice(keep)) await rm(join(this.dir, old.id), { recursive: true, force: true });
  }

  private async folder(id: string) {
    if (!ID.test(id)) throw ApiError.notFound('Backup not found');
    const folder = join(this.dir, id);
    if (!(await stat(folder).catch(() => null))?.isDirectory()) throw ApiError.notFound('Backup not found');
    return folder;
  }
}

async function folderSize(folder: string) {
  let total = 0;
  for (const f of await readdir(folder).catch(() => [] as string[])) total += (await stat(join(folder, f)).catch(() => null))?.size ?? 0;
  return total;
}
