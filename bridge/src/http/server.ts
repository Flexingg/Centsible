import Fastify, { type FastifyError, type FastifyInstance, type FastifyRequest } from 'fastify';
import type { ActualHost } from '../actual/host.js';
import type { BudgetOps } from '../actual/budget-ops.js';
import type { StructureOps } from '../actual/structure-ops.js';
import type { TransactionOps } from '../actual/transaction-ops.js';
import type { AccountOps } from '../actual/account-ops.js';
import type { PlanningOps } from '../actual/planning-ops.js';
import type { ReportOps } from '../actual/report-ops.js';
import type { JobStore } from '../jobs.js';
import type { SetupService } from '../setup.js';
import type { BankSyncOps } from '../actual/bank-sync-ops.js';
import type { BankSyncScheduler } from '../bank-sync-scheduler.js';
import type { HouseholdStore, Member, Device, Role } from '../auth/store.js';
import type { BridgeConfig } from '../config.js';
import { ApiError } from '../errors.js';
import { authRoutes } from './routes/auth.js';
import { budgetRoutes } from './routes/budgets.js';
import { householdRoutes } from './routes/household.js';
import { structureRoutes } from './routes/structure.js';
import { transactionRoutes } from './routes/transactions.js';
import { planningRoutes } from './routes/planning.js';
import { operationRoutes } from './routes/operations.js';
import { systemRoutes } from './routes/system.js';
import { setupRoutes } from './routes/setup.js';
import { bankSyncRoutes } from './routes/bank-sync.js';

export type Deps = {
  config: BridgeConfig;
  store: HouseholdStore;
  host: ActualHost;
  ops: BudgetOps;
  transactions: TransactionOps;
  structure: StructureOps;
  planning: PlanningOps;
  accountOps: AccountOps;
  reports: ReportOps;
  jobs: JobStore;
  setup: SetupService;
  bankSync: BankSyncOps;
  scheduler: BankSyncScheduler;
};

declare module 'fastify' {
  interface FastifyRequest {
    auth: { member: Member; device: Device } | null;
  }
}

const PUBLIC_ROUTES = new Set(['/v1/health', '/v1/auth/pair', '/v1/auth/refresh', '/v1/setup', '/v1/setup/claim']);

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
  await app.register(setupRoutes(deps));
  await app.register(authRoutes(deps));
  await app.register(householdRoutes(deps));
  await app.register(budgetRoutes(deps));
  await app.register(transactionRoutes(deps));
  await app.register(structureRoutes(deps));
  await app.register(planningRoutes(deps));
  await app.register(operationRoutes(deps));
  await app.register(bankSyncRoutes(deps));
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

/** Records who did what from which device (Actual itself doesn't track this). */
export function audit(deps: Deps, req: FastifyRequest, budgetId: string, action: string, target?: string, detail?: unknown) {
  const { member, device } = req.auth!;
  deps.store.audit({ memberId: member.id, deviceId: device.id, budgetId, action, target, detail });
}
