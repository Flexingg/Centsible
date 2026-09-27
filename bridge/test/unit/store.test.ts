import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HouseholdStore } from '../../src/auth/store.js';

let now = 1_700_000_000_000;
let store: HouseholdStore;

beforeEach(() => {
  now = 1_700_000_000_000;
  store = new HouseholdStore(':memory:', { accessSec: 60, refreshSec: 3600 }, () => now);
});
afterEach(() => store.close());

describe('pairing', () => {
  it('redeems a code once and issues working tokens', () => {
    const m = store.createMember({ displayName: 'Jo', role: 'owner' });
    const { code } = store.createPairingCode(m.id, null);
    const paired = store.redeemPairingCode(code, { name: 'Pixel', platform: 'android' })!;
    expect(paired.member.id).toBe(m.id);
    expect(store.authenticate(paired.tokens.accessToken)?.device.name).toBe('Pixel');
    expect(store.redeemPairingCode(code, { name: 'Again', platform: 'android' })).toBeNull();
  });

  it('accepts codes without the dash and in lower case', () => {
    const m = store.createMember({ displayName: 'Jo', role: 'owner' });
    const { code } = store.createPairingCode(m.id, null);
    expect(store.redeemPairingCode(code.replace('-', '').toLowerCase(), { name: 'P', platform: 'android' })).not.toBeNull();
  });

  it('rejects expired codes and disabled members', () => {
    const m = store.createMember({ displayName: 'Jo', role: 'owner' });
    const { code } = store.createPairingCode(m.id, null, 60);
    now += 61_000;
    expect(store.redeemPairingCode(code, { name: 'P', platform: 'android' })).toBeNull();

    const { code: code2 } = store.createPairingCode(m.id, null);
    store.db.prepare('UPDATE members SET disabled = 1 WHERE id = ?').run(m.id);
    expect(store.redeemPairingCode(code2, { name: 'P', platform: 'android' })).toBeNull();
  });
});

describe('tokens', () => {
  function pairedDevice() {
    const m = store.createMember({ displayName: 'Jo', role: 'member' });
    const { code } = store.createPairingCode(m.id, null);
    return store.redeemPairingCode(code, { name: 'P', platform: 'android' })!;
  }

  it('expires access tokens', () => {
    const { tokens } = pairedDevice();
    now += 61_000;
    expect(store.authenticate(tokens.accessToken)).toBeNull();
  });

  it('rotates refresh tokens and revokes the device on reuse', () => {
    const { tokens, device } = pairedDevice();
    const next = store.refresh(tokens.refreshToken)!;
    expect(next.tokens.refreshToken).not.toBe(tokens.refreshToken);
    expect(store.authenticate(tokens.accessToken)).toBeNull();
    expect(store.authenticate(next.tokens.accessToken)).not.toBeNull();

    expect(store.refresh(tokens.refreshToken)).toBeNull();
    expect(store.getDevice(device.id)).toBeNull();
    expect(store.authenticate(next.tokens.accessToken)).toBeNull();
  });

  it('revoking a device kills its tokens', () => {
    const { tokens, device } = pairedDevice();
    expect(store.revokeDevice(device.id)).toBe(true);
    expect(store.authenticate(tokens.accessToken)).toBeNull();
    expect(store.refresh(tokens.refreshToken)).toBeNull();
  });
});

describe('budget access', () => {
  it('owners see everything, others only granted budgets', () => {
    const owner = store.createMember({ displayName: 'Jo', role: 'owner' });
    const member = store.createMember({ displayName: 'Sam', role: 'member', budgetIds: ['shared'] });
    expect(store.canAccessBudget(owner, 'personal-jo')).toBe(true);
    expect(store.canAccessBudget(member, 'shared')).toBe(true);
    expect(store.canAccessBudget(member, 'personal-jo')).toBe(false);
    const updated = store.setMemberBudgets(member.id, ['shared', 'kids'])!;
    expect(updated.budgetIds).toEqual(['kids', 'shared']);
  });
});

describe('idempotency', () => {
  it('replays the stored response and flags key reuse on another route', () => {
    store.putIdempotent('m1', 'key-12345', 'transfer:a', 200, { ok: 1 });
    expect(store.getIdempotent('m1', 'key-12345', 'transfer:a')).toEqual({ status: 200, body: { ok: 1 } });
    expect(store.getIdempotent('m1', 'key-12345', 'transfer:b')?.status).toBe(409);
    expect(store.getIdempotent('m2', 'key-12345', 'transfer:a')).toBeNull();
  });
});
