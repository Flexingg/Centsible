import { createHash, randomBytes, randomUUID } from 'node:crypto';
import Database from 'better-sqlite3';

export type Role = 'owner' | 'member' | 'viewer';

export type Member = { id: string; displayName: string; role: Role; disabled: boolean; budgetIds: string[] };
export type Device = { id: string; memberId: string; name: string; platform: string; createdAt: string; lastSeenAt: string | null };
export type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };

const MIGRATIONS: string[] = [
  `CREATE TABLE members (
     id TEXT PRIMARY KEY,
     display_name TEXT NOT NULL,
     role TEXT NOT NULL CHECK (role IN ('owner','member','viewer')),
     disabled INTEGER NOT NULL DEFAULT 0,
     created_at TEXT NOT NULL
   );
   CREATE TABLE member_budgets (
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     budget_id TEXT NOT NULL,
     PRIMARY KEY (member_id, budget_id)
   );
   CREATE TABLE devices (
     id TEXT PRIMARY KEY,
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     name TEXT NOT NULL,
     platform TEXT NOT NULL,
     created_at TEXT NOT NULL,
     last_seen_at TEXT,
     revoked_at TEXT
   );
   CREATE TABLE pairing_codes (
     code_hash TEXT PRIMARY KEY,
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     created_by TEXT,
     expires_at INTEGER NOT NULL,
     used_at INTEGER
   );
   CREATE TABLE tokens (
     token_hash TEXT PRIMARY KEY,
     device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
     kind TEXT NOT NULL CHECK (kind IN ('access','refresh')),
     expires_at INTEGER NOT NULL,
     rotated_at INTEGER
   );
   CREATE INDEX tokens_device ON tokens(device_id);
   CREATE TABLE idempotency (
     member_id TEXT NOT NULL,
     key TEXT NOT NULL,
     route TEXT NOT NULL,
     status INTEGER NOT NULL,
     body TEXT NOT NULL,
     created_at INTEGER NOT NULL,
     PRIMARY KEY (member_id, key)
   );
   CREATE TABLE audit_log (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     at TEXT NOT NULL,
     member_id TEXT,
     device_id TEXT,
     action TEXT NOT NULL,
     budget_id TEXT,
     target TEXT,
     detail TEXT
   );`,
  // 2: bridge-wide settings (bank sync schedule) and a log of SimpleFIN requests, which
  // SimpleFIN Bridge caps at ~24 a day.
  `CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
   CREATE TABLE simplefin_requests (at INTEGER NOT NULL);
   CREATE INDEX simplefin_requests_at ON simplefin_requests(at);`,
  // 3: each person's review inbox: a watermark (Actual's sort_order, which is the
  // creation time in ms) plus transactions reviewed one by one since then.
  `CREATE TABLE review_state (
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     budget_id TEXT NOT NULL,
     since REAL NOT NULL,
     PRIMARY KEY (member_id, budget_id)
   );
   CREATE TABLE reviewed (
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     budget_id TEXT NOT NULL,
     transaction_id TEXT NOT NULL,
     at TEXT NOT NULL,
     PRIMARY KEY (member_id, budget_id, transaction_id)
   );`,
  // 4: each person's Home layout, and category colors/emoji shared by the household
  // (Actual has neither).
  `CREATE TABLE member_prefs (
     member_id TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
     key TEXT NOT NULL,
     value TEXT NOT NULL,
     PRIMARY KEY (member_id, key)
   );
   CREATE TABLE category_appearance (
     budget_id TEXT NOT NULL,
     category_id TEXT NOT NULL,
     color TEXT,
     emoji TEXT,
     PRIMARY KEY (budget_id, category_id)
   );`,
  // 5: Teller bank logins ("enrollments") and which Teller account feeds which account in a
  // budget. Access tokens and the client certificate are not here: they live in teller.json
  // beside this file, because this database goes into backups.
  `CREATE TABLE teller_enrollments (
     id TEXT PRIMARY KEY,
     institution TEXT NOT NULL,
     user_id TEXT,
     status TEXT NOT NULL,
     status_code TEXT,
     created_at TEXT NOT NULL,
     updated_at TEXT NOT NULL
   );
   CREATE TABLE teller_links (
     budget_id TEXT NOT NULL,
     account_id TEXT NOT NULL,
     teller_account_id TEXT NOT NULL,
     enrollment_id TEXT NOT NULL,
     teller_account_name TEXT NOT NULL,
     flip INTEGER NOT NULL DEFAULT 0,
     start_date TEXT NOT NULL,
     last_sync_at TEXT,
     last_error TEXT,
     PRIMARY KEY (budget_id, account_id),
     UNIQUE (budget_id, teller_account_id)
   );`,
  // 6: Teller closed its API in 2026; Plaid replaces it. Bank logins ("items") and which
  // Plaid account feeds which account in a budget, with each link's own sync cursor. Access
  // tokens and the secret live in plaid.json beside this file, because this database goes
  // into backups.
  `DROP TABLE teller_links;
   DROP TABLE teller_enrollments;
   CREATE TABLE plaid_items (
     id TEXT PRIMARY KEY,
     institution TEXT NOT NULL,
     status TEXT NOT NULL,
     status_code TEXT,
     created_at TEXT NOT NULL,
     updated_at TEXT NOT NULL
   );
   CREATE TABLE plaid_links (
     budget_id TEXT NOT NULL,
     account_id TEXT NOT NULL,
     plaid_account_id TEXT NOT NULL,
     item_id TEXT NOT NULL,
     plaid_account_name TEXT NOT NULL,
     start_date TEXT NOT NULL,
     cursor TEXT,
     last_sync_at TEXT,
     last_error TEXT,
     PRIMARY KEY (budget_id, account_id),
     UNIQUE (budget_id, plaid_account_id)
   );`,
  // 7: Account types (cash, savings, credit, investment, mortgage, loan), which Actual
  // doesn't have. One row per account that has been given one.
  `CREATE TABLE account_kinds (
     budget_id TEXT NOT NULL,
     account_id TEXT NOT NULL,
     kind TEXT NOT NULL,
     PRIMARY KEY (budget_id, account_id)
   );`,
];

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');
const token = (prefix: string) => `${prefix}_${randomBytes(32).toString('base64url')}`;
const nowIso = () => new Date().toISOString();

