import type { FastifyPluginAsync } from 'fastify';
import { pairingUri } from '../../auth/pairing.js';
import type { Role } from '../../auth/store.js';
import { ApiError } from '../../errors.js';
import { requireRole, type Deps } from '../server.js';

const ROLES = ['owner', 'member', 'viewer'];

export const householdRoutes =
  ({ store, config }: Deps): FastifyPluginAsync =>
  async (app) => {
    app.get('/v1/household/members', async (req) => {
      requireRole(req, 'owner');
      return { items: store.listMembers() };
    });

    app.post<{ Body: { displayName: string; role: Role; budgetIds?: string[] } }>(
      '/v1/household/members',
      {
        schema: {
          body: {
            type: 'object',
            required: ['displayName', 'role'],
            properties: {
              displayName: { type: 'string', minLength: 1, maxLength: 80 },
              role: { type: 'string', enum: ROLES },
              budgetIds: { type: 'array', items: { type: 'string' } },
            },
          },
        },
      },
      async (req, reply) => {
        const auth = requireRole(req, 'owner');
        if (store.findMemberByName(req.body.displayName)) throw new ApiError(409, 'conflict', 'Member already exists');
        const member = store.createMember(req.body);
        store.audit({ memberId: auth.member.id, deviceId: auth.device.id, action: 'household.member_created', target: member.id });
        return reply.status(201).send(member);
      },
    );

    app.post<{ Params: { memberId: string } }>('/v1/household/members/:memberId/pairing-codes', async (req, reply) => {
      const auth = requireRole(req, 'owner');
      const member = store.getMember(req.params.memberId);
      if (!member || member.disabled) throw ApiError.notFound('Member not found');
      const { code, expiresAt } = store.createPairingCode(member.id, auth.member.id);
      store.audit({ memberId: auth.member.id, deviceId: auth.device.id, action: 'household.pairing_code_created', target: member.id });
      return reply.status(201).send({ code, expiresAt, pairingUri: pairingUri(config, code) });
    });

    app.put<{ Params: { memberId: string }; Body: { budgetIds: string[] } }>(
      '/v1/household/members/:memberId/budgets',
      {
        schema: {
          body: {
            type: 'object',
            required: ['budgetIds'],
            properties: { budgetIds: { type: 'array', items: { type: 'string' } } },
          },
        },
      },
      async (req) => {
        const auth = requireRole(req, 'owner');
        const member = store.setMemberBudgets(req.params.memberId, req.body.budgetIds);
        if (!member) throw ApiError.notFound('Member not found');
        store.audit({
          memberId: auth.member.id,
          deviceId: auth.device.id,
          action: 'household.budgets_set',
          target: member.id,
          detail: req.body.budgetIds,
        });
        return member;
      },
    );

    app.delete<{ Params: { deviceId: string } }>('/v1/household/devices/:deviceId', async (req, reply) => {
      const auth = requireRole(req, 'owner');
      if (!store.revokeDevice(req.params.deviceId)) throw ApiError.notFound('Device not found');
      store.audit({ memberId: auth.member.id, deviceId: auth.device.id, action: 'household.device_revoked', target: req.params.deviceId });
      return reply.status(204).send();
    });
  };
