// Creates a second, end-to-end encrypted budget on a running actual-server, the way
// Actual's "Enable encryption" setting does (the internal `key-make` handler).
// Usage: node seed-encrypted.mjs <serverUrl> <password> <dataDir> <encryptionPassword>
// Prints {"budgetId": "..."} as the last line of stdout.
import * as api from '@actual-app/api';

const [serverURL, password, dataDir, encryptionPassword] = process.argv.slice(2);
const out = console.log.bind(console);
console.log = () => {};
console.info = () => {};

const lib = await api.init({ serverURL, password, dataDir });
const created = await lib.send('create-budget', { budgetName: 'Private' });
if (created?.error) throw new Error(`create-budget failed: ${created.error}`);
await api.createAccount({ name: 'Savings', offbudget: false }, 90000);
await api.sync();

const made = await lib.send('key-make', { password: encryptionPassword });
if (made?.error) throw new Error(`key-make failed: ${JSON.stringify(made.error)}`);

const file = (await api.getBudgets()).find((b) => b.name === 'Private' && b.groupId);
await api.shutdown();
out(JSON.stringify({ budgetId: file?.groupId, encryptKeyId: file?.encryptKeyId ?? null }));