// Crockford-style alphabet: no 0/O or 1/I/L confusion when typed by hand.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';
function pairingCode(): string {
  const bytes = randomBytes(8);
  const chars = [...bytes].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]);
  return `${chars.slice(0, 4).join('')}-${chars.slice(4).join('')}`;
}
const normalizeCode = (c: string) => c.toUpperCase().replace(/[^A-Z0-9]/g, '');

type Row = Record<string, unknown>;

export type PlaidItemStatus = 'ok' | 'reauth' | 'gone';
export type PlaidItemRow = { id: string; institution: string; status: PlaidItemStatus; statusCode: string | null; createdAt: string; updatedAt: string };
export type PlaidLinkRow = {
  budgetId: string;
  accountId: string;
  plaidAccountId: string;
  itemId: string;
  plaidAccountName: string;
  startDate: string;
  /** Plaid's /transactions/sync position for this link; null until the first sync finishes. */
  cursor: string | null;
  lastSyncAt: string | null;
  lastError: string | null;
};
const toItem = (r: Row): PlaidItemRow => ({
  id: String(r.id),
  institution: String(r.institution),
  status: String(r.status) as PlaidItemStatus,
  statusCode: (r.status_code as string | null) ?? null,
  createdAt: String(r.created_at),
  updatedAt: String(r.updated_at),
});
const toLink = (r: Row): PlaidLinkRow => ({
  budgetId: String(r.budget_id),
  accountId: String(r.account_id),
  plaidAccountId: String(r.plaid_account_id),
  itemId: String(r.item_id),
  plaidAccountName: String(r.plaid_account_name),
  startDate: String(r.start_date),
  cursor: (r.cursor as string | null) ?? null,
  lastSyncAt: (r.last_sync_at as string | null) ?? null,
  lastError: (r.last_error as string | null) ?? null,
});

