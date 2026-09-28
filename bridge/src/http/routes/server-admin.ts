import type { FastifyPluginAsync } from 'fastify';
import { BACKUP_INTERVALS } from '../../backups.js';
import { requireRole, type Deps } from '../server.js';

type BackupParams = { id: string };

/** Server health, one-tap updates, and backups. Owners only, except reading the status. */
export const serverAdminRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    const { server, backups, store } = deps;
    const audit = (req: Parameters<typeof requireRole>[0], action: string, detail?: unknown) => {
      const { member, device } = req.auth!;
      store.audit({ memberId: member.id, deviceId: device.id, action, detail });
    };

    app.get<{ Querystring: { refresh?: boolean } }>('/v1/server', async (req) => {
      requireRole(req, 'viewer');
      return server.status(req.query.refresh === true);
    });

    app.post('/v1/server/update', async (req, reply) => {
      requireRole(req, 'owner');
      const res = await server.update();
      audit(req, 'server.update_requested');
      return reply.status(202).send(res);
    });

    app.get('/v1/server/backups', async (req) => {
      requireRole(req, 'owner');
      return backups.overview();
    });

    app.post('/v1/server/backups', async (req, reply) => {
      requireRole(req, 'owner');
      const b = await backups.run('manual');
      audit(req, 'backup.created', { id: b.id });
      return reply.status(201).send(b);
    });

    app.put<{ Body: { intervalHours?: number; keep?: number } }>(
      '/v1/server/backups/settings',
      {
        schema: {
          body: {
            type: 'object',
            minProperties: 1,
            additionalProperties: false,
            properties: { intervalHours: { type: 'integer', enum: [...BACKUP_INTERVALS] }, keep: { type: 'integer', minimum: 1, maximum: 60 } },
          },
        },
      },
      async (req) => {
        requireRole(req, 'owner');
        audit(req, 'backup.settings', req.body);
        return backups.setSettings(req.body);
      },
    );

    app.delete<{ Params: BackupParams }>('/v1/server/backups/:id', async (req, reply) => {
      requireRole(req, 'owner');
      await backups.remove(req.params.id);
      audit(req, 'backup.deleted', { id: req.params.id });
      return reply.status(204).send();
    });

    app.get<{ Params: BackupParams & { file: string } }>('/v1/server/backups/:id/files/:file', async (req, reply) => {
      requireRole(req, 'owner');
      const { data, name } = await backups.file(req.params.id, req.params.file);
      audit(req, 'backup.downloaded', { id: req.params.id, file: req.params.file });
      return reply
        .header('content-type', name.endsWith('.zip') ? 'application/zip' : 'application/octet-stream')
        .header('content-disposition', `attachment; filename="${name}"`)
        .send(data);
    });

    app.post<{ Params: BackupParams; Body: { budgetId: string } }>(
      '/v1/server/backups/:id/restore',
      { schema: { body: { type: 'object', required: ['budgetId'], additionalProperties: false, properties: { budgetId: { type: 'string', minLength: 1 } } } } },
      async (req, reply) => {
        requireRole(req, 'owner');
        const res = await backups.restore(req.params.id, req.body.budgetId);
        audit(req, 'backup.restored', { id: req.params.id, from: req.body.budgetId, to: res.budgetId });
        return reply.status(201).send(res);
      },
    );
  };
