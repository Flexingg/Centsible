import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

/** Actual's RecurConfig, passed through with the fields the app edits. */
export type Recurrence = {
  frequency: 'daily' | 'weekly' | 'monthly' | 'yearly';
  interval?: number;
  start: string;
  endMode?: 'never' | 'after_n_occurrences' | 'on_date';
  endOccurrences?: number;
  endDate?: string;
  skipWeekend?: boolean;
  weekendSolveMode?: 'before' | 'after';
  patterns?: unknown[];
};

export type ScheduleInput = {
  name?: string | null;
  payeeId?: string | null;
  payeeName?: string;
  accountId?: string | null;
  amount?: number;
  amountOp?: 'is' | 'isapprox' | 'isbetween';
  amountMax?: number;
  /** Repeating schedule; mutually exclusive with `date`. */
  recurrence?: Recurrence;
  /** One-time schedule date. */
  date?: string;
  postsTransaction?: boolean;
};

export type RuleInput = {
  stage?: 'pre' | 'post' | null;
  conditionsOp?: 'and' | 'or';
  conditions: Raw[];
  actions: Raw[];
};

const settle = () => new Promise<void>((r) => setTimeout(r, 0));

const isRecurrence = (d: unknown): d is Recurrence => !!d && typeof d === 'object' && 'frequency' in (d as Raw);

/** Schedules, rules, payees, tags and category notes. */
export class PlanningOps {
  constructor(private readonly host: ActualHost) {}

  // ── Schedules ────────────────────────────────────────────────────────────

  schedules(budgetId: string, upcomingCount = 3) {
    return this.host.withBudget(budgetId, 'read', async (lib) => {
      const all = (await api.getSchedules()) as unknown as Raw[];
      return Promise.all(all.map(async (s) => this.toSchedule(lib, s, upcomingCount)));
    });
  }

