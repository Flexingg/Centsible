import { statfs } from 'node:fs/promises';
import type { ActualHost } from './actual/host.js';
import { ACTUAL_API_VERSION, BRIDGE_VERSION, compareVersions } from './actual/versions.js';
import type { HouseholdStore } from './auth/store.js';
import type { BridgeConfig } from './config.js';
import { ApiError } from './errors.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

export type Release = {
  version: string;
  /** The app's versionCode: CI numbers releases 0.2.<run> and builds versionCode <run>. */
  versionCode: number | null;
  publishedAt: string | null;
  url: string;
  apkUrl: string | null;
  notes: string | null;
};

const CHECK_EVERY_MS = 6 * 3600_000;

/** "0.2.21" / "v0.2.21" -> [0, 2, 21] for comparing. */
function parts(v: string) {
  return v.replace(/^v/, '').split(/[.-]/).map((x) => Number.parseInt(x, 10) || 0);
}
export function isNewer(candidate: string, current: string) {
  const a = parts(candidate);
  const b = parts(current);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) > (b[i] ?? 0);
  }
  return false;
}

/**
 * Health and updates for the household's server: versions, what's newest on GitHub (the
 * app's APK comes from the same release), and one-tap updates through the optional
 * updater container (Watchtower's HTTP API). The bridge never talks to Docker itself;
 * it only asks the updater, which holds the Docker socket.
 */
export class ServerOps {
  private release: { at: number; value: Release | null; actualLatest: string | null } | null = null;
  private readonly startedAt = Date.now();

  constructor(
    private readonly config: BridgeConfig,
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly log: Logger,
    private readonly fetcher: typeof fetch = fetch,
  ) {}

  get updaterConfigured() {
    return !!this.config.updater;
  }

  async status(refresh = false) {
    const { value: latest, actualLatest } = await this.latest(refresh);
    const disk = await statfs(this.config.dataDir).then((s) => ({ free: s.bavail * s.bsize, total: s.blocks * s.bsize })).catch(() => null);
    const lastUpdate = this.store.getSetting<{ requestedAt: string; from: string; result: string | null }>('server.last_update');
    return {
      bridge: {
        version: BRIDGE_VERSION,
        latest: latest?.version ?? null,
        updateAvailable: !!latest && isNewer(latest.version, BRIDGE_VERSION),
        uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      },
      actual: {
        version: this.host.actualServerVersion,
        pairedVersion: ACTUAL_API_VERSION,
        compatibility: compareVersions(ACTUAL_API_VERSION, this.host.actualServerVersion),
        newestRelease: actualLatest,
        connected: this.host.connected,
      },
      app: latest,
      updater: {
        configured: this.updaterConfigured,
        lastRequestedAt: lastUpdate?.requestedAt ?? null,
        lastUpdatedFrom: lastUpdate?.from ?? null,
        lastResult: lastUpdate?.result ?? null,
      },
      disk,
      checkedAt: this.release ? new Date(this.release.at).toISOString() : null,
    };
  }

  /**
   * Asks the updater to pull newer images for the bridge, Actual and the tunnel, and
   * restart them. The bridge is one of them, so this returns once the request is on its
   * way; the app watches /v1/health for the new version.
   */
  async update(): Promise<{ requested: true }> {
    const u = this.config.updater;
    if (!u) {
      throw ApiError.validation(
        'One-tap updates need the updater. Replace docker-compose.yml with the one from the newest release, then run: docker compose up -d',
      );
    }
    this.store.setSetting('server.last_update', { requestedAt: new Date().toISOString(), from: BRIDGE_VERSION, result: null });
    const url = `${u.url.replace(/\/+$/, '')}/v1/update`;
    // Watchtower answers when it has finished, by which time this process may be gone.
    const request = this.fetcher(url, { headers: { authorization: `Bearer ${u.token}` }, signal: AbortSignal.timeout(10 * 60_000) })
      .then(async (res) => {
        const result = res.ok ? 'done' : res.status === 429 ? 'An update is already running.' : `The updater answered HTTP ${res.status}.`;
        this.store.setSetting('server.last_update', { requestedAt: new Date().toISOString(), from: BRIDGE_VERSION, result });
        this.log.info({ status: res.status }, 'updater finished');
      })
      .catch((err: unknown) => {
        const result = `Couldn't reach the updater: ${err instanceof Error ? err.message : String(err)}`;
        this.store.setSetting('server.last_update', { requestedAt: new Date().toISOString(), from: BRIDGE_VERSION, result });
        this.log.warn({ err }, 'updater request failed');
      });
    // Fail fast when the updater isn't there at all; otherwise let it work.
    const early = await Promise.race([request.then(() => 'settled' as const), new Promise<'running'>((r) => setTimeout(() => r('running'), 3000))]);
    const result = this.store.getSetting<{ result: string | null }>('server.last_update')?.result;
    if (early === 'settled' && result && result !== 'done') throw new ApiError(502, 'update_failed', 'Update failed', result);
    return { requested: true };
  }

  /** The newest Centsible release (bridge + app) and Actual's newest, from GitHub; cached for hours. */
  private async latest(refresh: boolean) {
    if (!refresh && this.release && Date.now() - this.release.at < CHECK_EVERY_MS) return this.release;
    const get = (url: string) =>
      this.fetcher(url, { headers: { accept: 'application/vnd.github+json', 'user-agent': 'centsible-bridge' }, signal: AbortSignal.timeout(10_000) }).then(
        (r) => (r.ok ? (r.json() as Promise<Record<string, unknown>>) : null),
      );
    const [ours, actual] = await Promise.all([
      get(`https://api.github.com/repos/${this.config.releasesRepo ?? 'Flexingg/Centsible'}/releases/latest`).catch(() => null),
      get('https://api.github.com/repos/actualbudget/actual/releases/latest').catch(() => null),
    ]);
    let value: Release | null = this.release?.value ?? null;
    if (ours && typeof ours.tag_name === 'string') {
      const assets = (ours.assets as { name: string; browser_download_url: string }[] | undefined) ?? [];
      const version = ours.tag_name.replace(/^v/, '');
      const run = /^0\.2\.(\d+)$/.exec(version);
      value = {
        version,
        versionCode: run ? Number(run[1]) : null,
        publishedAt: (ours.published_at as string | null) ?? null,
        url: String(ours.html_url ?? ''),
        apkUrl: assets.find((a) => a.name.endsWith('.apk'))?.browser_download_url ?? null,
        notes: typeof ours.body === 'string' ? ours.body.slice(0, 4000) : null,
      };
    }
    const actualLatest = actual && typeof actual.tag_name === 'string' ? actual.tag_name.replace(/^v/, '') : (this.release?.actualLatest ?? null);
    this.release = { at: Date.now(), value, actualLatest };
    return this.release;
  }
}
