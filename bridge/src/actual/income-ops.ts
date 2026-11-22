import { randomUUID } from 'node:crypto';
import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import type { ActualHost } from './host.js';
import type { OffBooks } from './off-books.js';
import { BUDGET_FLOW, dateRange } from './report-ops.js';

type Row = Record<string, unknown>;

export type IncomeMode = 'all' | 'categories';
export type IncomeSettings = { mode: IncomeMode; categoryIds: string[] };

export type DeductionKind = 'tax' | 'retirement' | 'benefit' | 'other';
export type DeductionMode = 'fixed' | 'percent';

export type PayStubDeduction = {
  name: string;
  kind: DeductionKind;
  /** Positive cents deducted from gross pay. */
  amount: number;
  mode?: DeductionMode | null;
  /** Percent of gross pay (e.g. 6.0 for 6%), when mode is 'percent'. */
  rate?: number | null;
};

export type PayStubInput = {
  date: string;
  employer?: string | null;
  hours?: number | null;
  /** Cents per hour, optional. */
  hourlyRate?: number | null;
  /** Gross pay in cents. */
  gross: number;
  deductions: PayStubDeduction[];
  notes?: string | null;
};

export type PayStub = {
  id: string;
  date: string;
  employer: string | null;
  hours: number | null;
  hourlyRate: number | null;
  gross: number;
  deductions: PayStubDeduction[];
  net: number;
  notes: string | null;
};

export type LearnedDeduction = {
  name: string;
  kind: DeductionKind;
  mode: DeductionMode;
  /** Fixed amount in cents (used when mode === 'fixed', or latest observed amount). */
  amount: number;
  /** Percent of gross (e.g. 6.2), used when mode === 'percent'. */
  rate: number;
};

export type LearnedTemplate = {
  hourlyRate: number | null;
  employer: string | null;
  deductions: LearnedDeduction[];
};

const settingsKey = (budgetId: string) => `income.settings.${budgetId}`;
const stubsKey = (budgetId: string) => `income.paystubs.${budgetId}`;
const round1 = (n: number) => Math.round(n * 10) / 10;
const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * Income tracking (all money coming in, or chosen income categories), yearly variations and
 * projections, plus manual pay stubs (hours, gross, 401(k)/tax/benefit deductions) with
 * automatic fixed-vs-percentage template learning.
 */
