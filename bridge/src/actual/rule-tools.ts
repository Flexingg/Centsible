import * as api from '@actual-app/api';
import { HyperFormula } from 'hyperformula';
import { enUS } from 'hyperformula/i18n/languages';
import { ApiError } from '../errors.js';
import { toTransaction, type TransactionDto } from '../mappers/index.js';
import type { ActualHost, Lib } from './host.js';
import { TX_FIELDS } from './transaction-ops.js';

type Raw = Record<string, unknown>;
export type RuleItem = { field?: string | null; op: string; value?: unknown; type?: string | null; options?: Raw | null };
export type RuleDraft = { conditionsOp?: 'and' | 'or'; conditions: RuleItem[]; actions: RuleItem[] };

/** One change a rule would make to one transaction. */
type Change = { field: string; value: unknown; note?: string; error?: string };

const NUMBER_FIELDS = new Set(['amount']);
const DATE_FIELDS = new Set(['date']);
const BOOLEAN_FIELDS = new Set(['cleared', 'reconciled']);

/**
 * Rules beyond create/edit: which transactions a rule matches and what it would do to
 * them (nothing written), running a saved rule on existing transactions, and running
 * every rule again on chosen transactions.
 *
 * Matching uses Actual's own `make-filters-from-conditions`, so conditions mean exactly
 * what they mean in Actual. Changes are worked out here only for the preview; applying
 * always goes through Actual (`rule-apply-actions`, `rules-run`). Formula previews use
 * HyperFormula 3.3.0 with the same inputs Actual gives formulas (every transaction field,
 * plus payee_name, account_name, category_name, balance before it and today) and the same
 * unit rule: a number that comes out is in currency units, so =500+250 means $750.00.
 * BALANCE_OF, QUERY and BUDGET_QUERY need Actual's data and are only worked out when the
 * rule really runs.
 */
export class RuleTools {
  constructor(private readonly host: ActualHost) {}

  preview(budgetId: string, draft: RuleDraft, limit: number) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      const { filter, errors } = await this.filterFor(lib, draft);
      if (errors.length) return { matchCount: 0, items: [], errors };
      const [count, rows] = await Promise.all([countMatches(filter), matchingRows(filter, limit)]);
      const lookups = await lookupsFor(rows);
      const items = [];
      for (const row of rows) {
        const context = await formulaContext(row, lookups);
        items.push({ transaction: toTransaction(row), changes: draft.actions.flatMap((a) => changeFor(a, row, context, lookups)) });
      }
      return { matchCount: count, items, errors: [] as string[] };
    });
  }

  /** Runs a saved rule's actions on the transactions it matches (or just [ids], when they match). */
  runRule(budgetId: string, ruleId: string, ids?: string[]) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const rule = ((await api.getRules()) as unknown as Raw[]).find((r) => r.id === ruleId);
      if (!rule) throw ApiError.notFound(`Rule ${ruleId} not found`);
      const { filter, errors } = await this.filterFor(lib, { conditionsOp: rule.conditionsOp as 'and' | 'or', conditions: rule.conditions as RuleItem[], actions: [] });
      if (errors.length) throw ApiError.validation(errors.join('; '));
      const withIds = ids?.length ? { $and: [filter, { id: { $oneof: ids } }] } : filter;
      const { data } = (await api.aqlQuery(api.q('transactions').filter(withIds).select('*'))) as { data: Raw[] };
      if (!data.length) return { updated: 0 };
      await this.host.internal('rules.apply', lib, 'rule-apply-actions', { transactions: data, actions: rule.actions });
      await settle();
      return { updated: data.length };
    });
  }

  /** Runs every rule again on these transactions, as if they had just been imported. */
  rerun(budgetId: string, ids: string[]) {
    return this.rerunWhere(budgetId, { id: { $oneof: ids } });
  }

  /**
   * "Run all rules": every rule again on every transaction since [since] (all of them
   * without it). Reconciled ones are left alone; they've been checked against a statement.
   */
  runAll(budgetId: string, since?: string) {
    return this.rerunWhere(budgetId, { reconciled: false, ...(since ? { date: { $gte: since } } : {}) });
  }

  private rerunWhere(budgetId: string, filter: Raw) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const { data } = (await api.aqlQuery(
        api.q('transactions').filter({ ...filter, is_parent: false, starting_balance_flag: false }).options({ splits: 'inline' }).select('*'),
      )) as { data: Raw[] };
      let changed = 0;
      for (const t of data) {
        const after = await this.host.internal<Raw>('rules.apply', lib, 'rules-run', { transaction: t });
        const fields: Raw = {};
        for (const k of ['category', 'payee', 'notes', 'date', 'amount', 'cleared', 'account'] as const) {
          if (after && k in after && after[k] !== t[k]) fields[k] = after[k];
        }
        if (Object.keys(fields).length) {
          await api.updateTransaction(String(t.id), fields);
          changed++;
        }
      }
      await settle();
      return { checked: data.length, changed };
    });
  }

  private async filterFor(lib: Lib, draft: RuleDraft) {
    if (!draft.conditions.length) return { filter: {} as Raw, errors: [] as string[] };
    const r = await this.host.internal<{ filters: Raw[]; errors?: unknown[] }>('rules.apply', lib, 'make-filters-from-conditions', {
      conditions: draft.conditions,
      applySpecialCases: true,
    });
    const errors = (r?.errors ?? []).map((e) => (typeof e === 'string' ? e : JSON.stringify(e)));
    const filters = r?.filters ?? [];
    return { filter: (draft.conditionsOp === 'or' ? { $or: filters } : { $and: filters }) as Raw, errors };
  }
}

