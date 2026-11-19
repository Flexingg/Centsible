import { createServer, type Server } from 'node:http';

/**
 * A stand-in for Teller's API (teller.io/docs/api) for one or more bank logins: the access
 * token is the HTTP Basic username; GET /accounts, /accounts/:id/balances and
 * /accounts/:id/transactions (start_date, count, from_id; newest first); DELETE /accounts
 * revokes. A login can be marked disconnected, which Teller reports as a 404 with an
 * enrollment.disconnected.* code. Plain HTTP: the client certificate is a TLS detail.
 */
export type FakeTellerTxn = { id: string; date: string; amount: string; description: string; counterparty?: string; status?: 'posted' | 'pending' };
export type FakeTellerAccount = {
  id: string;
  name: string;
  type: 'depository' | 'credit';
  subtype: string;
  lastFour: string;
  institution: string;
  ledger: string;
  transactions: FakeTellerTxn[];
};
export type FakeEnrollment = { token: string; enrollmentId: string; accounts: FakeTellerAccount[]; disconnected?: string | null; revoked?: boolean };

export async function startFakeTeller(enrollments: FakeEnrollment[]) {
  const stats = { requests: 0, revoked: [] as string[], paths: [] as string[] };
  let port = 0;
  const send = (res: import('node:http').ServerResponse, status: number, body: unknown) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  const server: Server = createServer((req, res) => {
    stats.requests++;
    const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
    stats.paths.push(`${req.method} ${url.pathname}`);
    const auth = req.headers.authorization ?? '';
    const token = auth.startsWith('Basic ') ? Buffer.from(auth.slice(6), 'base64').toString().replace(/:$/, '') : '';
    const e = enrollments.find((x) => x.token === token);
    if (!token) return send(res, 401, { error: { code: 'unauthorized', message: 'Missing access token' } });
    if (!e || e.revoked) return send(res, 403, { error: { code: 'forbidden', message: 'Invalid access token' } });
    if (e.disconnected) return send(res, 404, { error: { code: e.disconnected, message: 'Enrollment disconnected' } });
    const toAccount = (a: FakeTellerAccount) => ({
      id: a.id, enrollment_id: e.enrollmentId, name: a.name, type: a.type, subtype: a.subtype, status: 'open', last_four: a.lastFour, currency: 'USD',
      institution: { id: a.institution.toLowerCase().replace(/\W+/g, '_'), name: a.institution },
      links: { self: `/accounts/${a.id}`, balances: `/accounts/${a.id}/balances`, transactions: `/accounts/${a.id}/transactions` },
    });
    if (req.method === 'DELETE' && url.pathname === '/accounts') {
      e.revoked = true;
      stats.revoked.push(e.enrollmentId);
      return res.writeHead(204).end();
    }
    if (req.method === 'GET' && url.pathname === '/accounts') return send(res, 200, e.accounts.map(toAccount));
    const m = /^\/accounts\/([^/]+)\/(balances|transactions)$/.exec(url.pathname);
    const acct = m && e.accounts.find((a) => a.id === decodeURIComponent(m[1]!));
    if (req.method === 'GET' && m && acct) {
      if (m[2] === 'balances') return send(res, 200, { account_id: acct.id, ledger: acct.ledger, available: acct.ledger });
      const start = url.searchParams.get('start_date') ?? '0000-00-00';
      const count = Number(url.searchParams.get('count') ?? 1000);
      const fromId = url.searchParams.get('from_id');
      let list = [...acct.transactions].sort((x, y) => (x.date < y.date ? 1 : x.date > y.date ? -1 : 0)).filter((t) => t.date >= start);
      if (fromId) list = list.slice(list.findIndex((t) => t.id === fromId) + 1);
      return send(
        res,
        200,
        list.slice(0, count).map((t) => ({
          id: t.id, account_id: acct.id, amount: t.amount, date: t.date, description: t.description, status: t.status ?? 'posted', type: 'card_payment',
          running_balance: null,
          details: { processing_status: t.status === 'pending' ? 'pending' : 'complete', category: null, counterparty: t.counterparty ? { name: t.counterparty, type: 'organization' } : null },
          links: { self: `/accounts/${acct.id}/transactions/${t.id}`, account: `/accounts/${acct.id}` },
        })),
      );
    }
    send(res, 404, { error: { code: 'not_found', message: 'Not found' } });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  port = (server.address() as { port: number }).port;
  return { url: `http://127.0.0.1:${port}`, stats, enrollments, stop: () => new Promise<void>((r) => server.close(() => r())) };
}
