import { chmodSync, existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { join } from 'node:path';
import { createPrivateKey, X509Certificate } from 'node:crypto';

/**
 * The parts of Teller's API (teller.io/docs/api) the bridge uses. Teller identifies the
 * application by a client certificate (mutual TLS, required outside the sandbox) and the
 * bank login by an access token, sent as the HTTP Basic username.
 */

export const TELLER_API = 'https://api.teller.io';
export const TELLER_ENVIRONMENTS = ['sandbox', 'development', 'production'] as const;
export type TellerEnvironment = (typeof TELLER_ENVIRONMENTS)[number];

export type TellerAccount = {
  id: string;
  enrollment_id: string;
  name: string;
  type: string;
  subtype: string;
  status: string;
  last_four?: string | null;
  currency?: string;
  institution?: { id?: string; name?: string };
};
export type TellerBalance = { account_id?: string; ledger?: string | null; available?: string | null };
export type TellerTransaction = {
  id: string;
  account_id: string;
  amount: string;
  date: string;
  description?: string;
  status?: string;
  type?: string;
  details?: { counterparty?: { name?: string | null; type?: string | null } | null; category?: string | null; processing_status?: string | null } | null;
};

export class TellerError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
  }
  /** The bank login needs the person to sign in again (Teller answers 404 with an enrollment.disconnected code). */
  get disconnected() {
    return this.code?.startsWith('enrollment.disconnected') === true;
  }
  /** The access token was revoked: the enrollment is gone for good. */
  get revoked() {
    return this.status === 403;
  }
}

/** What each disconnect reason means for the person, and what they can do. */
export function describeTellerCode(code: string | null): string {
  switch (code) {
    case 'enrollment.disconnected.credentials_invalid':
      return 'The bank password changed. Reconnect the bank to sign in again.';
    case 'enrollment.disconnected.user_action.mfa_required':
      return 'The bank wants a verification code. Reconnect the bank to enter it.';
    case 'enrollment.disconnected.user_action.web_login_required':
      return "The bank wants you to sign in on its website (often to accept new terms). Do that, then reconnect.";
    case 'enrollment.disconnected.user_action.captcha_required':
      return 'The bank wants a CAPTCHA solved. Reconnect the bank.';
    case 'enrollment.disconnected.user_action.contact_information_required':
      return 'The bank wants your contact details updated on its website. Do that, then reconnect.';
    case 'enrollment.disconnected.user_action.insufficient_permissions':
      return "This login doesn't have permission to see the account. Reconnect with the primary account holder's login.";
    case 'enrollment.disconnected.account_locked':
      return 'The bank locked access to the account. Contact the bank, then reconnect.';
    case 'enrollment.disconnected.enrollment_inactive':
      return 'The connection lapsed from inactivity. Reconnect the bank.';
    default:
      return 'The bank connection needs you to sign in again. Reconnect the bank.';
  }
}

export type TellerCredentials = { certificate: string; privateKey: string };

/** Checks a PEM certificate and key Teller issued, and that they belong together. */
export function checkCredentials(c: TellerCredentials): string | null {
  let cert: X509Certificate;
  try {
    cert = new X509Certificate(c.certificate);
  } catch {
    return "That isn't a certificate. Paste the whole certificate.pem from the Teller dashboard, including the BEGIN and END lines.";
  }
  let key;
  try {
    key = createPrivateKey(c.privateKey);
  } catch {
    return "That isn't a private key. Paste the whole private_key.pem from the Teller dashboard, including the BEGIN and END lines.";
  }
  if (!cert.checkPrivateKey(key)) return "The private key doesn't belong to that certificate. Use the pair Teller gave you together.";
  if (Date.parse(cert.validTo) < Date.now()) return `That certificate expired on ${new Date(cert.validTo).toISOString().slice(0, 10)}. Create a new one in the Teller dashboard.`;
  return null;
}

export class TellerClient {
  constructor(
    private readonly credentials: TellerCredentials | null,
    private readonly baseUrl: string = TELLER_API,
  ) {}

  accounts(token: string): Promise<TellerAccount[]> {
    return this.get(token, '/accounts') as Promise<TellerAccount[]>;
  }

  balances(token: string, accountId: string): Promise<TellerBalance> {
    return this.get(token, `/accounts/${encodeURIComponent(accountId)}/balances`) as Promise<TellerBalance>;
  }

