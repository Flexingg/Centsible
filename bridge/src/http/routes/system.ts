import type { FastifyPluginAsync } from 'fastify';
import { capabilities } from '../../actual/capabilities.js';
import { BRIDGE_VERSION } from '../../actual/versions.js';
import type { Deps } from '../server.js';

export const systemRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    app.get('/v1/health', async () => ({ status: 'ok', version: BRIDGE_VERSION }));

    app.get('/v1/capabilities', async () => capabilities(deps.host));
  };
