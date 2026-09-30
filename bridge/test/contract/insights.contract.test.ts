import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync, rmSync } from 'node:fs';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ActualHost } from '../../src/actual/host.js';
import { HouseholdStore } from '../../src/auth/store.js';
import type { BridgeConfig } from '../../src/config.js';
import { createDeps } from '../../src/deps.js';
import { buildServer } from '../../src/http/server.js';
import { SetupService } from '../../src/setup.js';
import { startSeededActual, type SeededActual } from '../support/actual-server.js';
import { expectContract, recordFixture } from '../support/contract.js';

// Trends and alerts, subscription discovery, year in review and net worth by account,
// against a real Actual server. History goes into complete past months so results
// don't depend on what day of the month the suite runs.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '.test-data', 'insights');
const silent = { debug() {}, info() {}, warn() {} };

let actual: SeededActual;
let store: HouseholdStore;
let host: ActualHost;
let app: FastifyInstance;
let owner: string;
let viewer: string;
let b: string;
let M: string;
const shift = (month: string, n: number) => {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m - 1 + n, 1)).toISOString().slice(0, 7);
};

function tokenFor(role: 'owner' | 'viewer') {
  const m = store.createMember({ displayName: role, role, budgetIds: [b] });
  const { code } = store.createPairingCode(m.id, null);
  return store.redeemPairingCode(code, { name: `${role} phone`, platform: 'android' })!.tokens.accessToken;
}

async function call(method: string, url: string, path: string, token: string, body?: unknown) {
  const res = await app.inject({ method: method as 'GET', url: `/v1/budgets/${b}${url}`, headers: { authorization: `Bearer ${token}` }, ...(body !== undefined ? { payload: body as object } : {}) });
  return { status: res.statusCode, body: expectContract(method, `/v1/budgets/{budgetId}${path}`, res) as Record<string, any> };
}

const add = (account: string, date: string, amount: number, payeeName: string, categoryId: string) =>
  call('POST', '/transactions', '/transactions', owner, { id: randomUUID(), accountId: account, date, amount, payeeName, categoryId });

beforeAll(async () => {
  actual = await startSeededActual(join(root, 'actual'), 5100 + Math.floor(Math.random() * 800));
  b = actual.budgetId;
  M = actual.month;
  const dataDir = join(root, 'bridge');
  rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(join(dataDir, 'actual'), { recursive: true });
  const config: BridgeConfig = {
    port: 0, host: '127.0.0.1', dataDir, publicUrl: 'https://budget-api.example.com', trustProxy: false,
    actual: { serverUrl: actual.url, password: actual.password, budgetPasswords: {} },
    syncMaxAgeMs: 0, accessTokenTtlSec: 3600, refreshTokenTtlSec: 3600, logLevel: 'silent',
  };
  store = new HouseholdStore(join(dataDir, 'bridge.sqlite'));
  host = new ActualHost(config, silent);
  await host.start();
  app = await buildServer(createDeps(config, store, host, new SetupService(config, store, host, silent, () => host.start()), silent), { logger: false });
  owner = tokenFor('owner');
  viewer = tokenFor('viewer');

  const { Food, General } = actual.categories as Record<string, string>;
  const { checking, card } = actual.accounts;
  for (const back of [4, 3, 2]) {
    await add(checking, `${shift(M, -back)}-10`, -30000, 'Grocer', Food!);
    await add(checking, `${shift(M, -back)}-11`, -500, 'Coffee Cart', General!);
  }
  const last = shift(M, -1);
  await add(checking, `${last}-10`, -30000, 'Grocer', Food!);
  await add(checking, `${last}-18`, -20000, 'Farmers Market', Food!); // Food: 500.00 against a usual 300.00
  await add(checking, `${last}-12`, -4000, 'Coffee Cart', General!); // eight times the usual coffee
  await add(checking, `${last}-20`, -250000, 'Jeweler', General!); // a big first visit
  for (const back of [3, 2, 1]) await add(card, `${shift(M, -back)}-12`, -1599, 'Netflix', General!);
});

afterAll(async () => {
  await app?.close();
  await host?.stop();
  store?.close();
  await actual?.stop();
});

describe('trends and alerts', () => {
  it('compares a month with the three before it and flags what stands out', async () => {
    const last = shift(M, -1);
    const res = await call('GET', `/insights?month=${last}`, '/insights', viewer);
    expect(res.status).toBe(200);
    const i = res.body;
    expect(i.daysElapsed).toBe(i.daysInMonth);
    const food = i.categories.find((c: any) => c.name === 'Food');
    expect(food).toMatchObject({ spent: 50000, typical: 30000, projected: 50000, changePct: 67 });
    const kinds = i.alerts.map((a: any) => [a.kind, a.title]);
    expect(kinds).toContainEqual(['category-pace', 'Food is 67% above usual']);
    expect(kinds).toContainEqual(['unusual-transaction', 'Bigger than usual at Coffee Cart']);
    expect(kinds).toContainEqual(['new-merchant', 'First time at Jeweler']);
    expect(i.alerts[0].severity).toBe('warning');
    recordFixture('insights', i);
  });

  it("won't look into the future", async () => {
    expect((await call('GET', `/insights?month=${shift(M, 2)}`, '/insights', viewer)).status).toBe(400);
  });
});

