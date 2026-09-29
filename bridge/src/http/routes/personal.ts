import type { FastifyPluginAsync } from 'fastify';
import * as api from '@actual-app/api';
import { ApiError } from '../../errors.js';
import { audit, requireBudget, requireRole, type Deps } from '../server.js';

type HomeLayout = { order: string[]; hidden: string[] };
const WIDGET_IDS = { type: 'array', maxItems: 100, items: { type: 'string', minLength: 1, maxLength: 100 } };

/**
 * Things Actual doesn't keep: each person's Home layout (follows them to a new phone),
 * and category colors and emoji, shared by the household so everyone's charts match.
 */
export const personalRoutes =
  (deps: Deps): FastifyPluginAsync =>
  async (app) => {
    app.get('/v1/me/home', async (req) => {
      const { member } = requireRole(req, 'viewer');
      return deps.store.getMemberPref<HomeLayout>(member.id, 'home') ?? { order: [], hidden: [] };
    });

    app.put<{ Body: HomeLayout }>(
      '/v1/me/home',
      { schema: { body: { type: 'object', required: ['order', 'hidden'], additionalProperties: false, properties: { order: WIDGET_IDS, hidden: WIDGET_IDS } } } },
      async (req) => {
        const { member } = requireRole(req, 'viewer');
        const layout = { order: [...new Set(req.body.order)], hidden: [...new Set(req.body.hidden)] };
        deps.store.setMemberPref(member.id, 'home', layout);
        return layout;
      },
    );

    app.get<{ Params: { budgetId: string } }>('/v1/budgets/:budgetId/appearance', async (req) => {
      requireBudget(deps, req, req.params.budgetId);
      return { categories: deps.store.categoryAppearance(req.params.budgetId) };
    });

    app.put<{ Params: { budgetId: string; categoryId: string }; Body: { color: string | null; emoji: string | null } }>(
      '/v1/budgets/:budgetId/categories/:categoryId/appearance',
      {
        schema: {
          body: {
            type: 'object',
            required: ['color', 'emoji'],
            additionalProperties: false,
            properties: {
              color: { type: ['string', 'null'], pattern: '^#[0-9A-Fa-f]{6}$' },
              emoji: { type: ['string', 'null'], minLength: 1, maxLength: 16 },
            },
          },
        },
      },
      async (req) => {
        const { budgetId, categoryId } = req.params;
        requireBudget(deps, req, budgetId, 'member');
        // A category or a category group (the Home dial shows groups).
        const exists = await deps.host.withBudget(budgetId, 'read', async () => {
          if ((await api.getCategories()).some((c) => c.id === categoryId)) return true;
          return ((await api.getCategoryGroups()) as { id: string }[]).some((g) => g.id === categoryId);
        });
        if (!exists) throw ApiError.notFound(`Category ${categoryId} not found`);
        const color = req.body.color?.toUpperCase() ?? null;
        deps.store.setCategoryAppearance(budgetId, categoryId, color, req.body.emoji);
        audit(deps, req, budgetId, 'category.appearance', categoryId, { color, emoji: req.body.emoji });
        return { categoryId, color, emoji: req.body.emoji };
      },
    );
  };