export class HouseholdStore {
  readonly db: Database.Database;

  constructor(
    path: string,
    private readonly ttl: { accessSec: number; refreshSec: number } = { accessSec: 3600, refreshSec: 90 * 24 * 3600 },
    private readonly now: () => number = Date.now,
  ) {
    this.db = new Database(path);
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('foreign_keys = ON');
    this.migrate();
  }

  close() {
    this.db.close();
  }

  private migrate() {
    const version = this.db.pragma('user_version', { simple: true }) as number;
    for (let v = version; v < MIGRATIONS.length; v++) {
      this.db.transaction(() => {
        this.db.exec(MIGRATIONS[v]!);
        this.db.pragma(`user_version = ${v + 1}`);
      })();
    }
  }

  // ── Personal preferences and category appearance ─────────────────────────

  getMemberPref<T>(memberId: string, key: string): T | null {
    const row = this.db.prepare('SELECT value FROM member_prefs WHERE member_id = ? AND key = ?').get(memberId, key) as { value: string } | undefined;
    return row ? (JSON.parse(row.value) as T) : null;
  }

  setMemberPref(memberId: string, key: string, value: unknown) {
    this.db
      .prepare('INSERT INTO member_prefs (member_id, key, value) VALUES (?, ?, ?) ON CONFLICT(member_id, key) DO UPDATE SET value = excluded.value')
      .run(memberId, key, JSON.stringify(value));
  }

  categoryAppearance(budgetId: string): { categoryId: string; color: string | null; emoji: string | null }[] {
    const rows = this.db.prepare('SELECT category_id, color, emoji FROM category_appearance WHERE budget_id = ? ORDER BY category_id').all(budgetId) as {
      category_id: string;
      color: string | null;
      emoji: string | null;
    }[];
    return rows.map((r) => ({ categoryId: r.category_id, color: r.color, emoji: r.emoji }));
  }

  setCategoryAppearance(budgetId: string, categoryId: string, color: string | null, emoji: string | null) {
    if (color === null && emoji === null) {
      this.db.prepare('DELETE FROM category_appearance WHERE budget_id = ? AND category_id = ?').run(budgetId, categoryId);
      return;
    }
    this.db
      .prepare(
        'INSERT INTO category_appearance (budget_id, category_id, color, emoji) VALUES (?, ?, ?, ?) ON CONFLICT(budget_id, category_id) DO UPDATE SET color = excluded.color, emoji = excluded.emoji',
      )
      .run(budgetId, categoryId, color, emoji);
  }

  // ── Review inbox ─────────────────────────────────────────────────────────

  reviewSince(memberId: string, budgetId: string): number | null {
    const row = this.db.prepare('SELECT since FROM review_state WHERE member_id = ? AND budget_id = ?').get(memberId, budgetId) as { since: number } | undefined;
    return row?.since ?? null;
  }

  setReviewSince(memberId: string, budgetId: string, since: number) {
    this.db
      .prepare('INSERT INTO review_state (member_id, budget_id, since) VALUES (?, ?, ?) ON CONFLICT(member_id, budget_id) DO UPDATE SET since = excluded.since')
      .run(memberId, budgetId, since);
  }

  reviewedIds(memberId: string, budgetId: string): Set<string> {
    const rows = this.db.prepare('SELECT transaction_id FROM reviewed WHERE member_id = ? AND budget_id = ?').all(memberId, budgetId) as { transaction_id: string }[];
    return new Set(rows.map((r) => r.transaction_id));
  }

  markReviewed(memberId: string, budgetId: string, ids: string[]) {
    const insert = this.db.prepare('INSERT OR IGNORE INTO reviewed (member_id, budget_id, transaction_id, at) VALUES (?, ?, ?, ?)');
    const at = nowIso();
    this.db.transaction(() => ids.forEach((id) => insert.run(memberId, budgetId, id, at)))();
  }