describe('subscriptions', () => {
  it("finds recurring payments that aren't scheduled yet", async () => {
    const res = await call('GET', '/subscriptions', '/subscriptions', viewer);
    expect(res.status).toBe(200);
    const netflix = res.body.candidates.find((c: any) => c.payeeName === 'Netflix');
    expect(netflix).toMatchObject({ accountId: actual.accounts.card, amount: -1599, income: false, yearlyAmount: -1599 * 12, lastDate: `${shift(M, -1)}-12` });
    expect(netflix.recurrence).toMatchObject({ frequency: 'monthly', interval: 1 });
  });

  it('can be dismissed', async () => {
    const netflix = (await call('GET', '/subscriptions', '/subscriptions', viewer)).body.candidates.find((c: any) => c.payeeName === 'Netflix');
    expect((await call('POST', '/subscriptions/dismiss', '/subscriptions/dismiss', viewer, { payeeId: netflix.payeeId })).status).toBe(403);
    expect((await call('POST', '/subscriptions/dismiss', '/subscriptions/dismiss', owner, { payeeId: netflix.payeeId })).status).toBe(204);
    const after = (await call('GET', '/subscriptions', '/subscriptions', viewer)).body.candidates;
    expect(after.some((c: any) => c.payeeName === 'Netflix')).toBe(false);
  });

  it('finds a bill whose amount moves around, and the forecast estimates it', async () => {
    const { General } = actual.categories as Record<string, string>;
    const bills = [-9000, -12000, -16000, -11000, -8700];
    for (const [i, amount] of bills.entries()) await add(actual.accounts.checking, `${shift(M, i - 7)}-${i % 2 ? 12 : 13}`, amount, 'City Power', General!);
    const res = await call('GET', '/subscriptions', '/subscriptions', viewer);
    recordFixture('subscriptions', res.body);
    const power = res.body.patterns.find((p: any) => p.payeeName === 'City Power');
    expect(power).toMatchObject({ income: false, varies: true, min: -8700, max: -16000, firstWeekday: false });
    expect(power.days[0]).toBeGreaterThanOrEqual(12);
    expect(power.next.basis).toBe('average');
    // Actual's own discovery can't keep up with the amounts, so it isn't a plain candidate too.
    expect(res.body.candidates.some((c: any) => c.payeeName === 'City Power')).toBe(false);

    const s = (
      await call('POST', '/schedules', '/schedules', owner, {
        name: 'City Power', payeeId: power.payeeId, accountId: actual.accounts.checking, amount: -8700, amountMax: -16000, amountOp: 'isbetween',
        recurrence: { frequency: 'monthly', interval: 1, start: `${shift(M, 1)}-12`, skipWeekend: true, weekendSolveMode: 'after', patterns: [{ type: 'day', value: 12 }] },
      })
    ).body;
    const forecast = (await call('GET', '/forecast?days=90', '/forecast', viewer)).body;
    const next = forecast.events.find((e: any) => e.scheduleId === s.id);
    // No bill last year this month: the average of the last three (-16000, -11000, -8700).
    expect(next).toMatchObject({ estimate: 'average', amount: -11900 });
    // Scheduled now, so it's no longer suggested.
    expect((await call('GET', '/subscriptions', '/subscriptions', viewer)).body.patterns.some((p: any) => p.payeeName === 'City Power')).toBe(false);
  });

  it('spots a price change on a scheduled bill', async () => {
    const s = (
      await call('POST', '/schedules', '/schedules', owner, {
        name: 'Gym', payeeName: 'Gym', accountId: actual.accounts.checking, amount: -4000, amountOp: 'is',
        recurrence: { frequency: 'monthly', interval: 1, start: `${shift(M, -2)}-01` },
      })
    ).body;
    await call('POST', `/schedules/${s.id}/post`, '/schedules/{id}/post', owner);
    await call('PATCH', `/schedules/${s.id}`, '/schedules/{id}', owner, { amount: -4500 });
    await call('POST', `/schedules/${s.id}/post`, '/schedules/{id}/post', owner);
    const changes = (await call('GET', '/subscriptions', '/subscriptions', viewer)).body.priceChanges;
    expect(changes).toContainEqual(expect.objectContaining({ scheduleId: s.id, name: 'Gym', previous: -4000, latest: -4500, changePct: 13 }));
  });
});

