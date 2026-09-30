import { mkdirSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { describe, expect, it } from 'vitest';
import { SimpleFinKeyFile } from '../../src/actual/simplefin-client.js';

const ACCESS = 'https://user:secret@beta-bridge.simplefin.org/simplefin';

function actualData(secrets: Record<string, string>) {
  const dir = mkdtempSync(join(tmpdir(), 'actual-data-'));
  mkdirSync(join(dir, 'server-files'));
  const db = new Database(join(dir, 'server-files', 'account.sqlite'));
  db.exec('CREATE TABLE secrets (name TEXT PRIMARY KEY, value BLOB)');
  for (const [k, v] of Object.entries(secrets)) db.prepare('INSERT INTO secrets VALUES (?, ?)').run(k, v);
  db.close();
  return dir;
}

describe('SimpleFinKeyFile', () => {
  it('uses the access URL Actual saved when SimpleFIN was connected in Actual', () => {
    const keys = new SimpleFinKeyFile(mkdtempSync(join(tmpdir(), 'bridge-')), actualData({ simplefin_accessKey: ACCESS, simplefin_token: 'abc' }));
    expect(keys.read()).toBe(ACCESS);
  });

  it("takes one saved for a single budget file, and ignores a claim that SimpleFIN refused", () => {
    const keys = new SimpleFinKeyFile(mkdtempSync(join(tmpdir(), 'bridge-')), actualData({ simplefin_accessKey: 'Forbidden', 'simplefin_accessKey:file-1': ACCESS }));
    expect(keys.read()).toBe(ACCESS);
  });

  it("falls back to the bridge's own copy without Actual's folder", () => {
    const keys = new SimpleFinKeyFile(mkdtempSync(join(tmpdir(), 'bridge-')), join(tmpdir(), 'missing'));
    expect(keys.read()).toBeNull();
    keys.write(ACCESS);
    expect(keys.read()).toBe(ACCESS);
  });
});