  createSchedule(budgetId: string, input: ScheduleInput) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const fields = await scheduleFields(input, true);
      const id = await api.createSchedule(fields as Parameters<typeof api.createSchedule>[0]);
      await settle();
      return this.scheduleById(lib, id);
    });
  }

  updateSchedule(budgetId: string, id: string, input: ScheduleInput) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.requireSchedule(id);
      const fields = await scheduleFields(input, false);
      if (Object.keys(fields).length) await api.updateSchedule(id, fields as Parameters<typeof api.updateSchedule>[1], true);
      await settle();
      return this.scheduleById(lib, id);
    });
  }

  deleteSchedule(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await this.requireSchedule(id);
      await api.deleteSchedule(id);
      await settle();
    });
  }

  /** Skips the next occurrence (tier 3). */
  skipSchedule(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.requireSchedule(id);
      await this.host.internal('schedules.skip', lib, 'schedule/skip-next-date', { id });
      await settle();
      return this.scheduleById(lib, id);
    });
  }

  /** Adds the next occurrence as a real transaction now (tier 3). */
  postSchedule(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      await this.requireSchedule(id);
      await this.host.internal('schedules.post', lib, 'schedule/post-transaction', { id });
      await settle();
      return this.scheduleById(lib, id);
    });
  }

  private async requireSchedule(id: string) {
    const s = ((await api.getSchedules()) as unknown as Raw[]).find((x) => x.id === id);
    if (!s) throw ApiError.notFound(`Schedule ${id} not found`);
    return s;
  }

  private async scheduleById(lib: Parameters<Parameters<ActualHost['withBudget']>[2]>[0], id: string) {
    return this.toSchedule(lib, await this.requireSchedule(id), 3);
  }

  private async toSchedule(lib: Parameters<Parameters<ActualHost['withBudget']>[2]>[0], s: Raw, upcomingCount: number) {
    const date = s.date;
    let upcoming: string[] = [];
    if (!s.completed && typeof s.next_date === 'string') {
      upcoming = [s.next_date];
      if (isRecurrence(date) && upcomingCount > 1 && !this.host.isFeatureDisabled('schedules.upcoming')) {
        // Tier 3: Actual's own recurrence engine, so the dates match the web app exactly.
        const dates = await this.host
          .internal<string[]>('schedules.upcoming', lib, 'schedule/get-upcoming-dates', { config: { ...date, start: s.next_date }, count: upcomingCount })
          .catch(() => [s.next_date as string]);
        upcoming = dates;
      }
    }
    const amount = s.amount;
    const range = amount && typeof amount === 'object' ? (amount as { num1: number; num2: number }) : null;
    return {
      id: String(s.id),
      name: typeof s.name === 'string' && s.name ? s.name : null,
      nextDate: typeof s.next_date === 'string' ? s.next_date : null,
      completed: s.completed === true,
      postsTransaction: s.posts_transaction === true,
      payeeId: typeof s.payee === 'string' ? s.payee : null,
      accountId: typeof s.account === 'string' ? s.account : null,
      amount: typeof amount === 'number' ? amount : range ? range.num1 : 0,
      amountMax: range ? range.num2 : null,
      amountOp: typeof s.amountOp === 'string' ? s.amountOp : 'is',
      recurrence: isRecurrence(date) ? date : null,
      date: typeof date === 'string' ? date : null,
      upcoming,
    };
  }

  // ── Rules ────────────────────────────────────────────────────────────────

  rules(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => ((await api.getRules()) as unknown as Raw[]).map(toRule));
  }

  createRule(budgetId: string, input: RuleInput) {
    return this.host.withBudget(budgetId, 'write', async () => {
      validateRule(input);
      const created = (await api.createRule({
        stage: input.stage ?? null,
        conditionsOp: input.conditionsOp ?? 'and',
        conditions: input.conditions,
        actions: input.actions,
      } as unknown as Parameters<typeof api.createRule>[0])) as unknown as Raw;
      return toRule(created);
    });
  }

  updateRule(budgetId: string, id: string, input: RuleInput) {
    return this.host.withBudget(budgetId, 'write', async () => {
      validateRule(input);
      const existing = ((await api.getRules()) as unknown as Raw[]).find((r) => r.id === id);
      if (!existing) throw ApiError.notFound(`Rule ${id} not found`);
      if (isScheduleRule(existing)) throw ApiError.validation('Edit this rule through its schedule');
      const updated = (await api.updateRule({
        id,
        stage: input.stage ?? null,
        conditionsOp: input.conditionsOp ?? 'and',
        conditions: input.conditions,
        actions: input.actions,
      } as unknown as Parameters<typeof api.updateRule>[0])) as unknown as Raw;
      return toRule(updated);
    });
  }

  deleteRule(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const existing = ((await api.getRules()) as unknown as Raw[]).find((r) => r.id === id);
      if (!existing) throw ApiError.notFound(`Rule ${id} not found`);
      if (isScheduleRule(existing)) throw ApiError.validation('Delete the schedule instead');
      await api.deleteRule(id);
    });
  }

  // ── Payees ───────────────────────────────────────────────────────────────

  payeeStats(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const [payees, counts, rules] = await Promise.all([
        api.getPayees(),
        api.aqlQuery(api.q('transactions').groupBy('payee').select(['payee', { count: { $count: '$id' } }])) as Promise<{ data: { payee: string | null; count: number }[] }>,
        api.getRules() as unknown as Promise<Raw[]>,
      ]);
      const byPayee = new Map(counts.data.map((c) => [c.payee, c.count]));
      const ruleCount = new Map<string, number>();
      for (const r of rules) {
        for (const c of (r.conditions as Raw[]) ?? []) {
          if (c.field === 'payee' && typeof c.value === 'string') ruleCount.set(c.value, (ruleCount.get(c.value) ?? 0) + 1);
        }
      }
      return payees
        .map((p) => ({
          id: p.id,
          name: p.name,
          transferAccountId: p.transfer_acct ?? null,
          transactionCount: byPayee.get(p.id) ?? 0,
          ruleCount: ruleCount.get(p.id) ?? 0,
        }))
        .sort((a, b) => b.transactionCount - a.transactionCount || a.name.localeCompare(b.name));
    });
  }

  renamePayee(budgetId: string, id: string, name: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const payee = (await api.getPayees()).find((p) => p.id === id);
      if (!payee) throw ApiError.notFound(`Payee ${id} not found`);
      if (payee.transfer_acct) throw ApiError.validation('Rename the account instead');
      await api.updatePayee(id, { name: name.trim() });
      return { id, name: name.trim(), transferAccountId: null };
    });
  }

  /** Moves every transaction and rule from `mergeIds` onto `targetId`. */
  mergePayees(budgetId: string, targetId: string, mergeIds: string[]) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const payees = await api.getPayees();
      const ids = [targetId, ...mergeIds];
      for (const id of ids) {
        const p = payees.find((x) => x.id === id);
        if (!p) throw ApiError.notFound(`Payee ${id} not found`);
        if (p.transfer_acct) throw ApiError.validation("Transfer payees can't be merged");
      }
      await api.mergePayees(targetId, mergeIds.filter((m) => m !== targetId));
      await settle();
    });
  }

  deletePayee(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const payee = (await api.getPayees()).find((p) => p.id === id);
      if (!payee) throw ApiError.notFound(`Payee ${id} not found`);
      if (payee.transfer_acct) throw ApiError.validation("Transfer payees can't be deleted");
      await api.deletePayee(id);
    });
  }

  // ── Tags ─────────────────────────────────────────────────────────────────

  tags(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => (await api.getTags()).map(toTag));
  }

  createTag(budgetId: string, input: { tag: string; color?: string | null; description?: string | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const tag = input.tag.replace(/^#/, '').trim();
      if ((await api.getTags()).some((t) => t.tag.toLowerCase() === tag.toLowerCase())) throw new ApiError(409, 'conflict', 'Tag already exists');
      const id = await api.createTag({ tag, color: input.color ?? null, description: input.description ?? null } as Parameters<typeof api.createTag>[0]);
      return toTag({ id, tag, color: input.color ?? null, description: input.description ?? null } as Raw);
    });
  }

  updateTag(budgetId: string, id: string, patch: { tag?: string; color?: string | null; description?: string | null }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const existing = (await api.getTags()).find((t) => t.id === id);
      if (!existing) throw ApiError.notFound(`Tag ${id} not found`);
      const fields: Record<string, unknown> = {};
      if (patch.tag !== undefined) fields.tag = patch.tag.replace(/^#/, '').trim();
      if (patch.color !== undefined) fields.color = patch.color;
      if (patch.description !== undefined) fields.description = patch.description;
      await api.updateTag(id, fields);
      return toTag({ ...existing, ...fields } as Raw);
    });
  }

  deleteTag(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      if (!(await api.getTags()).some((t) => t.id === id)) throw ApiError.notFound(`Tag ${id} not found`);
      await api.deleteTag(id);
    });
  }

  // ── Notes (goal templates live in category notes) ────────────────────────

  categoryNote(budgetId: string, categoryId: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      await requireCategory(categoryId);
      const note = (await api.getNote(categoryId)) as { note?: string | null } | null;
      return { categoryId, note: note?.note ?? null };
    });
  }

  setCategoryNote(budgetId: string, categoryId: string, note: string | null) {
    return this.host.withBudget(budgetId, 'write', async () => {
      await requireCategory(categoryId);
      await api.updateNote(categoryId, note ?? '');
      return { categoryId, note: note || null };
    });
  }
}

