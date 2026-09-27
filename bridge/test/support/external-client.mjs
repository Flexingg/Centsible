// Simulates another Actual client (e.g. the web app) adding a transaction directly
// through Actual's own sync, to prove the bridge picks up changes it didn't make.
// Usage: node external-client.mjs <serverUrl> <password> <dataDir> <budgetId> <accountId> <json tx>
import * as api from '@actual-app/api';

const [serverURL, password, dataDir, budgetId, accountId, txJson] = process.argv.slice(2);
console.log = () => {};
console.info = () => {};

await api.init({ serverURL, password, dataDir });
await api.downloadBudget(budgetId);
await api.addTransactions(accountId, [JSON.parse(txJson)]);
await api.sync();
await api.shutdown();
