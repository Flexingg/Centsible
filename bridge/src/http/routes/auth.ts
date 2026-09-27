import type { FastifyPluginAsync } from 'fastify';
import { ApiError } from '../../errors.js';
import type { Deps } from '../server.js';

/** Failed pairing attempts per client IP, to make guessing codes impractical. */
class FailureLimiter {
  private readonly failures = new Map<string, number[]>();
  constructor(
    private readonly max = 10,
    private readonly windowMs = 10 * 60_000,
  ) {}
  blocked(key: string) {
    const recent = (this.failures.get(key) ?? []).filter((t) => Date.now() - t < this.windowMs);
    this.failures.set(key, recent);
    return recent.length >= this.max;
  }
  fail(key: string) {
    this.failures.set(key, [...(this.failures.get(key) ?? []), Date.now()]);
  }
}

export const authRoutes =
  ({ store }: Deps): FastifyPluginAsync =>
  async (app) => {
    const pairLimiter = new FailureLimiter();

    app.post<{ Body: { code: string; deviceName: string; platform: string } }>(
      '/v1/auth/pair',
      {
        schema: {
          body: {
            type: 'object',
            required: ['code', 'deviceName', 'platform'],
            properties: {
              code: { type: 'string', minLength: 6, maxLength: 32 },
              deviceName: { type: 'string', minLength: 1, maxLength: 80 },
              platform: { type: 'string', enum: ['android', 'ios', 'web', 'other'] },
            },
          },
        },
      },
      async (req) => {
        if (pairLimiter.blocked(req.ip)) throw new ApiError(429, 'rate_limited', 'Too many attempts', 'Try again in a few minutes');
        const res = store.redeemPairingCode(req.body.code, { name: req.body.deviceName, platform: req.body.platform });
        if (!res) {
          pairLimiter.fail(req.ip);
          store.audit({ action: 'auth.pair_failed', detail: { ip: req.ip } });
          throw ApiError.unauthorized('Pairing code is invalid, used, or expired');
        }
        store.audit({ memberId: res.member.id, deviceId: res.device.id, action: 'auth.paired', detail: { ip: req.ip } });
        return { ...res.tokens, member: res.member, device: res.device };
      },
    );

    app.post<{ Body: { refreshToken: string } }>(
      '/v1/auth/refresh',
      {
        schema: {
          body: { type: 'object', required: ['refreshToken'], properties: { refreshToken: { type: 'string', minLength: 10 } } },
        },
      },
      async (req) => {
        const res = store.refresh(req.body.refreshToken);
        if (!res) throw ApiError.unauthorized('Refresh token is invalid, expired, or was reused');
        return { ...res.tokens, member: res.member, device: res.device };
      },
    );

    app.post('/v1/auth/logout', async (req, reply) => {
      const { member, device } = req.auth!;
      store.revokeDevice(device.id);
      store.audit({ memberId: member.id, deviceId: device.id, action: 'auth.logout' });
      return reply.status(204).send();
    });

    app.get('/v1/me', async (req) => {
      const { member, device } = req.auth!;
      return { member, device, devices: store.listDevices(member.id) };
    });
  };
