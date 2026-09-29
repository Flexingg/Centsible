import { randomUUID } from 'node:crypto';
import * as api from '@actual-app/api';
import { ApiError } from '../errors.js';
import { toTransaction, type TransactionDto } from '../mappers/index.js';
import type { ActualHost } from './host.js';

export type SplitInput = { id?: string; amount: number; categoryId?: string | null; notes?: string | null };

export type NewTransaction = {
  id: string;
  accountId: string;
  date: string;
  amount: number;
  payeeId?: string;
  payeeName?: string;
  categoryId?: string;
  notes?: string;
  cleared?: boolean;
  subtransactions?: SplitInput[];
};

export type TransactionPatch = {
  accountId?: string;
  date?: string;
  amount?: number;
  payeeId?: string | null;
  payeeName?: string;
  categoryId?: string | null;
  notes?: string | null;
  cleared?: boolean;
  /** Replaces all splits. An empty array turns a split back into a plain transaction. */
  subtransactions?: SplitInput[];
};

export type TransactionFilter = {
  accountId?: string;
  categoryId?: string;
  payeeId?: string;
  groupId?: string;
  since?: string;
  until?: string;
  q?: string;
  uncategorized?: boolean;
  limit: number;
  offset: number;
};

export const TX_FIELDS = [
  'id',
  'account',
  'date',
  'amount',
  'payee',
  'payee.name',
  'imported_payee',
  'category',
  'notes',
  'cleared',
  'reconciled',
  'transfer_id',
  'is_parent',
  'parent_id',
];

/**
 * Verified against Actual 26.9.0: updateTransaction/deleteTransaction resolve one
 * macrotask before their writes are applied, so a read straight after sees old data.
 */
const settle = () => new Promise<void>((r) => setTimeout(r, 0));

async function findTransaction(id: string): Promise<TransactionDto | null> {
  const { data } = (await api.aqlQuery(
    api.q('transactions').filter({ id }).options({ splits: 'grouped' }).select(TX_FIELDS),
  )) as { data: Record<string, unknown>[] };
  return data[0] ? toTransaction(data[0]) : null;
}

/** Actual's AQL LIKE has no escape clause; drop wildcards from user input. */
const likeTerm = (q: string) => `%${q.replace(/[%_]/g, ' ').trim()}%`;

export class TransactionOps {
  constructor(private readonly host: ActualHost) {}

  list(budgetId: string, f: TransactionFilter) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const filter: Record<string, unknown> = {};
      if (f.accountId) filter.account = f.accountId;
      if (f.categoryId) filter.category = f.categoryId;
      if (f.payeeId) filter.payee = f.payeeId;
      if (f.groupId) filter['category.group'] = f.groupId;
      // One condition per bound: Actual's AQL applies only the first operator in `date: { $gte, $lte }`.
      const dates: Record<string, unknown>[] = [];
      if (f.since) dates.push({ date: { $gte: f.since } });
      if (f.until) dates.push({ date: { $lte: f.until } });
      if (dates.length) filter.$and = dates;
      if (f.uncategorized) {
        // Needs a category: on-budget, not a transfer, not a split parent.
        filter.category = null;
        filter.transfer_id = null;
        filter.is_parent = false;
        filter['account.offbudget'] = false;
      }
      const conditions: Record<string, unknown>[] = [filter];
      if (f.q?.trim()) {
        const term = likeTerm(f.q);
        conditions.push({ $or: [{ 'payee.name': { $like: term } }, { notes: { $like: term } }, { 'category.name': { $like: term } }] });
      }

      // Tier 2: AQL. One extra row tells us whether another page exists.
      const { data } = (await api.aqlQuery(
        api
          .q('transactions')
          .filter(conditions.length === 1 ? filter : { $and: conditions })
          .options({ splits: 'grouped' })
          .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
          .limit(f.limit + 1)
          .offset(f.offset)
          .select(TX_FIELDS),
      )) as { data: Record<string, unknown>[] };