async function countMatches(filter: Raw): Promise<number> {
  const { data } = (await api.aqlQuery(api.q('transactions').filter(filter).options({ splits: 'inline' }).calculate({ $count: '*' }))) as { data: number };
  return Number(data ?? 0);
}

async function matchingRows(filter: Raw, limit: number): Promise<Raw[]> {
  const { data } = (await api.aqlQuery(
    api
      .q('transactions')
      .filter(filter)
      .options({ splits: 'inline' })
      .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
      .limit(limit)
      .select([...TX_FIELDS, 'sort_order', 'imported_payee', 'reconciled']),
  )) as { data: Raw[] };
  return data;
}

type Lookups = { accounts: Map<string, string>; categories: Map<string, string>; payees: Map<string, string> };

async function lookupsFor(rows: Raw[]): Promise<Lookups> {
  const [accounts, categories, payees] = await Promise.all([api.getAccounts(), api.getCategories(), rows.length ? api.getPayees() : Promise.resolve([])]);
  return {
    accounts: new Map(accounts.map((a) => [a.id, a.name])),
    categories: new Map(categories.map((c) => [c.id, c.name])),
    payees: new Map((payees as { id: string; name: string }[]).map((p) => [p.id, p.name])),
  };
}

/** What Actual hands a formula for this transaction (see Action.executeFormulaSync). */
async function formulaContext(row: Raw, l: Lookups): Promise<Raw> {
  const account = typeof row.account === 'string' ? row.account : null;
  let balance = 0;
  if (account) {
    // The account's balance before this transaction (Actual's getRunningBalanceBeforeTransaction).
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ account, $or: [{ date: { $lt: row.date } }, { $and: [{ date: row.date }, { sort_order: { $lt: row.sort_order } }] }] })
        .calculate({ $sum: '$amount' }),
    )) as { data: number };
    balance = Number(data ?? 0);
  }
  return {
    ...row,
    'payee.name': undefined,
    payee_name: typeof row.payee === 'string' ? (l.payees.get(row.payee) ?? '') : '',
    account_name: account ? (l.accounts.get(account) ?? '') : '',
    category_name: typeof row.category === 'string' ? (l.categories.get(row.category) ?? '') : '',
    balance,
    today: new Date().toISOString().slice(0, 10),
  };
}

