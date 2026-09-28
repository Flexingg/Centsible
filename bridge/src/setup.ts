import { randomInt, timingSafeEqual, createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ActualHost } from './actual/host.js';
import type { HouseholdStore } from './auth/store.js';
import type { BridgeConfig } from './config.js';
import { ApiError } from './errors.js';

type Logger = { info: (o: unknown, m?: string) => void; warn: (o: unknown, m?: string) => void };

/**
 * - ready: the bridge is signed in to Actual.
 * - needs-password: a brand-new Actual server; the owner picks its password.
 * - needs-login: Actual has a password the bridge doesn't know yet.
 * - unsupported: Actual uses OpenID; set ACTUAL_SESSION_TOKEN instead.
 * - unreachable: nothing answers at ACTUAL_SERVER_URL.
 */
export type ActualStatus = 'ready' | 'needs-password' | 'needs-login' | 'unsupported' | 'unreachable';

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O or 1/I

/** Where a password entered in the app is kept, so ACTUAL_PASSWORD can stay out of .env. */
export function savedActualPasswordPath(dataDir: string) {
  return join(dataDir, 'actual-password');
}

export function readSavedActualPassword(dataDir: string): string | undefined {
  const file = savedActualPasswordPath(dataDir);
  return existsSync(file) ? readFileSync(file, 'utf8').trim() || undefined : undefined;
}

/**
 * First-run setup from the app. While the household has no owner, the bridge holds a
 * one-time setup code: BRIDGE_SETUP_CODE from .env, or a generated one printed in the
 * logs. Knowing the bridge URL isn't enough to claim it (the URL is public); seeing the
 * code proves access to the server.
 */
export class SetupService {
  private code: string | null = null;

  constructor(
    private readonly config: BridgeConfig,
    private readonly store: HouseholdStore,
    private readonly host: ActualHost,
    private readonly log: Logger,
    private readonly startHost: () => Promise<void>,
  ) {}

  get needsOwner() {
    return this.store.listMembers().length === 0;
  }

  /** Call once at startup. Returns the code to show, or null when already set up. */
  prepare(): string | null {
    if (!this.needsOwner) return null;
    this.code = normalize(this.config.setupCode ?? '') || generateCode();
    return this.config.setupCode ?? `${this.code.slice(0, 4)}-${this.code.slice(4)}`;
  }

  async actualStatus(): Promise<ActualStatus> {
    if (this.host.connected) return 'ready';
    try {
      const res = await fetch(`${this.config.actual.serverUrl}/account/needs-bootstrap`, { signal: AbortSignal.timeout(5000) });
      const body = (await res.json()) as { data?: { bootstrapped?: boolean; loginMethod?: string } };
      if (!body.data?.bootstrapped) return 'needs-password';
      return body.data.loginMethod === 'password' ? 'needs-login' : 'unsupported';
    } catch {
      return 'unreachable';
    }
  }

  checkCode(input: string): boolean {
    if (!this.code) return false;
    const a = createHash('sha256').update(normalize(input)).digest();
    const b = createHash('sha256').update(this.code).digest();
    return timingSafeEqual(a, b);
  }

  /** Signs the bridge in to Actual (setting its first password if needed), then creates the owner. */
  async claim(input: { displayName: string; actualPassword?: string }) {
    if (!this.needsOwner) throw ApiError.forbidden('This bridge already has an owner. Ask them to invite you.');
    const status = await this.actualStatus();
    const url = this.config.actual.serverUrl;
    if (status === 'unreachable') throw ApiError.actualUnavailable(`The bridge can't reach Actual at ${url}. Check ACTUAL_SERVER_URL and that Actual is running.`);
    if (status === 'unsupported') throw ApiError.validation('Your Actual server signs in with OpenID. Set ACTUAL_SESSION_TOKEN for the bridge, restart it, and try again.');
    if (status !== 'ready') {
      const password = input.actualPassword ?? '';
      if (status === 'needs-password') {
        if (password.length < 8) throw ApiError.validation('Choose an Actual password of at least 8 characters.');
        const res = await actualPost(url, '/account/bootstrap', { password });
        if (res.status !== 'ok') throw ApiError.validation(`Actual didn't accept that password (${res.reason ?? 'unknown reason'}).`);
      } else {
        const res = await actualPost(url, '/account/login', { loginMethod: 'password', password });
        if (res.status !== 'ok') throw ApiError.validation("That isn't your Actual server's password.");
      }
      writeFileSync(savedActualPasswordPath(this.config.dataDir), password, { mode: 0o600 });
      this.config.actual.password = password;
      await this.startHost();
    }

    const owner = this.store.createMember({ displayName: input.displayName, role: 'owner' });
    this.code = null;
    this.log.info({ owner: owner.id }, 'bridge set up; owner created');
    return owner;
  }
}

function normalize(code: string) {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function generateCode() {
  return Array.from({ length: 8 }, () => ALPHABET[randomInt(ALPHABET.length)]).join('');
}

async function actualPost(serverUrl: string, path: string, body: unknown): Promise<{ status: string; reason?: string }> {
  const res = await fetch(`${serverUrl}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  return (await res.json()) as { status: string; reason?: string };
}