      const items = data.slice(0, f.limit).map(toTransaction);
      // One account, nothing else filtered: the balance after each transaction, like Actual's own column.
      const plain = f.accountId && !f.categoryId && !f.payeeId && !f.groupId && !f.since && !f.until && !f.q?.trim() && !f.uncategorized;
      const balances = plain ? await runningBalances(f.accountId!, f.offset, items.map((t) => t.amount)) : null;
      return { items, hasMore: data.length > f.limit, runningBalances: balances };
    });
  }

  /**
   * One change applied to many transactions (bulk edit), or deleting them. Rows that
   * can't take the change (a split's parent can't have a category, a split's part can't
   * move account) are skipped and reported.
   */
  batch(budgetId: string, ids: string[], change: { set?: BatchSet; delete?: boolean }) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const { data } = (await api.aqlQuery(
        api.q('transactions').filter({ id: { $oneof: ids } }).options({ splits: 'all' }).select(['id', 'is_parent', 'parent_id', 'transfer_id']),
      )) as { data: Record<string, unknown>[] };
      const found = new Map(data.map((r) => [String(r.id), r]));
      const skipped: { id: string; reason: string }[] = [];
      let updated = 0;
      let deleted = 0;
      if (change.set?.accountId) await assertAccount(change.set.accountId);
      const fields: Record<string, unknown> = {};
      if (change.set) {
        const s = change.set;
        if (s.categoryId !== undefined) fields.category = s.categoryId;
        if (s.accountId !== undefined) fields.account = s.accountId;
        if (s.cleared !== undefined) fields.cleared = s.cleared;
        if (s.notes !== undefined) fields.notes = s.notes;
        if (s.date !== undefined) fields.date = s.date;
        if (s.payeeId !== undefined) fields.payee = s.payeeId;
      }
      for (const id of ids) {
        const row = found.get(id);
        if (!row) {
          skipped.push({ id, reason: 'not_found' });
          continue;
        }
        if (change.delete) {
          if (row.parent_id) {
            skipped.push({ id, reason: 'split_part' });
            continue;
          }
          await api.deleteTransaction(id);
          deleted++;
          continue;
        }
        const own = { ...fields };
        if ('category' in own && (row.is_parent || row.transfer_id)) {
          delete own.category;
          if (Object.keys(own).length === 0) {
            skipped.push({ id, reason: row.is_parent ? 'split' : 'transfer' });
            continue;
          }
        }
        if (('account' in own || 'date' in own || 'payee' in own) && row.parent_id) {
          skipped.push({ id, reason: 'split_part' });
          continue;
        }
        if (Object.keys(own).length) {
          await api.updateTransaction(id, own);
          updated++;
        }
      }
      await settle();
      return { updated, deleted, skipped };
    });
  }

  get(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'read', async () => {
      const tx = await findTransaction(id);
      if (!tx) throw ApiError.notFound(`Transaction ${id} not found`);
      return tx;
    });
  }

  /**
   * Idempotent create: the client supplies the UUID, so an outbox replay of the same
   * transaction finds the existing row instead of creating a duplicate.
   */
  create(budgetId: string, tx: NewTransaction): Promise<{ created: boolean; transaction: TransactionDto }> {
    return this.host.withBudget(budgetId, 'write', async () => {
      const existing = await findTransaction(tx.id);
      if (existing) return { created: false, transaction: existing };

      await assertAccount(tx.accountId);
      assertSplitsAddUp(tx.subtransactions, tx.amount);

      await api.addTransactions(
        tx.accountId,
        [
          {
            id: tx.id,
            date: tx.date,
            amount: tx.amount,
            payee: tx.payeeId,
            payee_name: tx.payeeId ? undefined : tx.payeeName,
            category: tx.categoryId,
            notes: tx.notes,
            cleared: tx.cleared,
            subtransactions: tx.subtransactions?.map((s) => ({ amount: s.amount, category: s.categoryId ?? undefined, notes: s.notes ?? undefined })),
          } as Parameters<typeof api.addTransactions>[1][number],
        ],
        // runTransfers creates the other side when the payee is a transfer payee.
        { learnCategories: true, runTransfers: true },
      );

      const created = await findTransaction(tx.id);
      if (!created) throw new Error(`Transaction ${tx.id} was not found after creation`);
      return { created: true, transaction: created };
    });
  }

  update(budgetId: string, id: string, patch: TransactionPatch) {
    return this.host.withBudget(budgetId, 'write', async (lib) => {
      const current = await findTransaction(id);
      if (!current) throw ApiError.notFound(`Transaction ${id} not found`);
      if (current.parentId) throw ApiError.validation('Edit a split through its parent transaction');
      if (patch.accountId) await assertAccount(patch.accountId);

      const fields: Record<string, unknown> = {};
      if (patch.accountId !== undefined) fields.account = patch.accountId;
      if (patch.date !== undefined) fields.date = patch.date;
      if (patch.amount !== undefined) fields.amount = patch.amount;
      if (patch.notes !== undefined) fields.notes = patch.notes;
      if (patch.cleared !== undefined) fields.cleared = patch.cleared;
      if (patch.categoryId !== undefined) fields.category = patch.categoryId;
      if (patch.payeeId !== undefined) fields.payee = patch.payeeId;
      else if (patch.payeeName !== undefined) fields.payee = await payeeIdForName(patch.payeeName);

      if (Object.keys(fields).length) {
        await api.updateTransaction(id, fields);
        await settle();
      }

      if (patch.subtransactions !== undefined) {
        const amount = patch.amount ?? current.amount;
        await this.host.internal('transactions.splits', lib, 'transactions-batch-update', splitDiff(current, patch.subtransactions, amount, fields));
        await settle();
      }

      const updated = await findTransaction(id);
      if (!updated) throw new Error(`Transaction ${id} disappeared during update`);
      return updated;
    });
  }

  delete(budgetId: string, id: string) {
    return this.host.withBudget(budgetId, 'write', async () => {
      const current = await findTransaction(id);
      if (!current) throw ApiError.notFound(`Transaction ${id} not found`);
      await api.deleteTransaction(id); // removes a transfer's other side and a split's children too
      await settle();
    });
  }
}