  /** Forgets one-by-one marks (after the watermark moves past them). */
  pruneReviewed(memberId: string, budgetId: string, keep: string[]) {
    const keepSet = new Set(keep);
    const del = this.db.prepare('DELETE FROM reviewed WHERE member_id = ? AND budget_id = ? AND transaction_id = ?');
    this.db.transaction(() => {
      for (const id of this.reviewedIds(memberId, budgetId)) if (!keepSet.has(id)) del.run(memberId, budgetId, id);
    })();
  }

  // ── Settings and SimpleFIN request log ───────────────────────────────────

  getSetting<T>(key: string): T | null {
    const row = this.db.prepare('SELECT value FROM settings WHERE key = ?').get(key) as { value: string } | undefined;
    return row ? (JSON.parse(row.value) as T) : null;
  }

  setSetting(key: string, value: unknown) {
    if (value === null || value === undefined) this.db.prepare('DELETE FROM settings WHERE key = ?').run(key);
    else this.db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, JSON.stringify(value));
  }

  recordSimpleFinRequest() {
    this.db.prepare('INSERT INTO simplefin_requests (at) VALUES (?)').run(this.now());
    this.db.prepare('DELETE FROM simplefin_requests WHERE at < ?').run(this.now() - 7 * 24 * 3600 * 1000);
  }

  /** SimpleFIN requests in the last 24 hours (their quota is ~24 a day). */
  /** A consistent copy of the household database (members, devices, settings), safe while running. */
  async backupTo(path: string): Promise<void> {
    await this.db.backup(path);
  }

  simpleFinRequestsToday(): number {
    return (this.db.prepare('SELECT COUNT(*) AS n FROM simplefin_requests WHERE at >= ?').get(this.now() - 24 * 3600 * 1000) as { n: number }).n;
  }

  // ── Account types ────────────────────────────────────────────────────────

  accountKinds(budgetId: string): Map<string, string> {
    const rows = this.db.prepare('SELECT account_id, kind FROM account_kinds WHERE budget_id = ?').all(budgetId) as { account_id: string; kind: string }[];
    return new Map(rows.map((r) => [r.account_id, r.kind]));
  }

  /** Sets an account's type; null clears it (the app guesses again). */
  setAccountKind(budgetId: string, accountId: string, kind: string | null) {
    if (kind === null) this.db.prepare('DELETE FROM account_kinds WHERE budget_id = ? AND account_id = ?').run(budgetId, accountId);
    else
      this.db
        .prepare('INSERT INTO account_kinds (budget_id, account_id, kind) VALUES (?, ?, ?) ON CONFLICT(budget_id, account_id) DO UPDATE SET kind = excluded.kind')
        .run(budgetId, accountId, kind);
  }

  // ── Plaid ────────────────────────────────────────────────────────────────

  plaidItems(): PlaidItemRow[] {
    return (this.db.prepare('SELECT * FROM plaid_items ORDER BY created_at').all() as Row[]).map(toItem);
  }

  plaidItem(id: string): PlaidItemRow | null {
    const r = this.db.prepare('SELECT * FROM plaid_items WHERE id = ?').get(id) as Row | undefined;
    return r ? toItem(r) : null;
  }

  /** Adds a bank login, or marks a reconnected one healthy again. */
  savePlaidItem(e: { id: string; institution: string }) {
    const at = nowIso();
    this.db
      .prepare(
        `INSERT INTO plaid_items (id, institution, status, status_code, created_at, updated_at) VALUES (?, ?, 'ok', NULL, ?, ?)
         ON CONFLICT(id) DO UPDATE SET institution = excluded.institution, status = 'ok', status_code = NULL, updated_at = excluded.updated_at`,
      )
      .run(e.id, e.institution, at, at);
  }

  setPlaidItemStatus(id: string, status: PlaidItemStatus, code: string | null) {
    this.db.prepare('UPDATE plaid_items SET status = ?, status_code = ?, updated_at = ? WHERE id = ?').run(status, code, nowIso(), id);
  }

  deletePlaidItem(id: string) {
    this.db.transaction(() => {
      this.db.prepare('DELETE FROM plaid_links WHERE item_id = ?').run(id);
      this.db.prepare('DELETE FROM plaid_items WHERE id = ?').run(id);
    })();
  }

  plaidLinks(budgetId?: string): PlaidLinkRow[] {
    const rows = (budgetId ? this.db.prepare('SELECT * FROM plaid_links WHERE budget_id = ?').all(budgetId) : this.db.prepare('SELECT * FROM plaid_links').all()) as Row[];
    return rows.map(toLink);
  }

  plaidLink(budgetId: string, accountId: string): PlaidLinkRow | null {
    const r = this.db.prepare('SELECT * FROM plaid_links WHERE budget_id = ? AND account_id = ?').get(budgetId, accountId) as Row | undefined;
    return r ? toLink(r) : null;
  }

  addPlaidLink(l: Omit<PlaidLinkRow, 'cursor' | 'lastSyncAt' | 'lastError'>) {
    this.db
      .prepare('INSERT INTO plaid_links (budget_id, account_id, plaid_account_id, item_id, plaid_account_name, start_date) VALUES (?, ?, ?, ?, ?, ?)')
      .run(l.budgetId, l.accountId, l.plaidAccountId, l.itemId, l.plaidAccountName, l.startDate);
  }

  updatePlaidLink(budgetId: string, accountId: string, patch: { lastSyncAt?: string; lastError?: string | null; cursor?: string | null }) {
    const cur = this.plaidLink(budgetId, accountId);
    if (!cur) return;
    this.db
      .prepare('UPDATE plaid_links SET last_sync_at = ?, last_error = ?, cursor = ? WHERE budget_id = ? AND account_id = ?')
      .run(patch.lastSyncAt ?? cur.lastSyncAt, patch.lastError === undefined ? cur.lastError : patch.lastError, patch.cursor === undefined ? cur.cursor : patch.cursor, budgetId, accountId);
  }

  /** Starts a link's sync over from [startDate] (a history import). */
  resetPlaidLink(budgetId: string, accountId: string, startDate: string) {
    this.db.prepare('UPDATE plaid_links SET start_date = ?, cursor = NULL WHERE budget_id = ? AND account_id = ?').run(startDate, budgetId, accountId);
  }

  deletePlaidLink(budgetId: string, accountId: string) {
    this.db.prepare('DELETE FROM plaid_links WHERE budget_id = ? AND account_id = ?').run(budgetId, accountId);
  }

  clearPlaid() {
    this.db.transaction(() => {
      this.db.prepare('DELETE FROM plaid_links').run();
      this.db.prepare('DELETE FROM plaid_items').run();
    })();
  }

  // ── Members ──────────────────────────────────────────────────────────────

  createMember(input: { displayName: string; role: Role; budgetIds?: string[] }): Member {
    const id = randomUUID();
    this.db.transaction(() => {
      this.db
        .prepare('INSERT INTO members (id, display_name, role, created_at) VALUES (?, ?, ?, ?)')
        .run(id, input.displayName.trim(), input.role, nowIso());
      this.writeBudgets(id, input.budgetIds ?? []);
    })();
    return this.getMember(id)!;
  }

  getMember(id: string): Member | null {
    const row = this.db.prepare('SELECT * FROM members WHERE id = ?').get(id) as Row | undefined;
    return row ? this.toMember(row) : null;
  }

  findMemberByName(name: string): Member | null {
    const row = this.db.prepare('SELECT * FROM members WHERE lower(display_name) = lower(?)').get(name.trim()) as Row | undefined;
    return row ? this.toMember(row) : null;
  }

  listMembers(): Member[] {
    return (this.db.prepare('SELECT * FROM members ORDER BY created_at').all() as Row[]).map((r) => this.toMember(r));
  }

  setMemberBudgets(memberId: string, budgetIds: string[]): Member | null {
    if (!this.getMember(memberId)) return null;
    this.db.transaction(() => this.writeBudgets(memberId, budgetIds))();
    return this.getMember(memberId);
  }

  /** Owners can open every budget; everyone else only what they were granted. */
  canAccessBudget(member: Member, budgetId: string): boolean {
    return member.role === 'owner' || member.budgetIds.includes(budgetId);
  }

  private writeBudgets(memberId: string, budgetIds: string[]) {
    this.db.prepare('DELETE FROM member_budgets WHERE member_id = ?').run(memberId);
    const ins = this.db.prepare('INSERT OR IGNORE INTO member_budgets (member_id, budget_id) VALUES (?, ?)');
    for (const b of budgetIds) ins.run(memberId, b);
  }

  private toMember(row: Row): Member {
    const budgetIds = (
      this.db.prepare('SELECT budget_id FROM member_budgets WHERE member_id = ? ORDER BY budget_id').all(row.id) as Row[]
    ).map((r) => String(r.budget_id));
    return {
      id: String(row.id),
      displayName: String(row.display_name),
      role: row.role as Role,
      disabled: row.disabled === 1,
      budgetIds,
    };
  }

  // ── Pairing ──────────────────────────────────────────────────────────────

  createPairingCode(memberId: string, createdBy: string | null, ttlSec = 600): { code: string; expiresAt: string } {
    const code = pairingCode();
    const expiresAt = this.now() + ttlSec * 1000;
    this.db
      .prepare('INSERT INTO pairing_codes (code_hash, member_id, created_by, expires_at) VALUES (?, ?, ?, ?)')
      .run(sha256(normalizeCode(code)), memberId, createdBy, expiresAt);
    return { code, expiresAt: new Date(expiresAt).toISOString() };
  }

  /** Single use. Returns null for unknown, used, or expired codes, or disabled members. */
  redeemPairingCode(code: string, device: { name: string; platform: string }) {
    return this.db.transaction(() => {
      const row = this.db
        .prepare('SELECT * FROM pairing_codes WHERE code_hash = ?')
        .get(sha256(normalizeCode(code))) as Row | undefined;
      if (!row || row.used_at !== null || Number(row.expires_at) < this.now()) return null;
      const member = this.getMember(String(row.member_id));
      if (!member || member.disabled) return null;

      this.db.prepare('UPDATE pairing_codes SET used_at = ? WHERE code_hash = ?').run(this.now(), row.code_hash);
      const deviceId = randomUUID();
      this.db
        .prepare('INSERT INTO devices (id, member_id, name, platform, created_at) VALUES (?, ?, ?, ?, ?)')
        .run(deviceId, member.id, device.name.trim(), device.platform, nowIso());
      return { member, device: this.getDevice(deviceId)!, tokens: this.issueTokens(deviceId) };
    })();
  }

  // ── Tokens ───────────────────────────────────────────────────────────────

  private issueTokens(deviceId: string): TokenPair {
    const accessToken = token('abat');
    const refreshToken = token('abrt');
    const ins = this.db.prepare('INSERT INTO tokens (token_hash, device_id, kind, expires_at) VALUES (?, ?, ?, ?)');
    ins.run(sha256(accessToken), deviceId, 'access', this.now() + this.ttl.accessSec * 1000);
    ins.run(sha256(refreshToken), deviceId, 'refresh', this.now() + this.ttl.refreshSec * 1000);
    return { accessToken, refreshToken, expiresIn: this.ttl.accessSec };
  }

  /**
   * Rotates the refresh token. Presenting an already-rotated refresh token means it was
   * copied: the whole device is revoked (refresh token reuse detection).
   */
  refresh(refreshToken: string) {
    return this.db.transaction(() => {
      const row = this.db
        .prepare("SELECT * FROM tokens WHERE token_hash = ? AND kind = 'refresh'")
        .get(sha256(refreshToken)) as Row | undefined;
      if (!row) return null;
      const deviceId = String(row.device_id);
      if (row.rotated_at !== null) {
        this.revokeDevice(deviceId);
        return null;
      }
      if (Number(row.expires_at) < this.now()) return null;
      const ctx = this.deviceContext(deviceId);
      if (!ctx) return null;
      this.db.prepare('UPDATE tokens SET rotated_at = ? WHERE token_hash = ?').run(this.now(), row.token_hash);
      // Old access tokens for this device stop working once a new pair is issued.
      this.db.prepare("DELETE FROM tokens WHERE device_id = ? AND kind = 'access'").run(deviceId);
      return { ...ctx, tokens: this.issueTokens(deviceId) };
    })();
  }

  authenticate(accessToken: string): { member: Member; device: Device } | null {
    const row = this.db
      .prepare("SELECT * FROM tokens WHERE token_hash = ? AND kind = 'access'")
      .get(sha256(accessToken)) as Row | undefined;
    if (!row || Number(row.expires_at) < this.now()) return null;
    const ctx = this.deviceContext(String(row.device_id));
    if (!ctx) return null;
    // Throttle last-seen writes to once a minute per device.
    const last = ctx.device.lastSeenAt ? Date.parse(ctx.device.lastSeenAt) : 0;
    if (this.now() - last > 60_000) {
      this.db.prepare('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(new Date(this.now()).toISOString(), ctx.device.id);
    }
    return ctx;
  }

  private deviceContext(deviceId: string) {
    const d = this.db.prepare('SELECT * FROM devices WHERE id = ? AND revoked_at IS NULL').get(deviceId) as Row | undefined;
    if (!d) return null;
    const member = this.getMember(String(d.member_id));
    if (!member || member.disabled) return null;
    return { member, device: this.toDevice(d) };
  }

  // ── Devices ──────────────────────────────────────────────────────────────

  getDevice(id: string): Device | null {
    const row = this.db.prepare('SELECT * FROM devices WHERE id = ? AND revoked_at IS NULL').get(id) as Row | undefined;
    return row ? this.toDevice(row) : null;
  }

  listDevices(memberId: string): Device[] {
    return (
      this.db.prepare('SELECT * FROM devices WHERE member_id = ? AND revoked_at IS NULL ORDER BY created_at').all(memberId) as Row[]
    ).map((r) => this.toDevice(r));
  }

  revokeDevice(deviceId: string): boolean {
    const res = this.db.prepare('UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL').run(nowIso(), deviceId);
    this.db.prepare('DELETE FROM tokens WHERE device_id = ?').run(deviceId);
    return res.changes > 0;
  }

  private toDevice(row: Row): Device {
    return {
      id: String(row.id),
      memberId: String(row.member_id),
      name: String(row.name),
      platform: String(row.platform),
      createdAt: String(row.created_at),
      lastSeenAt: row.last_seen_at ? String(row.last_seen_at) : null,
    };
  }

  // ── Idempotency & audit ──────────────────────────────────────────────────

  getIdempotent(memberId: string, key: string, route: string): { status: number; body: unknown } | null {
    const row = this.db
      .prepare('SELECT * FROM idempotency WHERE member_id = ? AND key = ?')
      .get(memberId, key) as Row | undefined;
    if (!row) return null;
    if (row.route !== route) return { status: 409, body: null };
    return { status: Number(row.status), body: JSON.parse(String(row.body)) };
  }

  putIdempotent(memberId: string, key: string, route: string, status: number, body: unknown) {
    this.db
      .prepare('INSERT OR REPLACE INTO idempotency (member_id, key, route, status, body, created_at) VALUES (?, ?, ?, ?, ?, ?)')
      .run(memberId, key, route, status, JSON.stringify(body), this.now());
  }

  pruneIdempotency(maxAgeMs = 7 * 24 * 3600 * 1000) {
    this.db.prepare('DELETE FROM idempotency WHERE created_at < ?').run(this.now() - maxAgeMs);
  }

  audit(e: { memberId?: string; deviceId?: string; action: string; budgetId?: string; target?: string; detail?: unknown }) {
    this.db
      .prepare('INSERT INTO audit_log (at, member_id, device_id, action, budget_id, target, detail) VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(
        nowIso(),
        e.memberId ?? null,
        e.deviceId ?? null,
        e.action,
        e.budgetId ?? null,
        e.target ?? null,
        e.detail === undefined ? null : JSON.stringify(e.detail),
      );
  }

  recentAudit(limit = 50) {
    return this.db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT ?').all(limit) as Row[];
  }
}
