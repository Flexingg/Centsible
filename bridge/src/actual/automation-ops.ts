import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { ActualHost, Lib } from './host.js';

/**
 * Budget automations ("programmable budgets"): Actual's structured version of goal
 * templates. Each category keeps a list in `categories.goal_def` with
 * `template_settings.source` saying where it came from: 'notes' (parsed from #template
 * lines, refreshed from the notes before every run) or 'ui' (edited as data, the notes no
 * longer count). Saving here always writes 'ui', like Actual's own automation editor.
 *
 * Actual keeps amounts in currency units (50 means $50.00); this API uses minor units
 * like everything else, converted at the edge. Verified against 26.9.0's handlers:
 * budget/get-category-automations, budget/set-category-automations,
 * budget/dry-run-category-template, budget/store-note-templates,
 * budget/apply-multiple-templates.
 */

type Raw = Record<string, unknown>;

export type LimitDto = { amount: number; hold: boolean; period: 'daily' | 'weekly' | 'monthly'; start: string | null };
export type AdjustmentDto = { kind: 'percent'; value: number } | { kind: 'fixed'; value: number };

/** One automation, in API form (money in minor units). */
export type AutomationDto =
  | { type: 'simple'; priority: number; monthly: number | null; limit: LimitDto | null; description?: string | null }
  | { type: 'periodic'; priority: number; amount: number; every: { unit: 'day' | 'week' | 'month' | 'year'; count: number }; starting: string; limit: LimitDto | null; description?: string | null }
  | { type: 'by'; priority: number; amount: number; month: string; repeat: { unit: 'month' | 'year'; count: number } | null; spendFrom: string | null; description?: string | null }
  | { type: 'schedule'; priority: number; schedule: string; full: boolean; adjustment: AdjustmentDto | null; description?: string | null }
  | { type: 'average'; priority: number; months: number; adjustment: AdjustmentDto | null; description?: string | null }
  | { type: 'copy'; priority: number; monthsAgo: number; description?: string | null }
  | { type: 'percentage'; priority: number; percent: number; of: string; previous: boolean; description?: string | null }
  | { type: 'refill'; priority: number; limit: LimitDto; description?: string | null }
  | { type: 'remainder'; weight: number; limit: LimitDto | null; description?: string | null }
  | { type: 'goal'; amount: number; description?: string | null }
  | { type: 'error'; line: string; error: string };

const toUnits = (minor: number) => minor / 100;
const toMinor = (units: unknown) => Math.round(Number(units ?? 0) * 100);
const MONTH = /^\d{4}-\d{2}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

function limitFrom(raw: unknown): LimitDto | null {
  if (!raw || typeof raw !== 'object') return null;
  const l = raw as Raw;
  return { amount: toMinor(l.amount), hold: !!l.hold, period: (l.period as LimitDto['period']) ?? 'monthly', start: typeof l.start === 'string' ? l.start : null };
}
const limitTo = (l: LimitDto) => ({ amount: toUnits(l.amount), hold: l.hold, period: l.period, start: l.period === 'weekly' ? l.start : null });

function adjustmentFrom(t: Raw): AdjustmentDto | null {
  if (t.adjustment === undefined || t.adjustment === null) return null;
  return t.adjustmentType === 'percent' ? { kind: 'percent', value: Number(t.adjustment) } : { kind: 'fixed', value: toMinor(t.adjustment) };
}
function adjustmentTo(a: AdjustmentDto | null) {
  if (!a) return {};
  return a.kind === 'percent' ? { adjustment: a.value, adjustmentType: 'percent' } : { adjustment: toUnits(a.value), adjustmentType: 'fixed' };
}

const desc = (t: Raw) => (typeof t.description === 'string' && t.description ? { description: t.description } : {});

/**
 * Actual's list → API list. Actual's UI splits "refill to a cap" into a `limit` plus a
 * `refill`; they're merged back into one refill here (and a bare limit becomes a simple
 * template with no monthly amount, which is what `#template up to N` parses to).
 */