describe('year in review', () => {
  it('tells the story of a year', async () => {
    const year = Number(shift(M, -1).slice(0, 4));
    const res = await call('GET', `/reports/year-in-review?year=${year}`, '/reports/year-in-review', viewer);
    expect(res.status).toBe(200);
    const r = res.body;
    expect(r).toMatchObject({ year, empty: false });
    expect(r.availableYears).toContain(year);
    expect(r.months).toHaveLength(12);
    expect(r.biggestPurchase).toMatchObject({ payeeName: 'Jeweler', amount: 250000 });
    expect(r.topMerchants[0]).toMatchObject({ name: 'Jeweler', amount: 250000, visits: 1 });
    expect(r.spending).toBe(r.months.reduce((s: number, m: any) => s + m.spending, 0));
    expect(r.saved).toBe(r.income - r.spending);
    expect(r.noSpendDays).toBeGreaterThan(0);
    expect(r.longestNoSpendStreak.days).toBeGreaterThan(0);
    recordFixture('year-in-review', r);
  });

  it('is empty for a year without data, and refuses the future', async () => {
    const empty = (await call('GET', '/reports/year-in-review?year=2001', '/reports/year-in-review', viewer)).body;
    expect(empty).toMatchObject({ empty: true, spending: 0 });
    expect((await call('GET', '/reports/year-in-review?year=2099', '/reports/year-in-review', viewer)).status).toBe(400);
  });
});

describe('reviews of any period', () => {
  const path = '/reports/review';

  it('reviews a month, day by day, against the month before', async () => {
    const last = shift(M, -1);
    const r = (await call('GET', `/reports/review?period=month&date=${last}-15`, path, viewer)).body;
    expect(r).toMatchObject({ period: 'month', start: `${last}-01`, complete: true, empty: false, previousStart: `${shift(M, -2)}-01` });
    expect(r.buckets.length).toBe(Number(r.end.slice(8)));
    expect(r.biggestBucket).toMatchObject({ key: `${last}-20`, spending: 250000 }); // the Jeweler
    expect(r.biggestPurchase).toMatchObject({ payeeName: 'Jeweler' });
    expect(r.previousPeriod).toMatchObject({ spending: 32099 }); // groceries, a coffee and Netflix the month before
    expect(r.spending).toBe(r.buckets.reduce((s: number, b: any) => s + b.spending, 0));
    recordFixture('review-month', r);
  });

  it('reviews a week by day and a quarter by week', async () => {
    const last = shift(M, -1);
    const week = (await call('GET', `/reports/review?period=week&date=${last}-20`, path, viewer)).body;
    expect(week.buckets.map((b: any) => b.label)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    expect(week.start <= `${last}-20` && week.end >= `${last}-20`).toBe(true);
    const quarter = (await call('GET', `/reports/review?period=quarter&date=${last}-20`, path, viewer)).body;
    expect(quarter.label).toMatch(/^Q[1-4] \d{4}$/);
    expect(quarter.buckets.length).toBeGreaterThanOrEqual(13);
  });

  it('defaults to the last finished period, and refuses the future', async () => {
    const r = (await call('GET', '/reports/review?period=month', path, viewer)).body;
    expect(r).toMatchObject({ start: `${shift(M, -1)}-01`, complete: true, nextStart: `${M}-01` });
    expect((await call('GET', `/reports/review?period=week&date=${shift(M, 3)}-01`, path, viewer)).status).toBe(400);
    expect((await call('GET', '/reports/review?period=decade', path, viewer)).status).toBe(400);
  });
});

describe('net worth by account', () => {
  it('breaks the total down per account', async () => {
    const res = await call('GET', '/reports/net-worth?months=6', '/reports/net-worth', viewer);
    const accounts = (await call('GET', '/accounts', '/accounts', viewer)).body.items as any[];
    const nw = res.body;
    expect(nw.accounts.length).toBe(accounts.length);
    for (const a of nw.accounts) {
      expect(a.balances).toHaveLength(nw.points.length);
      expect(a.balances.at(-1)).toBe(accounts.find((x) => x.id === a.accountId).balance);
    }
    expect(nw.accounts.reduce((s: number, a: any) => s + a.balances.at(-1), 0)).toBe(nw.points.at(-1).netWorth);
  });
});

describe('date ranges', () => {
  // Actual's AQL applies only the first operator of `date: { $gte, $lte }`; these used to leak later dates.
  it('reports and search stop at the end date', async () => {
    expect((await call('GET', '/reports/spending?start=2001-01&end=2001-02', '/reports/spending', viewer)).body.total).toBe(0);
    const last = shift(M, -1);
    const items = (await call('GET', `/transactions?since=${last}-01&until=${last}-15`, '/transactions', viewer)).body.items as any[];
    expect(items.length).toBeGreaterThan(0);
    expect(items.every((t) => t.date >= `${last}-01` && t.date <= `${last}-15`)).toBe(true);
  });
});
