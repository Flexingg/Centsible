/**
 * Household admin on the bridge host. Bootstraps the first owner; after that, owners
 * can manage the household from the app.
 *
 *   node dist/admin/cli.js add-member --name Jo --role owner --pair
 *   node dist/admin/cli.js pair --name Sam
 *   node dist/admin/cli.js grant --name Sam --budgets <syncId>,<syncId>
 *   node dist/admin/cli.js list
 *   node dist/admin/cli.js revoke-device <deviceId>
 */
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import QRCode from 'qrcode';
import { pairingUri } from '../auth/pairing.js';
import { HouseholdStore, type Role } from '../auth/store.js';

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    name: { type: 'string' },
    role: { type: 'string', default: 'member' },
    budgets: { type: 'string' },
    pair: { type: 'boolean', default: false },
  },
});

const publicUrl = process.env.BRIDGE_PUBLIC_URL;
const cfAccess =
  process.env.CF_ACCESS_CLIENT_ID && process.env.CF_ACCESS_CLIENT_SECRET
    ? { clientId: process.env.CF_ACCESS_CLIENT_ID, clientSecret: process.env.CF_ACCESS_CLIENT_SECRET }
    : undefined;
const store = new HouseholdStore(join(resolve(process.env.BRIDGE_DATA_DIR ?? './data'), 'bridge.sqlite'));

function fail(msg: string): never {
  console.error(msg);
  process.exit(1);
}

function memberByName() {
  if (!values.name) fail('--name is required');
  return store.findMemberByName(values.name) ?? fail(`No member named "${values.name}"`);
}

async function printPairing(memberId: string, name: string) {
  if (!publicUrl) fail('BRIDGE_PUBLIC_URL must be set to generate a pairing QR code');
  const { code, expiresAt } = store.createPairingCode(memberId, null);
  const uri = pairingUri({ publicUrl, cfAccess }, code);
  console.log(await QRCode.toString(uri, { type: 'terminal', small: true }));
  console.log(`Pairing code for ${name}: ${code}  (single use, expires ${expiresAt})`);
  console.log('Scan the QR code in the app, or enter the bridge URL and code manually.');
}

const [command, arg] = positionals;
switch (command) {
  case 'add-member': {
    if (!values.name) fail('--name is required');
    const role = values.role as Role;
    if (!['owner', 'member', 'viewer'].includes(role)) fail('--role must be owner, member or viewer');
    if (store.findMemberByName(values.name)) fail(`A member named "${values.name}" already exists`);
    const budgetIds = values.budgets?.split(',').filter(Boolean) ?? [];
    const m = store.createMember({ displayName: values.name, role, budgetIds });
    console.log(`Created ${m.role} "${m.displayName}" (${m.id})`);
    if (values.pair) await printPairing(m.id, m.displayName);
    break;
  }
  case 'pair': {
    const m = memberByName();
    await printPairing(m.id, m.displayName);
    break;
  }
  case 'grant': {
    const m = memberByName();
    const ids = values.budgets?.split(',').filter(Boolean) ?? fail('--budgets is required');
    store.setMemberBudgets(m.id, ids);
    console.log(`${m.displayName} can now open: ${ids.join(', ') || '(nothing)'}`);
    break;
  }
  case 'list': {
    for (const m of store.listMembers()) {
      console.log(`${m.displayName}  [${m.role}]  ${m.id}${m.disabled ? '  (disabled)' : ''}`);
      console.log(`  budgets: ${m.role === 'owner' ? 'all' : m.budgetIds.join(', ') || '(none)'}`);
      for (const d of store.listDevices(m.id)) {
        console.log(`  device ${d.id}  ${d.name} (${d.platform})  last seen ${d.lastSeenAt ?? 'never'}`);
      }
    }
    break;
  }
  case 'revoke-device': {
    if (!arg) fail('usage: revoke-device <deviceId>');
    console.log(store.revokeDevice(arg) ? 'Revoked' : 'No such active device');
    break;
  }
  default:
    fail('commands: add-member, pair, grant, list, revoke-device');
}
store.close();