export class IncomeOps {
  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
    private readonly offBooks?: OffBooks,
    private readonly now: () => Date = () => new Date(),
  ) {}

  getSettings(budgetId: string): IncomeSettings {
    const saved = this.store.getSetting<IncomeSettings>(settingsKey(budgetId));
    if (!saved) return { mode: 'all', categoryIds: [] };
    return {
      mode: saved.mode === 'categories' ? 'categories' : 'all',
      categoryIds: Array.isArray(saved.categoryIds) ? saved.categoryIds.filter((s) => typeof s === 'string' && s.length > 0) : [],
    };
  }

  setSettings(budgetId: string, input: IncomeSettings): IncomeSettings {
    const clean: IncomeSettings = {
      mode: input.mode === 'categories' ? 'categories' : 'all',
      categoryIds: [...new Set((input.categoryIds ?? []).map((s) => s.trim()).filter(Boolean))],
    };
    this.store.setSetting(settingsKey(budgetId), clean);
    return clean;
  }

  listPayStubs(budgetId: string): PayStub[] {
    const raw = this.store.getSetting<PayStub[]>(stubsKey(budgetId)) ?? [];
    return [...raw].sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
  }

  createPayStub(budgetId: string, input: PayStubInput): PayStub {
    const stub = normalizeStub(randomUUID(), input);
    const list = [stub, ...this.listPayStubs(budgetId)].sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
    this.store.setSetting(stubsKey(budgetId), list);
    return stub;
  }

  updatePayStub(budgetId: string, id: string, input: PayStubInput): PayStub {
    const list = this.listPayStubs(budgetId);
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) throw ApiError.notFound(`Pay stub ${id} not found`);
    const updated = normalizeStub(id, input);
    list[idx] = updated;
    list.sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
    this.store.setSetting(stubsKey(budgetId), list);
    return updated;
  }

  deletePayStub(budgetId: string, id: string): void {
    const list = this.listPayStubs(budgetId);
    const next = list.filter((s) => s.id !== id);
    if (next.length === list.length) throw ApiError.notFound(`Pay stub ${id} not found`);
    this.store.setSetting(stubsKey(budgetId), next);
  }

  overview(budgetId: string, year?: number) {
    const today = this.now();
    const targetYear = year ?? today.getUTCFullYear();
    if (targetYear < 1970 || targetYear > 2100) throw ApiError.validation('Year out of range');

    const settings = this.getSettings(budgetId);
    const useCategories = settings.mode === 'categories' && settings.categoryIds.length > 0;
    const allStubs = this.listPayStubs(budgetId);
    const yearStubs = allStubs.filter((s) => s.date.startsWith(`${targetYear}-`));
    const template = learnTemplate(allStubs);

    return this.host.withBudget(budgetId, 'read', async () => {
      const hidden = (this.offBooks?.filter(budgetId).$and as unknown[] | undefined) ?? [];
      const baseScope = (from: string, to: string): Row => {
        const range = (dateRange(from, to).$and as unknown[]) ?? [];
        const conds: unknown[] = [...range, ...hidden];
        if (useCategories) {
          conds.push({ category: { $oneof: settings.categoryIds } });
        } else {
          conds.push({ amount: { $gt: 0 } });
        }
        return { ...BUDGET_FLOW, $and: conds };
      };

      const [{ data: curRows }, { data: prevRows }] = (await Promise.all([
        api.aqlQuery(
          api
            .q('transactions')
            .filter(baseScope(`${targetYear}-01-01`, `${targetYear}-12-31`))
            .groupBy([{ $month: '$date' }])
            .select([{ month: { $month: '$date' } }, { amount: { $sum: '$amount' } }, { count: { $count: '$id' } }]),
        ),
        api.aqlQuery(
          api
            .q('transactions')
            .filter(baseScope(`${targetYear - 1}-01-01`, `${targetYear - 1}-12-31`))
            .calculate({ $sum: '$amount' }),
        ),
      ])) as [{ data: Row[] }, { data: number | null }];

      const byMonth = new Map(curRows.map((r) => [String(r.month), { amount: Number(r.amount ?? 0), count: Number(r.count ?? 0) }]));
      const curYear = today.getUTCFullYear();
      const curMonth = today.getUTCMonth() + 1;
      const elapsedMonths = targetYear < curYear ? 12 : targetYear > curYear ? 0 : curMonth;
      const remainingMonths = Math.max(0, 12 - elapsedMonths);

      const months = Array.from({ length: 12 }, (_, idx) => {
        const m = `${targetYear}-${String(idx + 1).padStart(2, '0')}`;
        const entry = byMonth.get(m) ?? { amount: 0, count: 0 };
        const prevM = idx > 0 ? `${targetYear}-${String(idx).padStart(2, '0')}` : null;
        const prevAmt = prevM ? (byMonth.get(prevM)?.amount ?? 0) : 0;
        const deltaFromPrev = idx > 0 && idx < elapsedMonths ? entry.amount - prevAmt : null;
        const deltaPercent = deltaFromPrev !== null && prevAmt > 0 ? round1((deltaFromPrev / prevAmt) * 100) : null;
        return { month: m, amount: entry.amount, count: entry.count, deltaFromPrev, deltaPercent };
      });

      const ytdTotal = months.reduce((s, m) => s + m.amount, 0);
      const elapsedSlice = months.slice(0, elapsedMonths);
      const activeMonths = elapsedSlice.filter((m) => m.amount > 0);
      const monthlyAverage = activeMonths.length > 0 ? Math.round(activeMonths.reduce((s, m) => s + m.amount, 0) / activeMonths.length) : 0;
      const minMonth = activeMonths.length > 0 ? activeMonths.reduce((a, b) => (b.amount < a.amount ? b : a)) : null;
      const maxMonth = activeMonths.length > 0 ? activeMonths.reduce((a, b) => (b.amount > a.amount ? b : a)) : null;

      let variationPercent = 0;
      if (activeMonths.length >= 2 && monthlyAverage > 0) {
        const variance = activeMonths.reduce((s, m) => s + Math.pow(m.amount - monthlyAverage, 2), 0) / activeMonths.length;
        variationPercent = round1((Math.sqrt(variance) / monthlyAverage) * 100);
      }

      // Projection: use trailing 3 completed months (or active elapsed months when early in the year).
      const completed = targetYear === curYear ? months.slice(0, Math.max(0, curMonth - 1)).filter((m) => m.amount > 0) : activeMonths;
      const trailingSource = completed.length > 0 ? completed.slice(-3) : activeMonths.slice(-3);
      const trailingAvg = trailingSource.length > 0 ? trailingSource.reduce((s, m) => s + m.amount, 0) / trailingSource.length : 0;
      const projectedAnnual = targetYear < curYear ? ytdTotal : Math.round(ytdTotal + trailingAvg * remainingMonths);

      const prevYearTotal = Number(prevRows ?? 0);
      const payStubSummary = summarizePayStubs(yearStubs, elapsedMonths, remainingMonths, targetYear < curYear);

      return {
        year: targetYear,
        mode: settings.mode,
        categoryIds: settings.categoryIds,
        months,
        summary: {
          ytdTotal,
          prevYearTotal,
          monthlyAverage,
          projectedAnnual,
          elapsedMonths,
          remainingMonths,
          variationPercent,
          minMonth: minMonth ? { month: minMonth.month, amount: minMonth.amount } : null,
          maxMonth: maxMonth ? { month: maxMonth.month, amount: maxMonth.amount } : null,
        },
        payStubs: yearStubs,
        payStubSummary,
        template,
      };
    });
  }
}

