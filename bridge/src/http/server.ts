import Fastify, { type FastifyError, type FastifyInstance, type FastifyRequest } from 'fastify';
import type { ActualHost } from '../actual/host.js';
import type { BudgetOps } from '../actual/budget-ops.js';
import type { HouseholdStore, Member, Device, Role } from '../auth/store.js';
import type { BridgeConfig } from '../config.js';
import { ApiError } from '../errors.js';
import { authRoutes } from './routes/auth.js';
import { budgetRoutes } from './routes/budgets.js';
import { householdRoutes } from './routes/household.js';
import { systemRoutes } from './routes/system.js';

export type Deps = {
  config: BridgeConfig;
  store: HouseholdStore;
  host: ActualHost;
  ops: BudgetOps;
};

declare module 'fastify' {
  interface FastifyRequest {
    auth: { member: Member; device: Device } | null;
  }
}

const PUBLIC_ROUTES = new Set(['/v1/health', '/v1/auth/pair', '/v1/auth/refresh']);

export async function buildServer(deps: Deps, opts: { logger?: boolean | object } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger: opts.logger ?? { level: deps.config.logLevel },
    trustProxy: deps.config.trustProxy,
    ajv: { customOptions: { removeAdditional: false, coerceTypes: 'array' } },
  });

  app.decorateRequest('auth', null);

  app.addHook('onRequest', async (req, reply) => {
    reply.header('Cache-Control', 'no-store');
    const path = req.routeOptions.url ?? req.url;
    if (PUBLIC_ROUTES.has(path)) return;
    const header = req.headers.authorization;
    const bearer = header?.startsWith('Bearer ') ? header.slice(7) : null;
    const ctx = bearer ? deps.store.authenticate(bearer) : null;
    if (!ctx) throw ApiError.unauthorized('Missing or expired access token');
    req.auth = ctx;
  });

  app.setErrorHandler((err: FastifyError | ApiError, req, reply) => {
    const e =
      err instanceof ApiError
        ? err
        : err.validation
          ? ApiError.validation(err.message)
          : err.statusCode && err.statusCode < 500
            ? new ApiError(err.statusCode, 'validation', 'Invalid request', err.message)
            : null;
    if (!e) {
      req.log.error({ err }, 'unhandled error');
      return reply
        .status(500)
        .type('application/problem+json')
        .send({ type: 'about:blank', title: 'Internal error', status: 500, code: 'internal' });
    }
    return reply
      .status(e.status)
      .type('application/problem+json')
      .send({ type: 'about:blank', title: e.message, status: e.status, code: e.code, detail: e.detail });
  });

  app.setNotFoundHandler((req, reply) =>
    reply
      .status(404)
      .type('application/problem+json')
      .send({ type: 'about:blank', title: 'Not found', status: 404, code: 'not_found', detail: `${req.method} ${req.url}` }),
  );

  await app.register(systemRoutes(deps));
  await app.register(authRoutes(deps));
  await app.register(householdRoutes(deps));
  await app.register(budgetRoutes(deps));
  return app;
}

const RANK: Record<Role, number> = { viewer: 0, member: 1, owner: 2 };

/** Throws unless the caller has at least `role`. */
export function requireRole(req: FastifyRequest, role: Role) {
  const auth = req.auth;
  if (!auth) throw ApiError.unauthorized();
  if (RANK[auth.member.role] < RANK[role]) throw ApiError.forbidden(`Requires the ${role} role`);
  return auth;
}

/** Throws unless the caller may open `budgetId`. */
export function requireBudget(deps: Deps, req: FastifyRequest, budgetId: string, role: Role = 'viewer') {
  const auth = requireRole(req, role);
  if (!deps.store.canAccessBudget(auth.member, budgetId)) throw ApiError.forbidden('No access to this budget');
  return auth;
}
