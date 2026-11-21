import { randomUUID } from 'node:crypto';
import * as api from '@actual-app/api';
import type { HouseholdStore } from '../auth/store.js';
import { ApiError } from '../errors.js';
import { linkedAccounts } from './bank-sync-ops.js';
import type { ActualHost } from './host.js';

type Raw = Record<string, unknown>;

/**
 * A mortgage (or any fixed-rate loan) and the home it's for. The terms are the bridge's to
 * keep (Actual has no loans); the money stays in Actual:
 * - the loan is an off-budget account whose balance is what's owed (negative), and
 * - the home is an off-budget account whose balance is its value,
 * so net worth counts both with no special handling. Each payment's principal can be
 * recorded on the loan account (from the amortization schedule), which is how the balance
 * comes down when the bank doesn't sync the loan.
 */
export type MortgageInput = {
  name: string;
  /** What was borrowed, in cents. */
  principal: number;
  /** Yearly rate in percent, e.g. 6.125. */
  rate: number;
  termMonths: number;
  /** The first payment's date (YYYY-MM-DD); later ones fall on the same day of the month. */
  firstPayment: string;
  /** Taxes and insurance paid with the loan each month (cents). */
  escrow?: number;
  /** Extra principal each month (cents). */
  extra?: number;
  /** Who the payments go to, and from which account (to find them). */
  payeeId?: string | null;
  paymentAccountId?: string | null;
  loanAccountId?: string | null;
  homeAccountId?: string | null;
  /** On create: make the loan account with today's balance (else it's worked out from the schedule). */
  createLoanAccount?: boolean;
  currentBalance?: number | null;
  /** On create: make a home account with this value. */
  homeValue?: number | null;
};
type MortgageDef = Omit<MortgageInput, 'createLoanAccount' | 'currentBalance' | 'homeValue'> & { id: string; recorded: string[] };

export type AmortizationRow = { n: number; date: string; payment: number; interest: number; principal: number; extra: number; balance: number };

const key = (budgetId: string) => `mortgages.${budgetId}`;
const today = () => new Date().toISOString().slice(0, 10);

/** The monthly principal and interest that pays [principal] off in [months] at [rate]% a year. */
export function monthlyPayment(principal: number, rate: number, months: number): number {
  const r = rate / 100 / 12;
  if (r === 0) return Math.ceil(principal / months);
  return Math.round((principal * r) / (1 - Math.pow(1 + r, -months)));
}

/** Adds months to a date, keeping the day (or the month's last day when it's shorter). */
export function addMonthsDay(date: string, n: number): string {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  const last = new Date(Date.UTC(y, m - 1 + n + 1, 0)).getUTCDate();
  return new Date(Date.UTC(y, m - 1 + n, Math.min(d, last))).toISOString().slice(0, 10);
}

/**
 * The payment-by-payment schedule from [balance] on: each month's interest on what's
 * owed, the rest of [payment] (plus [extra]) off the principal, until it's paid.
 */
export function amortize(balance: number, rate: number, payment: number, extra: number, firstDate: string, firstN = 1, maxRows = 600): AmortizationRow[] {
  const r = rate / 100 / 12;
  const rows: AmortizationRow[] = [];
  let owed = balance;
  for (let i = 0; owed > 0 && i < maxRows; i++) {
    const interest = Math.round(owed * r);
    let principal = Math.min(owed, payment - interest);
    if (principal <= 0) throw ApiError.validation("That payment doesn't cover the interest.");
    // Rounding each payment to the cent leaves a few cents or dollars at the end: the last payment takes them.
    if (owed - principal > 0 && owed - principal - extra < Math.max(100, payment / 100) && extra === 0) principal = owed;
    const ex = Math.min(extra, owed - principal);
    owed -= principal + ex;
    if (owed < 0) {
      principal += owed;
      owed = 0;
    }
    rows.push({ n: firstN + i, date: addMonthsDay(firstDate, i), payment: interest + principal, interest, principal, extra: ex, balance: owed });
  }
  return rows;
}

export class MortgageOps {
  /** Accounts in a budget that sync through Plaid (Actual itself only knows SimpleFIN's links). */
  plaidLinked?: (budgetId: string) => Set<string>;

  constructor(
    private readonly host: ActualHost,
    private readonly store: HouseholdStore,
  ) {}

  /** The bank updates this account (SimpleFIN in Actual, or Plaid through the bridge). */
  private async synced(budgetId: string, accountId: string) {
    return (this.plaidLinked?.(budgetId).has(accountId) ?? false) || (await linkedAccounts()).some((a) => a.id === accountId && a.source);
  }