/**
 * Learns a reusable paycheck template (hourly rate + fixed vs %-of-gross deductions)
 * from previously entered pay stubs.
 */
export function learnTemplate(stubs: PayStub[]): LearnedTemplate {
  if (!stubs.length) return { hourlyRate: null, employer: null, deductions: [] };
  const recent = [...stubs].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 12);
  const employer = recent.find((s) => s.employer)?.employer ?? null;

  const rateSamples = recent
    .slice(0, 6)
    .map((s) => {
      if (s.hourlyRate != null && s.hourlyRate > 0) return s.hourlyRate;
      if (s.hours != null && s.hours > 0 && s.gross > 0) return Math.round(s.gross / s.hours);
      return null;
    })
    .filter((n): n is number => n !== null);
  const hourlyRate = rateSamples.length > 0 ? Math.round(rateSamples.reduce((a, b) => a + b, 0) / rateSamples.length) : null;

  type Occ = { name: string; kind: DeductionKind; amount: number; gross: number; mode?: DeductionMode | null; rate?: number | null };
  const groups = new Map<string, Occ[]>();
  for (const stub of recent) {
    for (const d of stub.deductions) {
      const key = d.name.trim().toLowerCase();
      if (!key) continue;
      const list = groups.get(key) ?? [];
      list.push({ name: d.name.trim(), kind: d.kind, amount: d.amount, gross: stub.gross, mode: d.mode, rate: d.rate });
      groups.set(key, list);
    }
  }

  const deductions: LearnedDeduction[] = [];
  for (const occs of groups.values()) {
    const latest = occs[0]!;
    const validGross = occs.filter((o) => o.gross > 0);
    const avgRate =
      validGross.length > 0
        ? round2(validGross.reduce((s, o) => s + (o.rate != null && o.mode === 'percent' ? o.rate : (o.amount / o.gross) * 100), 0) / validGross.length)
        : 0;

    let mode: DeductionMode;
    if (latest.mode === 'fixed' || latest.mode === 'percent') {
      mode = latest.mode;
    } else if (validGross.length >= 2) {
      const grosses = validGross.map((o) => o.gross);
      const grossSpread = Math.max(...grosses) - Math.min(...grosses);
      if (grossSpread >= 500) {
        const amounts = validGross.map((o) => o.amount);
        const amtSpread = Math.max(...amounts) - Math.min(...amounts);
        const amtMean = amounts.reduce((a, b) => a + b, 0) / amounts.length;
        mode = amtSpread <= 100 || (amtMean > 0 && amtSpread / amtMean < 0.015) ? 'fixed' : 'percent';
      } else {
        mode = latest.kind === 'tax' || latest.kind === 'retirement' ? 'percent' : 'fixed';
      }
    } else {
      mode = latest.kind === 'tax' || latest.kind === 'retirement' ? 'percent' : 'fixed';
    }

    deductions.push({
      name: latest.name,
      kind: latest.kind,
      mode,
      amount: latest.amount,
      rate: mode === 'percent' ? (latest.mode === 'percent' && latest.rate != null ? round2(latest.rate) : avgRate) : 0,
    });
  }

  const kindOrder: Record<DeductionKind, number> = { retirement: 0, tax: 1, benefit: 2, other: 3 };
  deductions.sort((a, b) => kindOrder[a.kind] - kindOrder[b.kind] || a.name.localeCompare(b.name));
  return { hourlyRate, employer, deductions };
}

