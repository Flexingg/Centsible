import { randomUUID } from 'node:crypto';
import { rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { extname, join } from 'node:path';
import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

export type CsvMapping = { date?: string; payee?: string; amount?: string; inflow?: string; outflow?: string; notes?: string };
export type ImportOptions = {
  /** How dates in the file are written. Default: detect ISO, otherwise the budget's date format. */
  dateFormat?: 'yyyy-MM-dd' | 'MM/dd/yyyy' | 'dd/MM/yyyy' | 'MM/dd/yy' | 'dd/MM/yy';
  hasHeaderRow?: boolean;
  delimiter?: string;
  csvMapping?: CsvMapping;
  /** Flip signs, for files that list spending as positive numbers. */
  invertAmounts?: boolean;
};
export type ImportFile = { fileName: string; contentBase64: string; options?: ImportOptions };

export type ImportRow = { date: string; amount: number; payeeName: string | null; notes: string | null; importedId: string | null };

const EXTENSIONS = new Set(['.ofx', '.qfx', '.qif', '.csv', '.tsv', '.txt', '.xml']);
const MAX_BYTES = 10 * 1024 * 1024;
const settle = () => new Promise<void>((r) => setTimeout(r, 0));

/** Bank sync, file import and reconciliation for one account. */
export class AccountOps {
  constructor(private readonly host: ActualHost) {}

  /** Account id → sync info. `account_sync_source` is set once linked in Actual. */
  static async syncInfo(): Promise<Map<string, { syncSource: string | null; lastSync: string | null; bankSyncStatus: string | null }>> {
    const { data } = (await api.aqlQuery(api.q('accounts').select(['id', 'account_sync_source', 'last_sync', 'bank_sync_status']))) as { data: Raw[] };
    return new Map(
      data.map((a) => [
        String(a.id),
        {
          syncSource: typeof a.account_sync_source === 'string' && a.account_sync_source ? a.account_sync_source : null,
          lastSync: a.last_sync ? new Date(Number(a.last_sync)).toISOString() : null,
          bankSyncStatus: typeof a.bank_sync_status === 'string' && a.bank_sync_status ? a.bank_sync_status : null,
        },
      ]),
    );
  }

  /** Pulls new bank transactions. Runs inside the budget queue, so it can take a while. */
  bankSync(budgetId: string, accountId: string | null) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const info = await AccountOps.syncInfo();
      const linked = [...info.entries()].filter(([, v]) => v.syncSource).map(([id]) => id);
      if (accountId && !linked.includes(accountId)) {
        throw ApiError.validation('This account isn\'t linked to a bank. Link it in Actual (GoCardless, SimpleFIN or Pluggy.ai) first.');
      }
      if (!accountId && !linked.length) throw ApiError.validation('No accounts are linked to a bank in Actual yet.');
      const count = async () =>
        ((await api.aqlQuery(api.q('transactions').filter(accountId ? { account: accountId } : { account: { $oneof: linked } }).calculate({ $count: '$id' }))) as { data: number }).data;
      const before = await count();
      await api.runBankSync(accountId ? { accountId } : undefined);
      await settle();
      return { accounts: accountId ? 1 : linked.length, newTransactions: Math.max(0, (await count()) - before) };
    });
  }

  /** Parses a statement file and reports what importing it would do (dry run). */
  previewImport(budgetId: string, accountId: string, file: ImportFile) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      await requireOpenAccount(accountId);
      const parsed = await parseFile(this.host, lib, file);
      if (!parsed.rows.length) return { ...parsed, newCount: 0, matchedCount: 0 };
      const dry = (await api.importTransactions(accountId, parsed.rows.map(toImportEntity), { dryRun: true })) as unknown as Raw;
      return {
        ...parsed,
        newCount: ((dry.added as unknown[]) ?? []).length,
        matchedCount: ((dry.updated as unknown[]) ?? []).length,
      };
    });
  }

  /** Imports the file. Rows matching existing transactions are merged, not duplicated. */
  commitImport(budgetId: string, accountId: string, file: ImportFile) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await requireOpenAccount(accountId);
      const parsed = await parseFile(this.host, lib, file);
      if (parsed.errors.length && !parsed.rows.length) throw ApiError.validation(parsed.errors.join('; '));
      const res = (await api.importTransactions(accountId, parsed.rows.map(toImportEntity), { defaultCleared: true })) as unknown as Raw;
      await settle();
      return { added: ((res.added as unknown[]) ?? []).length, updated: ((res.updated as unknown[]) ?? []).length, errors: parsed.errors };
    });
  }

  reconcileStatus(budgetId: string, accountId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      await requireOpenAccount(accountId);
      return balances(accountId);
    });
  }

  /**
   * Reconciles against a statement: if the cleared balance differs and `adjust` is set,
   * adds an adjustment transaction; then locks every cleared transaction.
   */
  reconcile(budgetId: string, accountId: string, statementBalance: number, adjust: boolean) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await requireOpenAccount(accountId);
      const before = await balances(accountId);
      const difference = statementBalance - before.clearedBalance;
      if (difference !== 0 && !adjust) {
        return { reconciled: false, difference, adjustmentTransactionId: null, lockedCount: 0 };
      }
      let adjustmentTransactionId: string | null = null;
      if (difference !== 0) {
        adjustmentTransactionId = randomUUID();
        await api.addTransactions(accountId, [
          {
            id: adjustmentTransactionId,
            date: new Date().toISOString().slice(0, 10),
            amount: difference,
            payee_name: 'Reconciliation balance adjustment',
            notes: 'Added when reconciling in the app',
            cleared: true,
          } as Parameters<typeof api.addTransactions>[1][number],
        ]);
      }
      const { data } = (await api.aqlQuery(
        api.q('transactions').filter({ account: accountId, cleared: true, reconciled: false }).options({ splits: 'none' }).select(['id']),
      )) as { data: { id: string }[] };
      for (const t of data) await api.updateTransaction(t.id, { reconciled: true });
      await settle();
      return { reconciled: true, difference, adjustmentTransactionId, lockedCount: data.length };
    });
  }
}

