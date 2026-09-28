import { createServer, type Server } from 'node:http';

/**
 * A stand-in for SimpleFIN Bridge that speaks the real protocol (simplefin.org/protocol):
 * a setup token is base64 of a claim URL; POSTing the claim URL once returns an access
 * URL with basic-auth credentials; GET {access}/accounts returns accounts, with
 * transactions unless balances-only=1. Counts requests so tests can check batching.
 */
export type FakeAccount = {
  id: string;
  name: string;
  org: { domain: string; name: string; id?: string };
  balance: string;
  transactions: { id: string; posted: number; amount: string; description: string; payee?: string; memo?: string; pending?: boolean; transacted_at?: number }[];
};

export async function startFakeSimpleFin(accounts: FakeAccount[]) {
  let claimed = false;
  const stats = { claims: 0, accountRequests: 0, lastQuery: '', queries: [] as URLSearchParams[] };
  const user = 'fakeuser';
  const pass = 'fakepass';
  let port = 0;
  const server: Server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
    if (req.method === 'POST' && url.pathname === '/claim/demo') {
      stats.claims++;
      if (claimed) {
        res.writeHead(403).end('Forbidden');
        return;
      }
      claimed = true;
      res.writeHead(200, { 'content-type': 'text/plain' }).end(`http://${user}:${pass}@127.0.0.1:${port}/simplefin`);
      return;
    }
    if (req.method === 'GET' && url.pathname === '/simplefin/accounts') {
      stats.accountRequests++;
      stats.lastQuery = url.search;
      stats.queries.push(url.searchParams);
      if (req.headers.authorization !== `Basic ${Buffer.from(`${user}:${pass}`).toString('base64')}`) {
        res.writeHead(403).end('Forbidden');
        return;
      }
      const only = url.searchParams.getAll('account');
      const balancesOnly = url.searchParams.get('balances-only') === '1';
      const start = Number(url.searchParams.get('start-date') ?? 0);
      const end = url.searchParams.has('end-date') ? Number(url.searchParams.get('end-date')) : Infinity;

      const body = {
        errors: [],
        accounts: accounts
          .filter((a) => !only.length || only.includes(a.id))
          .map((a) => ({
            org: a.org,
            id: a.id,
            name: a.name,
            currency: 'USD',
            balance: a.balance,
            'available-balance': a.balance,
            'balance-date': Math.floor(Date.now() / 1000),
            transactions: balancesOnly ? [] : a.transactions.filter((t) => (t.posted >= start && t.posted < end) || t.pending),
          })),
      };
      res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(body));
      return;
    }
    res.writeHead(404).end();
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  port = (server.address() as { port: number }).port;
  return {
    setupToken: Buffer.from(`http://127.0.0.1:${port}/claim/demo`).toString('base64'),
    stats,
    accounts,
    stop: () => new Promise<void>((r) => server.close(() => r())),
  };
}

export const day = (d: string) => Math.floor(Date.parse(`${d}T12:00:00Z`) / 1000);
