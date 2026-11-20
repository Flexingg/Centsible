/**
 * What kind of account each one is. Actual has no account types (only on/off budget), so
 * the household database keeps them per budget; the app groups accounts and figures net
 * worth by them. Null means not set, and the app guesses from the name and balance.
 */
export const ACCOUNT_KINDS = ['cash', 'savings', 'credit', 'investment', 'mortgage', 'loan'] as const;
export type AccountKind = (typeof ACCOUNT_KINDS)[number];

export const isAccountKind = (v: unknown): v is AccountKind => typeof v === 'string' && (ACCOUNT_KINDS as readonly string[]).includes(v);

/** The kind a bank account reported by Plaid most likely is (plaid.com/docs/api/accounts#account-type-schema). */
export function kindFromPlaid(type: string, subtype: string | null | undefined): AccountKind {
  const sub = (subtype ?? '').toLowerCase();
  switch (type) {
    case 'credit':
      return 'credit';
    case 'loan':
      return sub === 'mortgage' || sub === 'home equity' ? 'mortgage' : 'loan';
    case 'investment':
    case 'brokerage':
      return 'investment';
    case 'depository':
      return ['savings', 'money market', 'cd', 'hsa'].includes(sub) ? 'savings' : 'cash';
    default:
      return 'cash';
  }
}
