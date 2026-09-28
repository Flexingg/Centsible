// Seeds a large, realistic budget: ~12k transactions over 24 months across 5 accounts,
// 60 payees, some splits and transfers, budgets every month. For the benchmark.
// Usage: node seed-large.mjs <serverUrl> <password> <dataDir> <count>
import * as api from '@actual-app/api';

const [serverURL, password, dataDir, countArg] = process.argv.slice(2);
const count = Number(countArg ?? 12000);
const out = console.log.bind(console);
console.log = () => {};
console.info = () => {};

const lib = await api.init({ serverURL, password, dataDir });
await lib.send('create-budget', { budgetName: 'Big Household' });
await api.sync();
const accounts = [];
for (const [name, bal] of [['Joint Checking', 520000], ['Savings', 2500000], ['Visa', 0], ['Amex', 0], ['Cash', 20000]]) {
  accounts.push(await api.createAccount({ name, offbudget: false }, bal));
}
const cats = (await api.getCategoryGroups()).flatMap((g) => g.categories).filter((c) => !c.is_income);
const income = (await api.getCategoryGroups()).flatMap((g) => g.categories).find((c) => c.is_income);
const payees = Array.from({ length: 60 }, (_, i) => `Merchant ${String(i + 1).padStart(2, '0')}${i % 7 === 0 ? ' Coffee' : ''}`);

let seed = 42;
const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const today = new Date();
const start = new Date(today.getFullYear() - 2, today.getMonth(), 1);
const days = Math.floor((today - start) / 86400000);
const iso = (d) => d.toISOString().slice(0, 10);

const byAccount = new Map(accounts.map((a) => [a, []]));
for (let i = 0; i < count; i++) {
  const d = new Date(start.getTime() + Math.floor(rnd() * days) * 86400000);
  const account = accounts[Math.floor(rnd() * 4)];
  const amount = -Math.floor(200 + rnd() * 20000);
  const tx = { date: iso(d), amount, payee_name: payees[Math.floor(rnd() * payees.length)], notes: rnd() < 0.2 ? 'weekly #groceries' : undefined };
  if (rnd() < 0.05) {
    const a = Math.floor(amount / 2);
    tx.subtransactions = [{ amount: a, category: cats[0].id }, { amount: amount - a, category: cats[1].id }];
  } else if (rnd() < 0.97) {
    tx.category = cats[Math.floor(rnd() * cats.length)].id;
  }
  byAccount.get(account).push(tx);
}
// Paychecks twice a month.
for (let m = 0; m < 24; m++) {
  for (const day of [1, 15]) {
    const d = new Date(start.getFullYear(), start.getMonth() + m, day);
    if (d <= today) byAccount.get(accounts[0]).push({ date: iso(d), amount: 310000, payee_name: 'Employer Payroll', category: income?.id });
  }
}
for (const [account, txs] of byAccount) {
  for (let i = 0; i < txs.length; i += 1000) {
    await api.addTransactions(account, txs.slice(i, i + 1000));
    await api.sync(); // one giant sync is over the server's upload limit
  }
}
for (let m = 0; m < 25; m++) {
  const d = new Date(start.getFullYear(), start.getMonth() + m, 1);
  const month = iso(d).slice(0, 7);
  for (const c of cats) await api.setBudgetAmount(month, c.id, 40000 + Math.floor(rnd() * 20000));
  await api.sync();
}
await api.sync();
const budgetId = (await api.getBudgets()).find((b) => b.name === 'Big Household' && b.groupId)?.groupId;
await api.shutdown();
out(JSON.stringify({ budgetId, transactions: count }));
