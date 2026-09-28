import type { FastifyPluginAsync } from 'fastify';
import { ApiError } from '../../errors.js';
import { FailureLimiter } from '../limiter.js';
import type { Deps } from '../server.js';

/** First-run setup from the app: see SetupService. Public, and only useful until an owner exists. */
export const setupRoutes =
  ({ setup, store }: Deps): FastifyPluginAsync =>
  async (app) => {
    const limiter = new FailureLimiter();

    app.get('/v1/setup', async () => {
      // Once set up, say nothing more: this route is public.
      if (!setup.needsOwner) return { needsOwner: false };
      return { needsOwner: true, actual: await setup.actualStatus() };
    });

    app.post<{ Body: { setupCode: string; displayName: string; deviceName: string; platform: string; actualPassword?: string } }>(
      '/v1/setup/claim',
      {
        schema: {
          body: {
            type: 'object',
            required: ['setupCode', 'displayName', 'deviceName', 'platform'],
            additionalProperties: false,
            properties: {
              setupCode: { type: 'string', minLength: 4, maxLength: 64 },
              displayName: { type: 'string', minLength: 1, maxLength: 80 },
              deviceName: { type: 'string', minLength: 1, maxLength: 80 },
              platform: { type: 'string', enum: ['android', 'ios', 'web', 'other'] },
              actualPassword: { type: 'string', maxLength: 256 },
            },
          },
        },
      },
      async (req) => {
        if (limiter.blocked(req.ip)) throw new ApiError(429, 'rate_limited', 'Too many attempts', 'Try again in a few minutes');
        if (!setup.needsOwner) throw ApiError.forbidden('This bridge already has an owner. Ask them to invite you.');
        if (!setup.checkCode(req.body.setupCode)) {
          limiter.fail(req.ip);
          store.audit({ action: 'setup.claim_failed', detail: { ip: req.ip } });
          throw ApiError.unauthorized("That setup code doesn't match. Find it in the bridge's logs: docker compose logs bridge");
        }
        const owner = await setup.claim({ displayName: req.body.displayName.trim(), actualPassword: req.body.actualPassword });
        // Sign this phone in exactly the way pairing does.
        const { code } = store.createPairingCode(owner.id, owner.id);
        const res = store.redeemPairingCode(code, { name: req.body.deviceName, platform: req.body.platform })!;
        store.audit({ memberId: owner.id, deviceId: res.device.id, action: 'setup.claimed', detail: { ip: req.ip } });
        return { ...res.tokens, member: res.member, device: res.device };
      },
    );
  };
