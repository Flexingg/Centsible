import type { BridgeConfig } from '../config.js';

/**
 * Deep link encoded in the pairing QR code. The Cloudflare Access service token rides
 * along so the phone can reach the bridge through Access on its first request.
 */
export function pairingUri(config: Pick<BridgeConfig, 'publicUrl' | 'cfAccess'>, code: string): string {
  const params = new URLSearchParams({ u: config.publicUrl, c: code });
  if (config.cfAccess) {
    params.set('cfid', config.cfAccess.clientId);
    params.set('cfsecret', config.cfAccess.clientSecret);
  }
  return `actualbridge://pair?${params.toString()}`;
}
