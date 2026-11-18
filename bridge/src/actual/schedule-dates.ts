import * as api from '@actual-app/api';
import type { ActualHost, Lib } from './host.js';

type Raw = Record<string, unknown>;

/**
 * The next [count] dates a recurring schedule falls on, from [start], by Actual's own
 * recurrence engine (weekend moves included), so they match the web app exactly.
 * Uses the public getScheduleDates (Actual 26.11+), falling back to the internal handler
 * older servers have; when both are missing, just [start].
 */
export async function scheduleDates(host: ActualHost, lib: Lib, date: Raw, start: string, count: number): Promise<string[]> {
  const pub = (api as { getScheduleDates?: (d: unknown, o: { start?: string; count?: number }) => Promise<string[]> }).getScheduleDates;
  if (typeof pub === 'function') {
    try {
      const dates = await pub(date, { start, count });
      if (Array.isArray(dates)) return dates;
    } catch {
      /* fall through to the handler */
    }
  }
  if (host.isFeatureDisabled('schedules.upcoming')) return [start];
  return host.internal<string[]>('schedules.upcoming', lib, 'schedule/get-upcoming-dates', { config: { ...date, start }, count }).catch(() => [start]);
}
