// Seeds a realistic household budget on a running actual-server. Runs in its own
// process because @actual-app/api is a singleton and the bridge under test owns one.
// Usage: node seed-budget.mjs <serverUrl> <password> <dataDir> <month YYYY-MM>
// Prints {"budgetId": "...", ...ids} as the last line of stdout.
import * as api from '@actual-app/api';

const [serverURL, password, dataDir, month] = process.argv.slice(2);
const out = console.log.bind(console);
console.log = () => {};
console.info = () => {};

const lib = await api.init({ serverURL, password, dataDir });
const created = await lib.send('create-budget', { budgetName: 'Household' });
if (created?.error) throw new Error(`create-budget failed: ${created.error}`);

const checking = await api.createAccount({ name: 'Joint Checking', offbudget: false }, 520000);
const card = await api.createAccount({ name: 'Visa', offbudget: false }, 0);
await api.createAccount({ name: 'Brokerage', offbudget: true }, 1250000);

const groups = await api.getCategoryGroups();
const cats = Object.fromEntries(groups.flatMap((g) => g.categories).map((c) => [c.name, c.id]));
const d = (day) => `${month}-${String(day).padStart(2, '0')}`;

await api.addTransactions(checking, [
  { date: d(1), amount: 610000, payee_name: 'Employer Payroll', category: cats['Income'] },
  { date: d(2), amount: -185000, payee_name: 'Oak Street Apartments', category: cats['Bills'] },
  {
    date: d(5),
    amount: -12834,
    payee_name: 'Costco',
    subtransactions: [
      { amount: -9834, category: cats['Food'] },
      { amount: -3000, category: cats['General'], notes: 'batteries' },
    ],
  },
]);
await api.addTransactions(card, [
  { date: d(3), amount: -4523, payee_name: "Trader Joe's", category: cats['Food'], notes: 'weekly shop #groceries' },
  { date: d(6), amount: -1650, payee_name: 'Blue Bottle', category: cats['General'] },
]);

await api.setBudgetAmount(month, cats['Food'], 60000);
await api.setBudgetAmount(month, cats['Bills'], 185000);
await api.setBudgetAmount(month, cats['General'], 20000);
await api.setBudgetCarryover(month, cats['Food'], true);
await api.sync();

const budgetId = (await api.getBudgets()).find((b) => b.name === 'Household' && b.groupId)?.groupId;
await api.shutdown();
out(JSON.stringify({ budgetId, accounts: { checking, card }, categories: cats }));