async function requireOpenAccount(accountId: string) {
  const account = (await api.getAccounts()).find((a) => a.id === accountId);
  if (!account) throw ApiError.notFound(`Account ${accountId} not found`);
  if (account.closed) throw ApiError.validation('That account is closed');
}

async function balances(accountId: string) {
  const sum = async (filter: Raw) =>
    ((await api.aqlQuery(api.q('transactions').filter({ account: accountId, ...filter }).options({ splits: 'none' }).calculate({ $sum: '$amount' }))) as { data: number | null }).data ?? 0;
  const [cleared, uncleared] = await Promise.all([sum({ cleared: true }), sum({ cleared: false })]);
  return { clearedBalance: cleared, unclearedBalance: uncleared, balance: cleared + uncleared };
}

function toImportEntity(r: ImportRow) {
  return {
    date: r.date,
    amount: r.amount,
    payee_name: r.payeeName ?? undefined,
    imported_payee: r.payeeName ?? undefined,
    notes: r.notes ?? undefined,
    imported_id: r.importedId ?? undefined,
  } as Parameters<typeof api.importTransactions>[1][number];
}

async function parseFile(host: ActualHost, lib: Parameters<Parameters<ActualHost['withBudget']>[2]>[0], file: ImportFile) {
  const ext = extname(file.fileName).toLowerCase();
  if (!EXTENSIONS.has(ext)) throw ApiError.validation(`Unsupported file type ${ext || '(none)'}. Use OFX, QFX, QIF, CSV or CAMT XML.`);
  const bytes = Buffer.from(file.contentBase64, 'base64');
  if (!bytes.length) throw ApiError.validation('The file is empty');
  if (bytes.length > MAX_BYTES) throw ApiError.validation('The file is larger than 10 MB');
  const opts = file.options ?? {};
  const isCsv = ext === '.csv' || ext === '.tsv' || ext === '.txt';
  const path = join(tmpdir(), `bridge-import-${randomUUID()}${ext}`);
  writeFileSync(path, bytes);
  try {
    const res = await host.internal<{ errors?: { message: string }[]; transactions?: Raw[] }>('import.files', lib, 'transactions-parse-file', {
      filepath: path,
      options: {
        hasHeaderRow: opts.hasHeaderRow ?? true,
        delimiter: opts.delimiter ?? (ext === '.tsv' ? '\t' : ','),
        importNotes: true,
        fallbackMissingPayeeToMemo: true,
      },
    });
    const errors = (res.errors ?? []).map((e) => e.message);
    const raw = res.transactions ?? [];
    const prefs = (await api.getPreferences()) as Raw;
    const fallbackFormat = (typeof prefs.dateFormat === 'string' ? prefs.dateFormat : 'MM/dd/yyyy') as NonNullable<ImportOptions['dateFormat']>;
    const dateFormat = opts.dateFormat ?? fallbackFormat;
    const sign = opts.invertAmounts ? -1 : 1;

    let columns: string[] = [];
    let mapping: CsvMapping | null = null;
    const rows: ImportRow[] = [];
    if (isCsv) {
      columns = raw.length ? Object.keys(raw[0]!) : [];
      mapping = { ...detectCsvMapping(columns), ...(opts.csvMapping ?? {}) };
      if (!mapping.date || (!mapping.amount && !mapping.inflow && !mapping.outflow)) {
        return { rows: [], errors: [...errors, 'Choose which columns hold the date and amount'], columns, mapping };
      }
    }
    raw.forEach((t, i) => {
      const line = `Row ${i + 1}`;
      const dateText = String(isCsv ? t[mapping!.date!] ?? '' : t.date ?? '');
      const date = parseDate(dateText, dateFormat);
      let amount: number | null;
      if (isCsv) {
        if (mapping!.amount) amount = parseAmount(t[mapping!.amount]);
        else amount = (parseAmount(t[mapping!.inflow!]) ?? 0) - Math.abs(parseAmount(t[mapping!.outflow!]) ?? 0);
      } else {
        amount = typeof t.amount === 'number' ? Math.round(t.amount * 100) : parseAmount(t.amount);
      }
      if (!date) return void errors.push(`${line}: can't read date "${dateText}"`);
      if (amount === null) return void errors.push(`${line}: can't read the amount`);
      const payee = isCsv ? t[mapping!.payee ?? ''] : (t.payee_name ?? t.imported_payee);
      const notes = isCsv ? t[mapping!.notes ?? ''] : t.notes;
      rows.push({
        date,
        amount: amount * sign,
        payeeName: typeof payee === 'string' && payee.trim() ? payee.trim() : null,
        notes: typeof notes === 'string' && notes.trim() ? notes.trim() : null,
        importedId: typeof t.imported_id === 'string' && t.imported_id ? t.imported_id : null,
      });
    });
    return { rows, errors, columns, mapping };
  } finally {
    rmSync(path, { force: true });
  }
}