  /**
   * Transactions from [startDate] (inclusive) to today, newest first. Pages backwards with
   * from_id in case Teller caps a page.
   */
  async transactions(token: string, accountId: string, startDate: string, limit = 5000): Promise<TellerTransaction[]> {
    const pageSize = 500;
    const out: TellerTransaction[] = [];
    let fromId: string | null = null;
    for (let page = 0; page < 50 && out.length < limit; page++) {
      const q = new URLSearchParams({ count: String(pageSize), start_date: startDate });
      if (fromId) q.set('from_id', fromId);
      const batch = (await this.get(token, `/accounts/${encodeURIComponent(accountId)}/transactions?${q}`)) as TellerTransaction[];
      out.push(...batch.filter((t) => t.date >= startDate));
      if (batch.length < pageSize || batch.some((t) => t.date < startDate)) break;
      fromId = batch[batch.length - 1]!.id;
    }
    return out;
  }

  /** Stops Teller's access to every account in the enrollment (best effort when unlinking a bank). */
  async revoke(token: string): Promise<void> {
    await this.send('DELETE', token, '/accounts');
  }

  private get(token: string, path: string): Promise<unknown> {
    return this.send('GET', token, path);
  }

  private send(method: string, token: string, path: string): Promise<unknown> {
    const url = new URL(path, this.baseUrl);
    const secure = url.protocol === 'https:';
    const request = secure ? httpsRequest : httpRequest;
    return new Promise((resolve, reject) => {
      const req = request(
        url,
        {
          method,
          headers: { authorization: `Basic ${Buffer.from(`${token}:`).toString('base64')}`, accept: 'application/json' },
          timeout: 60_000,
          ...(secure && this.credentials ? { cert: this.credentials.certificate, key: this.credentials.privateKey } : {}),
        },
        (res) => {
          const chunks: Buffer[] = [];
          res.on('data', (c: Buffer) => chunks.push(c));
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8');
            const status = res.statusCode ?? 0;
            let body: unknown = null;
            try {
              body = text ? JSON.parse(text) : null;
            } catch {
              /* not JSON */
            }
            if (status >= 200 && status < 300) return resolve(body);
            const err = (body as { error?: { code?: string; message?: string } } | null)?.error;
            const code = err?.code ?? null;
            const message =
              status === 429
                ? 'Teller asked the bridge to slow down. It will try again at the next sync.'
                : status === 502
                  ? 'The bank is unavailable right now. It will try again at the next sync.'
                  : code?.startsWith('enrollment.disconnected')
                    ? describeTellerCode(code)
                    : status === 403
                      ? 'Teller no longer accepts this bank connection. Connect the bank again.'
                      : status === 400 && /certificate/i.test(err?.message ?? '')
                        ? 'Teller wants the client certificate. Add the certificate and private key from the Teller dashboard in Bank sync → Teller.'
                        : `Teller answered HTTP ${status}${err?.message ? `: ${err.message}` : ''}`;
            reject(new TellerError(message, status, code));
          });
        },
      );
      req.on('timeout', () => req.destroy(new Error('Teller took too long to answer')));
      req.on('error', (e) => reject(new TellerError(`Couldn't reach Teller: ${e.message}`, 0, null)));
      req.end();
    });
  }
}

/**
 * Teller's secrets: the application's client certificate and key, and each bank login's
 * access token. Kept in an owner-only file beside bridge.sqlite rather than in it, because
 * the household database goes into backups (which can be downloaded).
 */
export class TellerVault {
  private readonly path: string;
  constructor(dataDir: string) {
    this.path = join(dataDir, 'teller.json');
  }

  private read(): { certificate?: string; privateKey?: string; tokens: Record<string, string> } {
    if (!existsSync(this.path)) return { tokens: {} };
    try {
      const v = JSON.parse(readFileSync(this.path, 'utf8')) as { certificate?: string; privateKey?: string; tokens?: Record<string, string> };
      return { ...v, tokens: v.tokens ?? {} };
    } catch {
      return { tokens: {} };
    }
  }

  private write(v: ReturnType<TellerVault['read']>) {
    writeFileSync(this.path, JSON.stringify(v), { mode: 0o600 });
    chmodSync(this.path, 0o600); // an existing file keeps its old mode otherwise
  }

  credentials(): TellerCredentials | null {
    const v = this.read();
    return v.certificate && v.privateKey ? { certificate: v.certificate, privateKey: v.privateKey } : null;
  }
  setCredentials(c: TellerCredentials | null) {
    const v = this.read();
    this.write({ ...v, certificate: c?.certificate, privateKey: c?.privateKey });
  }
  token(enrollmentId: string): string | null {
    return this.read().tokens[enrollmentId] ?? null;
  }
  setToken(enrollmentId: string, token: string | null) {
    const v = this.read();
    if (token) v.tokens[enrollmentId] = token;
    else delete v.tokens[enrollmentId];
    this.write(v);
  }
  clear() {
    rmSync(this.path, { force: true });
  }
}
