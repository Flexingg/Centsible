import { createServer, type Server } from 'node:http';

/**
 * A stand-in for Plaid's API (plaid.com/docs/api) with one or more bank logins ("items").
 * Every call is a JSON POST with client_id and secret. /transactions/sync works from a
 * per-item change log: the cursor is a position in it, so tests add transactions with
 * add(), change them with modify() and withdraw them with remove(). An item can be made to
 * fail with any Plaid error code.
 */
export type FakePlaidTxn = { id: string; accountId: string; date: string; amount: number; name: string; merchant?: string; pending?: boolean };
export type FakePlaidAccount = { id: string; name: string; type: 'depository' | 'credit' | 'loan'; subtype: string; mask: string; current: number };
export type FakePlaidItem = {
  itemId: string;
  accessToken: string;
  publicToken: string;
  institution: string;
  accounts: FakePlaidAccount[];
  log: { kind: 'added' | 'modified' | 'removed'; txn: FakePlaidTxn }[];
  error?: string | null;
  removed?: boolean;
};

// Made-up values, shaped to pass the bridge's format check; not Plaid keys.
export const PLAID_CLIENT_ID = 'fakeclientidfortests0001';
export const PLAID_SECRET = 'fakesecretfortestsonlynotreal0';

export async function startFakePlaid(items: Omit<FakePlaidItem, 'log'>[], opts: { pageSize?: number } = {}) {
  const state: FakePlaidItem[] = items.map((i) => ({ ...i, log: [] }));
  const stats = { linkTokens: [] as Record<string, unknown>[], removed: [] as string[], syncCalls: 0 };
  const send = (res: import('node:http').ServerResponse, status: number, body: unknown) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  const fail = (res: import('node:http').ServerResponse, status: number, type: string, code: string, message: string) =>
    send(res, status, { error_type: type, error_code: code, error_message: message, display_message: null, request_id: 'req' });
  const byToken = (t: unknown) => state.find((i) => i.accessToken === t && !i.removed);
  const toTxn = (t: FakePlaidTxn) => ({
    transaction_id: t.id, account_id: t.accountId, amount: t.amount, iso_currency_code: 'USD', date: t.date, authorized_date: t.date,
    name: t.name, merchant_name: t.merchant ?? null, pending: t.pending ?? false, pending_transaction_id: null, counterparties: t.merchant ? [{ name: t.merchant, type: 'merchant' }] : [],
  });

  const server: Server = createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const body = (raw ? JSON.parse(raw) : {}) as Record<string, any>;
      if (req.method !== 'POST') return fail(res, 404, 'INVALID_REQUEST', 'NOT_FOUND', 'not found');
      if (body.client_id !== PLAID_CLIENT_ID || body.secret !== PLAID_SECRET) return fail(res, 400, 'INVALID_INPUT', 'INVALID_API_KEYS', 'invalid client_id or secret provided');
      const path = req.url ?? '';
      if (path === '/link/token/create') {
        stats.linkTokens.push(body);
        if (body.access_token && !byToken(body.access_token)) return fail(res, 400, 'INVALID_INPUT', 'INVALID_ACCESS_TOKEN', 'bad token');
        return send(res, 200, { link_token: `link-sandbox-${stats.linkTokens.length}`, expiration: '2026-12-01T00:00:00Z', request_id: 'req' });
      }
      if (path === '/item/public_token/exchange') {
        const item = state.find((i) => i.publicToken === body.public_token);
        if (!item) return fail(res, 400, 'INVALID_INPUT', 'INVALID_PUBLIC_TOKEN', 'provided public token is in an invalid format');
        return send(res, 200, { access_token: item.accessToken, item_id: item.itemId, request_id: 'req' });
      }
      const item = byToken(body.access_token);
      if (!item) return fail(res, 400, 'INVALID_INPUT', 'INVALID_ACCESS_TOKEN', 'provided access token is in an invalid format');
      if (path === '/item/remove') {
        item.removed = true;
        stats.removed.push(item.itemId);
        return send(res, 200, { request_id: 'req' });
      }
      if (item.error) return fail(res, 400, 'ITEM_ERROR', item.error, 'the login details of this item have changed');
      if (path === '/accounts/get') {
        return send(res, 200, {
          accounts: item.accounts.map((a) => ({ account_id: a.id, name: a.name, official_name: null, mask: a.mask, type: a.type, subtype: a.subtype, balances: { current: a.current, available: null, iso_currency_code: 'USD' } })),
          item: { item_id: item.itemId, institution_id: 'ins_1', institution_name: item.institution },
          request_id: 'req',
        });
      }
      if (path === '/transactions/sync') {
        stats.syncCalls++;
        const start = body.cursor ? Number(body.cursor) : 0;
        const count = Math.min(Number(body.count ?? 100), opts.pageSize ?? 500);
        const slice = item.log.slice(start, start + count);
        const next = start + slice.length;
        return send(res, 200, {
          added: slice.filter((e) => e.kind === 'added').map((e) => toTxn(e.txn)),
          modified: slice.filter((e) => e.kind === 'modified').map((e) => toTxn(e.txn)),
          removed: slice.filter((e) => e.kind === 'removed').map((e) => ({ transaction_id: e.txn.id, account_id: e.txn.accountId })),
          next_cursor: String(next),
          has_more: next < item.log.length,
          transactions_update_status: 'HISTORICAL_UPDATE_COMPLETE',
          accounts: [],
          request_id: 'req',
        });
      }
      fail(res, 404, 'INVALID_REQUEST', 'NOT_FOUND', 'not found');
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const port = (server.address() as { port: number }).port;
  const find = (itemId: string) => state.find((i) => i.itemId === itemId)!;
  return {
    url: `http://127.0.0.1:${port}`,
    stats,
    items: state,
    item: find,
    add: (itemId: string, ...txns: FakePlaidTxn[]) => find(itemId).log.push(...txns.map((txn) => ({ kind: 'added' as const, txn }))),
    modify: (itemId: string, txn: FakePlaidTxn) => find(itemId).log.push({ kind: 'modified', txn }),
    remove: (itemId: string, txn: FakePlaidTxn) => find(itemId).log.push({ kind: 'removed', txn }),
    stop: () => new Promise<void>((r) => server.close(() => r())),
  };
}
