import * as api from '@actual-app/api';
import type { ActualHost } from './host.js';
import { BRIDGE_VERSION, CONTRACT_VERSION } from './versions.js';

/** Flags that are false by design today, so they don't mark the bridge as degraded. */
const NOT_YET_IMPLEMENTED = new Set(['budget.tracking']);

const has = (fn: string) => typeof (api as Record<string, unknown>)[fn] === 'function';

/**
 * Feature flags the app uses to show or hide UI. A flag is true only when the bridge
 * implements the endpoint AND the Actual version behind it supports it. New Actual
 * features appear here as new flags; removed ones flip to false instead of crashing.
 */
export function capabilities(host: ActualHost) {
  const features: Record<string, boolean> = {
    household: true,
    'accounts.read': has('getAccounts') && has('getAccountBalance'),
    'categories.read': has('getCategoryGroups'),
    'payees.read': has('getPayees'),
    'transactions.read': has('aqlQuery'),
    'transactions.create': has('addTransactions'),
    'transactions.update': has('updateTransaction'),
    'transactions.delete': has('deleteTransaction'),
    'transactions.search': has('aqlQuery'),
    'transactions.splits': !host.isFeatureDisabled('transactions.splits'),
    'transactions.transfers': has('addTransactions') && has('getPayees'),
    'accounts.write': has('createAccount') && has('updateAccount') && has('closeAccount') && has('reopenAccount'),
    'categories.write': has('createCategory') && has('updateCategory') && has('deleteCategory') && has('createCategoryGroup'),
    preferences: has('getPreferences'),
    'payees.write': has('updatePayee') && has('mergePayees') && has('deletePayee'),
    'rules.read': has('getRules'),
    'rules.write': has('createRule') && has('updateRule') && has('deleteRule'),
    'schedules.read': has('getSchedules'),
    'schedules.write': has('createSchedule') && has('updateSchedule') && has('deleteSchedule'),
    'schedules.skip': !host.isFeatureDisabled('schedules.skip'),
    'schedules.post': !host.isFeatureDisabled('schedules.post'),
    'schedules.discover': !host.isFeatureDisabled('schedules.discover'),
    tags: has('getTags') && has('createTag'),
    'bankSync.run': has('runBankSync'),
    'import.files': !host.isFeatureDisabled('import.files') && has('importTransactions'),
    reconcile: has('updateTransaction') && has('aqlQuery'),
    'reports.cashFlow': has('aqlQuery'),
    'reports.spending': has('aqlQuery'),
    'reports.netWorth': has('aqlQuery'),
    'budget.templates': !host.isFeatureDisabled('budget.templates'),
    'notes.categories': has('getNote') && has('updateNote'),
    'budget.envelope': has('getBudgetMonth') && has('setBudgetAmount'),
    'budget.tracking': false, // not implemented in the bridge yet
    'budget.carryover': has('setBudgetCarryover'),
    'budget.hold': has('holdBudgetForNextMonth') && has('resetBudgetHold'),
    'budget.moveMoney': !host.isFeatureDisabled('budget.moveMoney'),
  };

  const compatibility = host.compatibility();
  const lostFeature = Object.entries(features).some(([k, v]) => !v && !NOT_YET_IMPLEMENTED.has(k));
  const status = !host.actualServerVersion
    ? 'unavailable'
    : compatibility === 'api_older' || lostFeature
      ? 'degraded'
      : 'ok';

  return {
    contract: CONTRACT_VERSION,
    bridge: { version: BRIDGE_VERSION },
    actual: { serverVersion: host.actualServerVersion, apiVersion: host.apiVersion, compatibility },
    status,
    features,
  };
}