export function fromActual(list: Raw[]): AutomationDto[] {
  const limit = list.find((t) => t.type === 'limit');
  const hasRefill = list.some((t) => t.type === 'refill');
  const out: AutomationDto[] = [];
  for (const t of list) {
    const priority = Number(t.priority ?? 0) || 0;
    switch (t.type) {
      case 'simple':
        out.push({ type: 'simple', priority, monthly: t.monthly == null ? null : toMinor(t.monthly), limit: limitFrom(t.limit), ...desc(t) });
        break;
      case 'periodic': {
        const p = (t.period ?? {}) as Raw;
        out.push({
          type: 'periodic', priority, amount: toMinor(t.amount),
          every: { unit: (p.period as 'day') ?? 'month', count: Number(p.amount ?? 1) },
          starting: String(t.starting ?? ''), limit: limitFrom(t.limit), ...desc(t),
        });
        break;
      }
      case 'by':
      case 'spend':
        out.push({
          type: 'by', priority, amount: toMinor(t.amount), month: String(t.month),
          repeat: t.annual === undefined ? null : { unit: t.annual ? 'year' : 'month', count: Number(t.repeat ?? 1) },
          spendFrom: t.type === 'spend' && typeof t.from === 'string' ? t.from : null, ...desc(t),
        });
        break;
      case 'schedule':
        out.push({ type: 'schedule', priority, schedule: String(t.name ?? ''), full: !!t.full, adjustment: adjustmentFrom(t), ...desc(t) });
        break;
      case 'average':
        out.push({ type: 'average', priority, months: Number(t.numMonths ?? 3), adjustment: adjustmentFrom(t), ...desc(t) });
        break;
      case 'copy':
        out.push({ type: 'copy', priority, monthsAgo: Number(t.lookBack ?? 1), ...desc(t) });
        break;
      case 'percentage':
        out.push({ type: 'percentage', priority, percent: Number(t.percent), of: String(t.category ?? 'all income'), previous: !!t.previous, ...desc(t) });
        break;
      case 'refill':
        if (limit) out.push({ type: 'refill', priority, limit: limitFrom(limit)!, ...desc(t) });
        break;
      case 'limit':
        if (!hasRefill) out.push({ type: 'simple', priority, monthly: null, limit: limitFrom(t), ...desc(t) });
        break;
      case 'remainder':
        out.push({ type: 'remainder', weight: Number(t.weight ?? 1), limit: limitFrom(t.limit), ...desc(t) });
        break;
      case 'goal':
        out.push({ type: 'goal', amount: toMinor(t.amount), ...desc(t) });
        break;
      case 'error':
        out.push({ type: 'error', line: String(t.line ?? ''), error: String(t.error ?? 'Could not read this line') });
        break;
      default:
        break;
    }
  }
  return out;
}