function summarizePayStubs(stubs: PayStub[], elapsedMonths: number, remainingMonths: number, isPastYear: boolean) {
  const totalGross = stubs.reduce((s, x) => s + x.gross, 0);
  const totalNet = stubs.reduce((s, x) => s + x.net, 0);
  const totalDeductions = totalGross - totalNet;
  const totalHours = round2(stubs.reduce((s, x) => s + (x.hours ?? 0), 0));

  const byKind = { tax: 0, retirement: 0, benefit: 0, other: 0 };
  const byName = new Map<string, { name: string; kind: DeductionKind; total: number; grossSum: number }>();

  for (const stub of stubs) {
    for (const d of stub.deductions) {
      byKind[d.kind] += d.amount;
      const k = d.name.trim().toLowerCase();
      const cur = byName.get(k);
      if (cur) {
        cur.total += d.amount;
        cur.grossSum += stub.gross;
      } else {
        byName.set(k, { name: d.name.trim(), kind: d.kind, total: d.amount, grossSum: stub.gross });
      }
    }
  }

  const byDeduction = [...byName.values()]
    .map((x) => ({
      name: x.name,
      kind: x.kind,
      total: x.total,
      avgPercentOfGross: x.grossSum > 0 ? round1((x.total / x.grossSum) * 100) : 0,
    }))
    .sort((a, b) => b.total - a.total);

  const effectiveTaxRate = totalGross > 0 ? round1((byKind.tax / totalGross) * 100) : 0;
  const takeHomeRate = totalGross > 0 ? round1((totalNet / totalGross) * 100) : 0;

  // Annualized projections from pay stubs when in the current year.
  const scale = !isPastYear && elapsedMonths > 0 && remainingMonths > 0 ? 12 / elapsedMonths : 1;
  const projectedAnnualGross = Math.round(totalGross * scale);
  const projectedAnnualRetirement = Math.round(byKind.retirement * scale);
  const projectedAnnualTax = Math.round(byKind.tax * scale);

  return {
    stubCount: stubs.length,
    totalHours,
    totalGross,
    totalNet,
    totalDeductions,
    effectiveTaxRate,
    takeHomeRate,
    byKind,
    byDeduction,
    projectedAnnualGross,
    projectedAnnualRetirement,
    projectedAnnualTax,
  };
}

function normalizeStub(id: string, input: PayStubInput): PayStub {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.date)) throw ApiError.validation('Date must be YYYY-MM-DD');
  if (!Number.isInteger(input.gross) || input.gross < 0) throw ApiError.validation('Gross pay must be a non-negative integer (cents)');
  const deductions: PayStubDeduction[] = (input.deductions ?? [])
    .map((d) => {
      const name = d.name.trim();
      if (!name) throw ApiError.validation('Each deduction needs a name');
      const amount = Math.round(d.amount);
      if (!Number.isFinite(amount) || amount < 0) throw ApiError.validation(`Deduction "${name}" must be non-negative`);
      const kind: DeductionKind = d.kind === 'tax' || d.kind === 'retirement' || d.kind === 'benefit' ? d.kind : 'other';
      const mode: DeductionMode | null = d.mode === 'fixed' || d.mode === 'percent' ? d.mode : null;
      const rate = d.rate != null && Number.isFinite(d.rate) ? round2(d.rate) : null;
      return { name, kind, amount, mode, rate };
    });
  const totalDeductions = deductions.reduce((s, d) => s + d.amount, 0);
  const net = input.gross - totalDeductions;
  const hours = input.hours != null && Number.isFinite(input.hours) && input.hours > 0 ? round2(input.hours) : null;
  const hourlyRate = input.hourlyRate != null && Number.isFinite(input.hourlyRate) && input.hourlyRate > 0 ? Math.round(input.hourlyRate) : null;
  return {
    id,
    date: input.date,
    employer: input.employer?.trim() || null,
    hours,
    hourlyRate,
    gross: input.gross,
    deductions,
    net,
    notes: input.notes?.trim() || null,
  };
}