  private defs(budgetId: string) {
    return this.store.getSetting<MortgageDef[]>(key(budgetId)) ?? [];
  }

  private save(budgetId: string, defs: MortgageDef[]) {
    this.store.setSetting(key(budgetId), defs);
  }

  list(budgetId: string) {
    return this.host.withBudget(budgetId, 'read', async () => ({ items: await Promise.all(this.defs(budgetId).map((d) => this.describe(d, false, budgetId))) }));
  }

  get(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'read', async () => this.describe(this.find(budgetId, id), true, budgetId));
  }

  /**
   * Uses [accountId] (typically one SimpleFIN or Plaid syncs) as the mortgage's loan
   * account: what it owes becomes the balance, and nothing needs recording by hand. With
   * [retireOld], the previous loan account (one the bank didn't update, like the one this
   * tool makes) is brought to zero with a note and closed, so net worth doesn't count the
   * loan twice; its history stays. Null unlinks the loan account.
   */
  linkLoanAccount(budgetId: string, id: string, input: { accountId: string | null; retireOld?: boolean }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const defs = this.defs(budgetId);
      const def = defs.find((d) => d.id === id);
      if (!def) throw ApiError.notFound('Mortgage not found');
      const accounts = (await api.getAccounts()) as Raw[];
      const target = input.accountId ? accounts.find((a) => a.id === input.accountId) : null;
      if (input.accountId) {
        if (!target) throw ApiError.notFound('Account not found');
        if (target.closed) throw ApiError.validation('Reopen that account first.');
        if (input.accountId === def.homeAccountId) throw ApiError.validation("That's the home's account. Pick the account that owes the loan.");
      }
      const old = def.loanAccountId && def.loanAccountId !== input.accountId ? accounts.find((a) => a.id === def.loanAccountId && !a.closed) : undefined;
      let retired: string | null = null;
      if (input.retireOld && old && !(await this.synced(budgetId, String(old.id)))) {
        const balance = await api.getAccountBalance(String(old.id));
        if (balance !== 0) {
          await api.addTransactions(String(old.id), [
            { date: today(), amount: -balance, payee_name: def.name, notes: target ? `Balance now tracked in ${String(target.name)}` : 'Loan account retired' },
          ]);
          await settle();
        }
        await api.closeAccount(String(old.id));
        retired = String(old.name);
      }
      def.loanAccountId = input.accountId;
      def.recorded = [];
      if (input.accountId && !this.store.accountKinds(budgetId).has(input.accountId)) this.store.setAccountKind(budgetId, input.accountId, 'mortgage');
      this.save(budgetId, defs);
      await settle();
      return { ...(await this.describe(def, false, budgetId)), retiredAccount: retired };
    });
  }

  create(budgetId: string, input: MortgageInput) {
    return this.host.withBudget(budgetId, 'write', async () => {
      validate(input);
      const def: MortgageDef = { ...pick(input), id: randomUUID(), recorded: [] };
      if (input.createLoanAccount && !def.loanAccountId) {
        const owed = input.currentBalance ?? scheduledBalance(def, today());
        def.loanAccountId = await api.createAccount({ name: input.name.trim(), offbudget: true }, -Math.max(0, owed));
        this.store.setAccountKind(budgetId, def.loanAccountId, 'mortgage');
        // Payments made before today are already in that balance.
        def.recorded = (await this.payments(def)).map((p) => p.id);
      }
      if (input.homeValue && !def.homeAccountId) {
        def.homeAccountId = await api.createAccount({ name: `${input.name.trim()} home`.replace(/ mortgage home$/i, ' home'), offbudget: true }, input.homeValue);
      }
      await settle();
      this.save(budgetId, [...this.defs(budgetId), def]);
      return this.describe(def, false, budgetId);
    });
  }

  update(budgetId: string, id: string, input: MortgageInput) {
    return this.host.withBudget(budgetId, 'read', async () => {
      validate(input);
      const defs = this.defs(budgetId);
      const i = defs.findIndex((d) => d.id === id);
      if (i < 0) throw ApiError.notFound('Mortgage not found');
      defs[i] = { ...pick(input), loanAccountId: input.loanAccountId ?? defs[i]!.loanAccountId, homeAccountId: input.homeAccountId ?? defs[i]!.homeAccountId, id, recorded: defs[i]!.recorded };
      this.save(budgetId, defs);
      return this.describe(defs[i]!, false, budgetId);
    });
  }

  remove(budgetId: string, id: string) {
    this.find(budgetId, id);
    this.save(budgetId, this.defs(budgetId).filter((d) => d.id !== id));
  }

  /** The home's value now: a "Value update" transaction on the home account for the difference. */
  setHomeValue(budgetId: string, id: string, value: number) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const defs = this.defs(budgetId);
      const def = defs.find((d) => d.id === id);
      if (!def) throw ApiError.notFound('Mortgage not found');
      if (!def.homeAccountId) {
        def.homeAccountId = await api.createAccount({ name: `${def.name} home`.replace(/ mortgage home$/i, ' home'), offbudget: true }, value);
        this.save(budgetId, defs);
      } else {
        const now = await api.getAccountBalance(def.homeAccountId);
        if (now !== value) await api.addTransactions(def.homeAccountId, [{ date: today(), amount: value - now, payee_name: 'Home value update', notes: 'Home value update' }]);
      }
      await settle();
      return this.describe(def, false, budgetId);
    });
  }

  /**
   * Takes each payment's principal (as the schedule splits it) off the loan account, for
   * payments not recorded yet. Not for a loan the bank syncs: its balance comes from there.
   */
  recordPrincipal(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const defs = this.defs(budgetId);
      const def = defs.find((d) => d.id === id);
      if (!def) throw ApiError.notFound('Mortgage not found');
      if (!def.loanAccountId) throw ApiError.validation('Link or create the loan account first.');
      if (await this.synced(budgetId, def.loanAccountId)) throw ApiError.validation('Your bank updates this loan’s balance, so there’s nothing to record.');
      const pending = (await this.payments(def)).filter((p) => !def.recorded.includes(p.id));
      const rows = fullSchedule(def);
      const adds = [];
      for (const p of pending) {
        const row = rowFor(rows, p.date);
        if (!row) continue;
        adds.push({ date: p.date, amount: row.principal + row.extra, payee_name: def.name, notes: `Principal from the payment on ${p.date}` });
        def.recorded.push(p.id);
      }
      if (adds.length) await api.addTransactions(def.loanAccountId, adds);
      await settle();
      this.save(budgetId, defs);
      return { recorded: adds.length, principal: adds.reduce((s, a) => s + a.amount, 0) };
    });
  }

  private find(budgetId: string, id: string) {
    const d = this.defs(budgetId).find((x) => x.id === id);
    if (!d) throw ApiError.notFound('Mortgage not found');
    return d;
  }

  /** Payments to the lender since the loan started (by payee, and account when set). */
  private async payments(def: MortgageDef): Promise<{ id: string; date: string; amount: number }[]> {
    if (!def.payeeId) return [];
    const since = addMonthsDay(def.firstPayment, -1);
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ payee: def.payeeId, date: { $gte: since }, amount: { $lt: 0 }, is_child: false, ...(def.paymentAccountId ? { account: def.paymentAccountId } : {}) })
        .options({ splits: 'inline' })
        .select(['id', 'date', 'amount'])
        .orderBy({ date: 'asc' }),
    )) as { data: Raw[] };
    return data.map((r) => ({ id: String(r.id), date: String(r.date), amount: Number(r.amount) }));
  }

  private async describe(def: MortgageDef, withSchedule: boolean, budgetId: string) {
    const pAndI = monthlyPayment(def.principal, def.rate, def.termMonths);
    const original = amortize(def.principal, def.rate, pAndI, 0, def.firstPayment);
    const planned = fullSchedule(def);
    const now = today();
    const accounts = (await api.getAccounts()) as Raw[];
    const loanExists = !!def.loanAccountId && accounts.some((a) => a.id === def.loanAccountId);
    const homeExists = !!def.homeAccountId && accounts.some((a) => a.id === def.homeAccountId);
    const scheduled = scheduledBalance(def, now);
    const synced = loanExists ? await this.synced(budgetId, def.loanAccountId!) : false;
    const loanRaw = loanExists ? await api.getAccountBalance(def.loanAccountId!) : null;
    // A bank may report what's owed as a positive number: owed is owed either way.
    const balance = loanRaw === null ? scheduled : synced ? Math.abs(loanRaw) : Math.max(0, -loanRaw);
    const homeValue = homeExists ? await api.getAccountBalance(def.homeAccountId!) : null;
    // From what's owed now, paying the same each month: when it's done and what interest is left.
    const nextDate = planned.find((r) => r.date > now)?.date ?? addMonthsDay(def.firstPayment, planned.length);
    const ahead = balance > 0 ? amortize(balance, def.rate, pAndI, def.extra ?? 0, nextDate) : [];
    const paidCount = planned.filter((r) => r.date <= now).length;
    const payments = await this.payments(def);
    const interestPaid = planned.filter((r) => r.date <= now).reduce((s, r) => s + r.interest, 0);
    return {
      id: def.id,
      name: def.name,
      principal: def.principal,
      rate: def.rate,
      termMonths: def.termMonths,
      firstPayment: def.firstPayment,
      escrow: def.escrow ?? 0,
      extra: def.extra ?? 0,
      payeeId: def.payeeId ?? null,
      paymentAccountId: def.paymentAccountId ?? null,
      loanAccountId: loanExists ? def.loanAccountId! : null,
      homeAccountId: homeExists ? def.homeAccountId! : null,
      loanSynced: synced,
      /** The loan account's balance is positive, so net worth counts the loan as an asset (the bank reports it that way). */
      loanPositive: loanRaw !== null && loanRaw > 0,
      monthlyPayment: pAndI,
      monthlyTotal: pAndI + (def.escrow ?? 0) + (def.extra ?? 0),
      balance,
      scheduledBalance: scheduled,
      /** Positive: owed less than the schedule says by now. */
      aheadBy: scheduled - balance,
      paymentsMade: paidCount,
      paymentsLeft: ahead.length,
      payoffDate: ahead.at(-1)?.date ?? now,
      originalPayoffDate: original.at(-1)?.date ?? def.firstPayment,
      interestPaid,
      interestLeft: ahead.reduce((s, r) => s + r.interest, 0),
      /** Against the original schedule, by paying extra (or having paid ahead). */
      interestSaved: Math.max(0, original.reduce((s, r) => s + r.interest, 0) - interestPaid - ahead.reduce((s, r) => s + r.interest, 0)),
      homeValue,
      equity: homeValue !== null ? homeValue - balance : null,
      paymentsFound: payments.length,
      unrecorded: loanExists && !synced ? payments.filter((p) => !def.recorded.includes(p.id)).length : 0,
      lastPayment: payments.at(-1) ?? null,
      ...(withSchedule
        ? {
            schedule: planned.map((r) => {
              const paid = payments.find((p) => Math.abs(Date.parse(p.date) - Date.parse(r.date)) <= 15 * 86_400_000);
              return { ...r, paidOn: paid?.date ?? null, paidAmount: paid?.amount ?? null };
            }),
          }
        : {}),
    };
  }
}