export type BatchSet = { categoryId?: string | null; accountId?: string; cleared?: boolean; notes?: string | null; date?: string; payeeId?: string | null };

/**
 * Balance after each row of a page of one account's transactions (newest first): today's
 * balance, less everything newer than the page, then less each row as we go down it.
 */
async function runningBalances(accountId: string, offset: number, amounts: number[]): Promise<number[]> {
  let balance = await api.getAccountBalance(accountId);
  if (offset > 0) {
    const { data } = (await api.aqlQuery(
      api
        .q('transactions')
        .filter({ account: accountId })
        .options({ splits: 'grouped' })
        .orderBy([{ date: 'desc' }, { sort_order: 'desc' }, { id: 'desc' }])
        .limit(offset)
        .select(['amount']),
    )) as { data: { amount: number }[] };
    balance -= data.reduce((s, r) => s + r.amount, 0);
  }
  return amounts.map((a) => {
    const after = balance;
    balance -= a;
    return after;
  });
}

async function assertAccount(accountId: string) {
  const accounts = await api.getAccounts();
  const account = accounts.find((a) => a.id === accountId);
  if (!account) throw ApiError.validation(`Unknown account ${accountId}`);
  if (account.closed) throw ApiError.validation('That account is closed');
}

function assertSplitsAddUp(splits: SplitInput[] | undefined, amount: number) {
  if (!splits?.length) return;
  const sum = splits.reduce((s, x) => s + x.amount, 0);
  if (sum !== amount) throw ApiError.validation(`Split amounts (${sum}) must add up to the total (${amount})`);
}

async function payeeIdForName(name: string): Promise<string | null> {
  const trimmed = name.trim();
  if (!trimmed) return null;
  const existing = (await api.getPayees()).find((p) => !p.transfer_acct && p.name.toLowerCase() === trimmed.toLowerCase());
  return existing?.id ?? (await api.createPayee({ name: trimmed }));
}

/** The added/updated/deleted rows that make `current`'s splits equal `next`. */
function splitDiff(current: TransactionDto, next: SplitInput[], amount: number, parentFields: Record<string, unknown>) {
  if (current.transferId) throw ApiError.validation("Transfers can't be split");
  assertSplitsAddUp(next, amount);
  const existing = new Map(current.subtransactions.map((s) => [s.id, s]));
  const keep = new Set(next.map((s) => s.id).filter((x): x is string => !!x && existing.has(x)));
  const account = (parentFields.account as string | undefined) ?? current.accountId;
  const date = (parentFields.date as string | undefined) ?? current.date;

  const added = next
    .filter((s) => !s.id || !existing.has(s.id))
    .map((s) => ({
      id: s.id ?? randomUUID(),
      parent_id: current.id,
      is_child: true,
      account,
      date,
      amount: s.amount,
      category: s.categoryId ?? null,
      notes: s.notes ?? null,
    }));
  const updated: Record<string, unknown>[] = [
    // A parent carries no category of its own.
    { id: current.id, is_parent: next.length > 0, ...(next.length > 0 ? { category: null } : {}) },
    ...next
      .filter((s) => s.id && existing.has(s.id))
      .map((s) => ({ id: s.id, amount: s.amount, category: s.categoryId ?? null, notes: s.notes ?? null, account, date })),
  ];
  const deleted = [...existing.keys()].filter((childId) => !keep.has(childId)).map((childId) => ({ id: childId }));
  return { added, updated, deleted };
}