/** Guesses CSV columns from common bank export headers. */
export function detectCsvMapping(columns: string[]): CsvMapping {
  const find = (re: RegExp, exclude?: RegExp) => columns.find((c) => re.test(c) && !(exclude && exclude.test(c)));
  return {
    date: find(/^(transaction |posted |posting |trans\.? )?date$/i) ?? find(/date/i),
    payee: find(/payee|merchant|description|^name$|counterparty/i, /date/i),
    amount: find(/^amount$|^amount \(|transaction amount/i),
    inflow: find(/credit|deposit|inflow|money in|paid in/i),
    outflow: find(/debit|withdrawal|outflow|money out|paid out/i),
    notes: find(/note|memo|reference|details/i, /date/i),
  };
}

/** Cents from "1,234.56", "-$12.00", "(12.00)" or a plain number. */
export function parseAmount(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v * 100) : null;
  if (typeof v !== 'string') return null;
  let s = v.trim();
  if (!s) return null;
  const negative = /^\(.*\)$/.test(s) || s.startsWith('-') || s.endsWith('-');
  s = s.replace(/[()\s$£€¥-]/g, '').replace(/,(?=\d{3}(\D|$))/g, '');
  if (/^\d+,\d{1,2}$/.test(s)) s = s.replace(',', '.'); // "12,50" decimal comma
  if (!/^\d*\.?\d+$/.test(s)) return null;
  const cents = Math.round(parseFloat(s) * 100);
  return negative ? -cents : cents;
}

/** "YYYY-MM-DD" from ISO or the given day/month order; null if it isn't a real date. */
export function parseDate(text: string, format: NonNullable<ImportOptions['dateFormat']>): string | null {
  const s = text.trim();
  let y: number, m: number, d: number;
  const iso = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(s);
  const compact = /^(\d{4})(\d{2})(\d{2})/.exec(s); // OFX 20260903120000
  const parts = /^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})$/.exec(s);
  if (iso) [y, m, d] = [Number(iso[1]), Number(iso[2]), Number(iso[3])];
  else if (compact) [y, m, d] = [Number(compact[1]), Number(compact[2]), Number(compact[3])];
  else if (parts) {
    const a = Number(parts[1]);
    const b = Number(parts[2]);
    y = Number(parts[3]);
    if (y < 100) y += 2000;
    [m, d] = format.startsWith('dd') ? [b, a] : [a, b];
  } else return null;
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return date.toISOString().slice(0, 10);
}