/** API list → Actual's list (validated). */
export function toActual(list: AutomationDto[]): Raw[] {
  const out: Raw[] = [];
  const caps = list.filter((a) => (a.type === 'refill') || ((a.type === 'simple' || a.type === 'periodic' || a.type === 'remainder') && a.limit));
  if (caps.length > 1) throw ApiError.validation('Only one cap ("up to") per category');
  if (list.filter((a) => a.type === 'goal').length > 1) throw ApiError.validation('Only one long-term goal per category');
  for (const a of list) {
    const d = 'description' in a && a.description ? { description: a.description } : {};
    const t = { directive: 'template', ...d };
    const priority = 'priority' in a ? checkPriority(a.priority) : 0;
    switch (a.type) {
      case 'simple':
        if (a.monthly == null && !a.limit) throw ApiError.validation('A fixed amount needs an amount or a cap');
        out.push({ ...t, type: 'simple', priority, monthly: a.monthly == null ? null : toUnits(positive(a.monthly, 'Amount')), ...(a.limit ? { limit: limitTo(checkLimit(a.limit)) } : {}) });
        break;
      case 'periodic':
        if (!DAY.test(a.starting)) throw ApiError.validation('Choose a start date');
        if (!['day', 'week', 'month', 'year'].includes(a.every.unit) || !(a.every.count >= 1)) throw ApiError.validation('Choose how often');
        out.push({
          ...t, type: 'periodic', priority, amount: toUnits(positive(a.amount, 'Amount')),
          period: { period: a.every.unit, amount: Math.floor(a.every.count) }, starting: a.starting,
          ...(a.limit ? { limit: limitTo(checkLimit(a.limit)) } : {}),
        });
        break;
      case 'by':
        if (!MONTH.test(a.month)) throw ApiError.validation('Choose the month to save it by');
        if (a.spendFrom && (!MONTH.test(a.spendFrom) || a.spendFrom > a.month)) throw ApiError.validation('Spending can start at the latest in the target month');
        out.push({
          ...t, type: a.spendFrom ? 'spend' : 'by', priority, amount: toUnits(positive(a.amount, 'Amount')), month: a.month,
          ...(a.spendFrom ? { from: a.spendFrom } : {}),
          ...(a.repeat ? { annual: a.repeat.unit === 'year', repeat: Math.max(1, Math.floor(a.repeat.count)) } : {}),
        });
        break;
      case 'schedule':
        if (!a.schedule.trim()) throw ApiError.validation('Choose a schedule');
        checkAdjustment(a.adjustment);
        out.push({ ...t, type: 'schedule', priority, name: a.schedule.trim(), full: a.full, ...adjustmentTo(a.adjustment) });
        break;
      case 'average':
        if (!(a.months >= 1 && a.months <= 36)) throw ApiError.validation('Average over 1 to 36 months');
        checkAdjustment(a.adjustment);
        out.push({ ...t, type: 'average', priority, numMonths: Math.floor(a.months), ...adjustmentTo(a.adjustment) });
        break;
      case 'copy':
        if (!(a.monthsAgo >= 1 && a.monthsAgo <= 36)) throw ApiError.validation('Copy from 1 to 36 months ago');
        out.push({ ...t, type: 'copy', priority, lookBack: Math.floor(a.monthsAgo) });
        break;
      case 'percentage':
        if (!(a.percent > 0 && a.percent <= 100)) throw ApiError.validation('Choose a percentage from 0 to 100');
        if (!a.of.trim()) throw ApiError.validation('Choose which income');
        out.push({ ...t, type: 'percentage', priority, percent: a.percent, category: a.of, previous: a.previous });
        break;
      case 'refill': {
        const limit = checkLimit(a.limit);
        out.push({ directive: 'template', type: 'limit', ...limitTo(limit) });
        out.push({ ...t, type: 'refill', priority });
        break;
      }
      case 'remainder':
        if (!(a.weight > 0)) throw ApiError.validation('Weight must be more than 0');
        out.push({ ...t, type: 'remainder', priority: null, weight: a.weight, ...(a.limit ? { limit: limitTo(checkLimit(a.limit)) } : {}) });
        break;
      case 'goal':
        out.push({ ...d, directive: 'goal', type: 'goal', priority: null, amount: toUnits(positive(a.amount, 'Goal')) });
        break;
      default:
        throw ApiError.validation(`Unknown automation type ${(a as { type: string }).type}`);
    }
  }
  return out;
}

function positive(n: number, what: string) {
  if (!Number.isInteger(n) || n <= 0) throw ApiError.validation(`${what} must be more than 0`);
  return n;
}
function checkPriority(p: number) {
  if (!Number.isInteger(p) || p < 0 || p > 99) throw ApiError.validation('Priority must be 0 to 99');
  return p;
}
function checkLimit(l: LimitDto) {
  positive(l.amount, 'Cap');
  if (!['daily', 'weekly', 'monthly'].includes(l.period)) throw ApiError.validation('Cap period must be daily, weekly or monthly');
  if (l.period === 'weekly' && !(l.start && DAY.test(l.start))) throw ApiError.validation('A weekly cap needs a start date');
  return l;
}
function checkAdjustment(a: AdjustmentDto | null) {
  if (a?.kind === 'percent' && (a.value <= -100 || a.value > 1000)) throw ApiError.validation('Adjust by more than -100% and at most 1000%');
}