function fullSchedule(def: MortgageDef) {
  return amortize(def.principal, def.rate, monthlyPayment(def.principal, def.rate, def.termMonths), def.extra ?? 0, def.firstPayment);
}

/** What the schedule says is owed after the payments due by [date]. */
function scheduledBalance(def: Pick<MortgageDef, 'principal' | 'rate' | 'termMonths' | 'firstPayment' | 'extra'>, date: string) {
  const rows = amortize(def.principal, def.rate, monthlyPayment(def.principal, def.rate, def.termMonths), def.extra ?? 0, def.firstPayment);
  return rows.filter((r) => r.date <= date).at(-1)?.balance ?? def.principal;
}

/** The schedule's row for a payment made around [date] (a couple of weeks either way). */
function rowFor(rows: AmortizationRow[], date: string) {
  const t = Date.parse(date);
  return rows.reduce<AmortizationRow | null>((best, r) => {
    const d = Math.abs(Date.parse(r.date) - t);
    return d <= 20 * 86_400_000 && (!best || d < Math.abs(Date.parse(best.date) - t)) ? r : best;
  }, null);
}

function pick(i: MortgageInput): Omit<MortgageDef, 'id' | 'recorded'> {
  return {
    name: i.name.trim(),
    principal: i.principal,
    rate: i.rate,
    termMonths: i.termMonths,
    firstPayment: i.firstPayment,
    escrow: i.escrow ?? 0,
    extra: i.extra ?? 0,
    payeeId: i.payeeId ?? null,
    paymentAccountId: i.paymentAccountId ?? null,
    loanAccountId: i.loanAccountId ?? null,
    homeAccountId: i.homeAccountId ?? null,
  };
}

function validate(i: MortgageInput) {
  if (!i.name?.trim()) throw ApiError.validation('Give it a name.');
  if (!(i.principal > 0)) throw ApiError.validation('Enter what was borrowed.');
  if (!(i.rate >= 0 && i.rate < 50)) throw ApiError.validation('The rate should be a yearly percentage, like 6.5.');
  if (!(i.termMonths >= 1 && i.termMonths <= 600)) throw ApiError.validation('The term should be between 1 month and 50 years.');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(i.firstPayment)) throw ApiError.validation('Enter the first payment date.');
}

const settle = () => new Promise<void>((r) => setTimeout(r, 0));
