/**
 * Actual shapes -> contract DTOs. Everything that knows Actual's field names and sign
 * conventions lives here, so an Actual rename is a one-line fix in one file.
 */

type Raw = Record<string, unknown>;

const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const bool = (v: unknown): boolean => v === true || v === 1;

export type BudgetType = 'envelope' | 'tracking' | 'unknown';

export function budgetTypeFromPrefs(prefs: Raw): BudgetType {
  // Actual stores "envelope" (formerly "rollover") or "tracking" (formerly "report").
  // A budget that never changed it has no pref and is envelope.
  const t = prefs.budgetType;
  if (t === undefined || t === null || t === 'envelope' || t === 'rollover') return 'envelope';
  if (t === 'tracking' || t === 'report') return 'tracking';
  return 'unknown';
}

export function toAccount(raw: Raw, balance: number, sync?: { syncSource: string | null; lastSync: string | null; bankSyncStatus?: string | null }) {
  return {
    id: String(raw.id),
    name: String(raw.name ?? ''),
    offBudget: bool(raw.offbudget),
    closed: bool(raw.closed),
    balance,
    accountGroupId: str(raw.account_group_id),
    syncSource: sync?.syncSource ?? null,
    bankSyncStatus: sync?.bankSyncStatus ?? null,
    lastSync: sync?.lastSync ?? null,
  };
}

export function toCategory(raw: Raw) {
  return {
    id: String(raw.id),
    name: String(raw.name ?? ''),
    groupId: String(raw.group_id ?? ''),
    isIncome: bool(raw.is_income),
    hidden: bool(raw.hidden),
  };
}

export function toCategoryGroup(raw: Raw) {
  const cats = Array.isArray(raw.categories) ? (raw.categories as Raw[]) : [];
  return {
    id: String(raw.id),
    name: String(raw.name ?? ''),
    isIncome: bool(raw.is_income),
    hidden: bool(raw.hidden),
    categories: cats.map(toCategory),
  };
}

export function toPayee(raw: Raw) {
  return {
    id: String(raw.id),
    name: String(raw.name ?? ''),
    transferAccountId: str(raw.transfer_acct),
  };
}

function toBudgetCategory(raw: Raw) {
  return {
    id: String(raw.id),
    name: String(raw.name ?? ''),
    hidden: bool(raw.hidden),
    budgeted: num(raw.budgeted),
    spent: num(raw.spent),
    balance: num(raw.balance),
    received: num(raw.received),
    carryover: bool(raw.carryover),
  };
}

export function toBudgetMonth(raw: Raw, budgetType: BudgetType) {
  const groups = Array.isArray(raw.categoryGroups) ? (raw.categoryGroups as Raw[]) : [];
  return {
    month: String(raw.month),
    budgetType,
    toBudget: num(raw.toBudget),
    incomeAvailable: num(raw.incomeAvailable),
    lastMonthOverspent: num(raw.lastMonthOverspent),
    forNextMonth: num(raw.forNextMonth),
    fromLastMonth: num(raw.fromLastMonth),
    // Actual reports this as a negative number (money leaving "To Budget").
    totalBudgeted: Math.abs(num(raw.totalBudgeted)),
    totalIncome: num(raw.totalIncome),
    totalSpent: num(raw.totalSpent),
    totalBalance: num(raw.totalBalance),
    groups: groups.map((g) => {
      const cats = Array.isArray(g.categories) ? (g.categories as Raw[]) : [];
      return {
        id: String(g.id),
        name: String(g.name ?? ''),
        isIncome: bool(g.is_income),
        hidden: bool(g.hidden),
        budgeted: num(g.budgeted),
        spent: num(g.spent),
        balance: num(g.balance),
        received: num(g.received),
        categories: cats.map(toBudgetCategory),
      };
    }),
  };
}

export type TransactionDto = {
  id: string;
  accountId: string;
  date: string;
  amount: number;
  payeeId: string | null;
  payeeName: string | null;
  categoryId: string | null;
  notes: string | null;
  cleared: boolean;
  reconciled: boolean;
  transferId: string | null;
  isParent: boolean;
  parentId: string | null;
  subtransactions: TransactionDto[];
};

export function toTransaction(raw: Raw): TransactionDto {
  const subs = Array.isArray(raw.subtransactions) ? (raw.subtransactions as Raw[]) : [];
  return {
    id: String(raw.id),
    accountId: String(raw.account),
    date: String(raw.date),
    amount: num(raw.amount),
    payeeId: str(raw.payee),
    payeeName: str(raw['payee.name']) ?? str(raw.imported_payee),
    categoryId: str(raw.category),
    notes: str(raw.notes),
    cleared: bool(raw.cleared),
    reconciled: bool(raw.reconciled),
    transferId: str(raw.transfer_id),
    isParent: bool(raw.is_parent),
    parentId: str(raw.parent_id),
    subtransactions: subs.map(toTransaction),
  };
}

/** Synced budget preferences with Actual's defaults filled in. */
export function toPreferences(prefs: Raw) {
  const first = Number(prefs.firstDayOfWeekIdx ?? 0);
  return {
    budgetType: budgetTypeFromPrefs(prefs),
    currencyCode: typeof prefs.defaultCurrencyCode === 'string' && prefs.defaultCurrencyCode ? prefs.defaultCurrencyCode : 'USD',
    numberFormat: typeof prefs.numberFormat === 'string' ? prefs.numberFormat : 'comma-dot',
    dateFormat: typeof prefs.dateFormat === 'string' ? prefs.dateFormat : 'MM/dd/yyyy',
    firstDayOfWeek: Number.isInteger(first) && first >= 0 && first <= 6 ? first : 0,
    hideFraction: prefs.hideFraction === true || prefs.hideFraction === 'true',
  };
}