type Source = 'ui' | 'notes' | 'none';

export class AutomationOps {
  constructor(private readonly host: ActualHost) {}

  /** Every category and its automations; with a month, what each would budget. */
  list(budgetId: string, month: string | null) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.refreshFromNotes(lib);
      const groups = (await api.getCategoryGroups()) as unknown as Raw[];
      const settings = await this.settings();
      const categories = [];
      for (const g of groups) {
        for (const c of (g.categories as Raw[] | undefined) ?? []) {
          const id = String(c.id);
          const raw = await this.rawFor(lib, id);
          const automations = fromActual(raw);
          const preview = month && raw.length ? await this.dryRun(lib, month, id, raw) : null;
          categories.push({
            categoryId: id,
            name: String(c.name),
            groupId: String(g.id),
            groupName: String(g.name),
            isIncome: !!g.is_income,
            hidden: !!c.hidden || !!g.hidden,
            source: settings.get(id) ?? (raw.length ? 'notes' : 'none'),
            automations,
            projected: preview?.budgeted ?? null,
          });
        }
      }
      return { month, categories };
    });
  }

  one(budgetId: string, categoryId: string, month: string | null) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.assertCategory(categoryId);
      await this.refreshFromNotes(lib, [categoryId]);
      return this.describe(lib, categoryId, month);
    });
  }

  /** Saves the list as UI automations (the category's #template notes stop counting). */
  set(budgetId: string, categoryId: string, automations: AutomationDto[], month: string | null) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const cat = await this.assertCategory(categoryId);
      const templates = toActual(automations);
      if (cat.is_income && templates.some((t) => t.type !== 'goal')) throw ApiError.validation('Income categories can only have a goal');
      await this.host.internal('budget.automations', lib, 'budget/set-category-automations', {
        categoriesWithTemplates: [{ id: categoryId, templates }],
        source: 'ui',
      });
      await settle();
      return this.describe(lib, categoryId, month);
    });
  }

  /** Back to #template notes: the category's automations come from its notes again. */
  useNotes(budgetId: string, categoryId: string, month: string | null) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.assertCategory(categoryId);
      await this.host.internal('budget.automations', lib, 'budget/set-category-automations', {
        categoriesWithTemplates: [{ id: categoryId, templates: [] }],
        source: 'notes',
      });
      await this.refreshFromNotes(lib, [categoryId]);
      await settle();
      return this.describe(lib, categoryId, month);
    });
  }

  /** What these (unsaved) automations would budget this month. */
  preview(budgetId: string, categoryId: string, month: string, automations: AutomationDto[]) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      await this.assertCategory(categoryId);
      const templates = toActual(automations);
      const r = await this.dryRun(lib, month, categoryId, templates);
      return { month, projected: r.budgeted, perAutomation: perAutomation(automations, templates, r.perTemplate) };
    });
  }

  /**
   * Runs automations for a month: every category (fill empty ones, or overwrite), or
   * just some (always overwrites, like Actual's per-category "apply").
   */
  apply(budgetId: string, month: string, opts: { overwrite: boolean; categoryIds?: string[] }) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const handler = opts.categoryIds?.length ? 'budget/apply-multiple-templates' : opts.overwrite ? 'budget/overwrite-goal-template' : 'budget/apply-goal-template';
      const args = opts.categoryIds?.length ? { month, categoryIds: opts.categoryIds } : { month };
      const r = await this.host.internal<{ type?: string; message?: string; pre?: string } | null>('budget.automations', lib, handler, args);
      await settle();
      return { ok: !r?.pre && r?.type !== 'error', message: message(r), details: r?.pre ?? null };
    });
  }

  // ── helpers ──

  private async describe(lib: Lib, categoryId: string, month: string | null) {
    const raw = await this.rawFor(lib, categoryId);
    const settings = await this.settings();
    const automations = fromActual(raw);
    const preview = month && raw.length ? await this.dryRun(lib, month, categoryId, raw) : null;
    const note = ((await api.getNote(categoryId)) as { note?: string | null } | null)?.note ?? '';
    return {
      categoryId,
      source: settings.get(categoryId) ?? (raw.length ? 'notes' : 'none'),
      automations,
      notesHaveTemplates: /#(template|goal)/i.test(note),
      month,
      projected: preview?.budgeted ?? null,
      perAutomation: preview ? perAutomation(automations, raw, preview.perTemplate) : null,
    };
  }

  private async rawFor(lib: Lib, categoryId: string): Promise<Raw[]> {
    const r = await this.host.internal<Record<string, Raw[]>>('budget.automations', lib, 'budget/get-category-automations', categoryId);
    return r?.[categoryId] ?? [];
  }

  /** Categories whose automations were saved as data ('ui'); everything else follows notes. */
  private async settings(): Promise<Map<string, Source>> {
    const { data } = (await api.aqlQuery(api.q('categories').select(['id', 'template_settings']))) as { data: Raw[] };
    const out = new Map<string, Source>();
    for (const r of data) {
      const s = r.template_settings;
      const source = typeof s === 'string' ? (safeJson(s)?.source as string | undefined) : ((s as Raw | null)?.source as string | undefined);
      if (source === 'ui') out.set(String(r.id), 'ui');
    }
    return out;
  }

  /** Re-reads #template lines into goal_def for categories that follow their notes. */
  private async refreshFromNotes(lib: Lib, categoryIds?: string[]) {
    await this.host.internal('budget.automations', lib, 'budget/store-note-templates', categoryIds);
    await settle();
  }

  private async dryRun(lib: Lib, month: string, categoryId: string, templates: Raw[]) {
    const r = await this.host.internal<{ budgeted: number; perTemplate: number[] }>('budget.automations', lib, 'budget/dry-run-category-template', {
      month, categoryId, templates,
    });
    return { budgeted: r?.budgeted ?? 0, perTemplate: r?.perTemplate ?? [] };
  }

  private async assertCategory(categoryId: string) {
    const cat = (await api.getCategories()).find((c) => c.id === categoryId);
    if (!cat) throw ApiError.notFound(`Category ${categoryId} not found`);
    return cat;
  }
}