function changeFor(a: RuleItem, row: Raw, context: Raw, l: Lookups): Change[] {
  const field = a.field ?? '';
  switch (a.op) {
    case 'set': {
      const formula = typeof a.options?.formula === 'string' ? a.options.formula : null;
      if (formula) return [evaluate(field, formula, context)];
      if (typeof a.options?.template === 'string') return [{ field, value: null, note: 'Worked out from the template when the rule runs' }];
      return [{ field, value: named(field, a.value, l) }];
    }
    case 'prepend-notes':
      return [{ field: 'notes', value: `${String(a.value ?? '')}${String(row.notes ?? '')}` }];
    case 'append-notes':
      return [{ field: 'notes', value: `${String(row.notes ?? '')}${String(a.value ?? '')}` }];
    case 'delete-transaction':
      return [{ field: 'deleted', value: true }];
    case 'set-split-amount': {
      const i = Number(a.options?.splitIndex ?? 0);
      const method = String(a.options?.method ?? 'fixed-amount');
      if (method === 'formula' && typeof a.options?.formula === 'string') {
        const c = evaluate('amount', a.options.formula, context);
        return [{ ...c, field: `split ${i} amount` }];
      }
      return [{ field: `split ${i} amount`, value: a.value ?? null, note: method }];
    }
    default:
      return [];
  }
}

/** Ids show as names in the preview. */
function named(field: string, value: unknown, l: Lookups): unknown {
  if (typeof value !== 'string') return value;
  if (field === 'category') return l.categories.get(value) ?? value;
  if (field === 'account') return l.accounts.get(value) ?? value;
  if (field === 'payee') return l.payees.get(value) ?? value;
  return value;
}

// The ES build ships without a language registered; Actual's bundle registers en-US.
if (!HyperFormula.getRegisteredLanguagesCodes().includes('enUS')) HyperFormula.registerLanguage('enUS', enUS);

const NEEDS_ACTUAL = /\b(BALANCE_OF|QUERY|BUDGET_QUERY)\s*\(/i;

function evaluate(field: string, formula: string, context: Raw): Change {
  if (!formula.startsWith('=')) return { field, value: null, error: 'A formula starts with =' };
  if (NEEDS_ACTUAL.test(formula)) return { field, value: null, note: 'Uses budget data; worked out when the rule runs' };
  const hf = HyperFormula.buildEmpty({ licenseKey: 'gpl-v3', language: 'enUS', dateFormats: ['DD/MM/YYYY', 'YYYY-MM-DD', 'YYYY/MM/DD'] });
  try {
    const sheet = hf.getSheetId(hf.addSheet('Sheet1'))!;
    for (const [k, v] of Object.entries(context)) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) continue;
      const value = v === undefined || v === null || typeof v === 'object' ? '' : (v as string | number | boolean);
      try {
        hf.addNamedExpression(k, value);
      } catch {
        /* a name HyperFormula won't take (it would fail in Actual the same way) */
      }
    }
    hf.setCellContents({ sheet, col: 0, row: 0 }, [[formula]]);
    const out = hf.getCellValue({ sheet, col: 0, row: 0 });
    if (out && typeof out === 'object' && 'type' in out) return { field, value: null, error: `Formula error: ${(out as { message?: string }).message ?? String((out as { type: unknown }).type)}` };
    return { field, value: coerce(field, out) };
  } catch (err) {
    return { field, value: null, error: err instanceof Error ? err.message : String(err) };
  } finally {
    hf.destroy();
  }
}

/** The value Actual would store for this field. */
function coerce(field: string, out: unknown): unknown {
  if (NUMBER_FIELDS.has(field)) {
    const n = typeof out === 'number' ? out : parseFloat(String(out));
    return Number.isNaN(n) ? null : Math.round(n * 100);
  }
  if (typeof out === 'number') out = Math.round(out * 100) / 100;
  if (DATE_FIELDS.has(field)) return String(out);
  if (BOOLEAN_FIELDS.has(field)) return typeof out === 'boolean' ? out : String(out).toLowerCase() === 'true';
  return String(out);
}

const settle = () => new Promise<void>((r) => setTimeout(r, 0));

export type RulePreview = { matchCount: number; items: { transaction: TransactionDto; changes: Change[] }[]; errors: string[] };
