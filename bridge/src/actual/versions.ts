import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);

function readVersion(pkgJsonPath: string): string {
  return JSON.parse(readFileSync(pkgJsonPath, 'utf8')).version;
}

// @actual-app/api does not export its package.json, so locate it from the entry point.
const apiEntry = require.resolve('@actual-app/api'); // <pkg>/dist/index.js
export const ACTUAL_API_VERSION = readVersion(join(dirname(apiEntry), '..', 'package.json'));

const here = dirname(fileURLToPath(import.meta.url)); // src/actual or dist/actual
/** The release this build belongs to (0.2.<CI run>, set in the image), else package.json's version. */
export const BRIDGE_VERSION = process.env.BRIDGE_RELEASE || readVersion(join(here, '..', '..', 'package.json'));
export const CONTRACT_VERSION = '1.0.0';

export type Compatibility = 'ok' | 'api_newer' | 'api_older' | 'unknown';

/** Actual versions are YY.M.patch; the release line (YY.M) is what must match. */
function releaseLine(v: string): [number, number] | null {
  const m = /^v?(\d+)\.(\d+)/.exec(v.trim());
  return m ? [Number(m[1]), Number(m[2])] : null;
}

/**
 * The web client is served by actual-server, so the server version is the version
 * that migrates budget files. An API older than that can hit "out-of-sync-migrations".
 */
export function compareVersions(apiVersion: string, serverVersion: string | null): Compatibility {
  if (!serverVersion) return 'unknown';
  const a = releaseLine(apiVersion);
  const s = releaseLine(serverVersion);
  if (!a || !s) return 'unknown';
  if (a[0] === s[0] && a[1] === s[1]) return 'ok';
  return a[0] > s[0] || (a[0] === s[0] && a[1] > s[1]) ? 'api_newer' : 'api_older';
}