/**
 * Actual reports a contribution per stored template; the API list merges limit+refill
 * into one entry, so contributions are folded the same way.
 */
function perAutomation(automations: AutomationDto[], raw: Raw[], perTemplate: number[]): number[] {
  const values: number[] = [];
  let i = 0;
  for (const a of automations) {
    if (a.type === 'refill') {
      // limit then refill in Actual's list
      const li = raw.findIndex((t) => t.type === 'limit');
      const ri = raw.findIndex((t) => t.type === 'refill');
      values.push((perTemplate[li] ?? 0) + (perTemplate[ri] ?? 0));
      continue;
    }
    while (i < raw.length && (raw[i]!.type === 'limit' || raw[i]!.type === 'refill')) i++;
    values.push(perTemplate[i] ?? 0);
    i++;
  }
  return values;
}

function message(r: { type?: string; message?: string; pre?: string } | null | undefined): string {
  const known: Record<string, string> = {
    'templates-applied': 'Automations applied',
    'templates-up-to-date': 'Everything is already budgeted',
    'templates-check-passed': 'All automations look good',
    'no-templates': 'No automations yet',
    'template-errors': 'Some automations need fixing',
  };
  return r?.message ? (known[r.message] ?? r.message) : 'Done';
}

const safeJson = (s: string): Raw | null => {
  try {
    return JSON.parse(s) as Raw;
  } catch {
    return null;
  }
};

/** Actual applies writes one macrotask later (see transaction-ops). */
const settle = () => new Promise<void>((r) => setTimeout(r, 0));