async function requireCategory(id: string) {
  if (!(await api.getCategories()).some((c) => c.id === id)) throw ApiError.notFound(`Category ${id} not found`);
}

async function scheduleFields(input: ScheduleInput, creating: boolean) {
  const fields: Record<string, unknown> = {};
  if (input.name !== undefined) fields.name = input.name || null;
  if (input.payeeId !== undefined) fields.payee = input.payeeId;
  else if (input.payeeName?.trim()) {
    const name = input.payeeName.trim();
    const existing = (await api.getPayees()).find((p) => !p.transfer_acct && p.name.toLowerCase() === name.toLowerCase());
    fields.payee = existing?.id ?? (await api.createPayee({ name }));
  }
  if (input.accountId !== undefined) fields.account = input.accountId;
  if (input.amountOp !== undefined) fields.amountOp = input.amountOp;
  if (input.amount !== undefined) {
    const op = input.amountOp ?? 'is';
    if (op === 'isbetween') {
      if (input.amountMax === undefined) throw ApiError.validation('amountMax is required for a range');
      fields.amount = { num1: input.amount, num2: input.amountMax };
    } else {
      fields.amount = input.amount;
    }
  }
  if (input.recurrence && input.date) throw ApiError.validation('Use either recurrence or date, not both');
  if (input.recurrence) fields.date = { endMode: 'never', interval: 1, ...input.recurrence };
  else if (input.date) fields.date = input.date;
  if (input.postsTransaction !== undefined) fields.posts_transaction = input.postsTransaction;
  if (creating) {
    if (fields.date === undefined) throw ApiError.validation('A schedule needs a date or a recurrence');
    if (fields.amount === undefined) fields.amount = 0;
    if (fields.amountOp === undefined) fields.amountOp = 'isapprox';
    if (fields.posts_transaction === undefined) fields.posts_transaction = false;
  }
  return fields;
}

const isScheduleRule = (r: Raw) => ((r.actions as Raw[]) ?? []).some((a) => a.op === 'link-schedule');

function toRule(r: Raw) {
  const actions = ((r.actions as Raw[]) ?? []).map(({ field, op, value, type, options }) => ({ field: field ?? null, op, value, type: type ?? null, options: options ?? null }));
  const link = actions.find((a) => a.op === 'link-schedule');
  return {
    id: String(r.id),
    stage: (r.stage as string | null) ?? null,
    conditionsOp: (r.conditionsOp as string) ?? 'and',
    conditions: ((r.conditions as Raw[]) ?? []).map(({ field, op, value, type, options }) => ({ field, op, value, type: type ?? null, options: options ?? null })),
    actions,
    scheduleId: link && typeof link.value === 'string' ? link.value : null,
  };
}

function validateRule(input: RuleInput) {
  if (!input.conditions.length && !input.actions.length) throw ApiError.validation('A rule needs conditions and actions');
  if (!input.actions.length) throw ApiError.validation('A rule needs at least one action');
  if (input.actions.some((a) => a.op === 'link-schedule')) throw ApiError.validation('Schedule rules are managed through schedules');
}

function toTag(t: Raw) {
  return { id: String(t.id), tag: String(t.tag), color: (t.color as string | null) ?? null, description: (t.description as string | null) ?? null };
}
