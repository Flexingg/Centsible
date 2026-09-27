import { randomUUID } from 'node:crypto';

export type Job = {
  id: string;
  kind: string;
  budgetId: string;
  status: 'running' | 'succeeded' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  result: unknown;
  error: string | null;
};

/**
 * Long operations (bank sync) run as jobs: Cloudflare cuts origin requests at ~100 s,
 * so the phone starts a job (202) and polls it. Kept in memory; a restart forgets
 * finished jobs, which is fine for "is my sync done yet".
 */
export class JobStore {
  private readonly jobs = new Map<string, Job>();

  start(kind: string, budgetId: string, run: () => Promise<unknown>): Job {
    const job: Job = { id: randomUUID(), kind, budgetId, status: 'running', startedAt: new Date().toISOString(), finishedAt: null, result: null, error: null };
    this.jobs.set(job.id, job);
    run().then(
      (result) => Object.assign(job, { status: 'succeeded', result: result ?? null, finishedAt: new Date().toISOString() }),
      (err: unknown) => Object.assign(job, { status: 'failed', error: describe(err), finishedAt: new Date().toISOString() }),
    );
    this.prune();
    return job;
  }

  get(id: string): Job | undefined {
    return this.jobs.get(id);
  }

  /** A running job of this kind for this budget, so double-taps don't queue twice. */
  running(kind: string, budgetId: string): Job | undefined {
    return [...this.jobs.values()].find((j) => j.kind === kind && j.budgetId === budgetId && j.status === 'running');
  }

  private prune() {
    const cutoff = Date.now() - 24 * 3600 * 1000;
    for (const [id, j] of this.jobs) if (j.finishedAt && Date.parse(j.finishedAt) < cutoff) this.jobs.delete(id);
  }
}

/** ApiError keeps the human-readable explanation in `detail`; prefer it. */
function describe(err: unknown): string {
  if (err && typeof err === 'object' && 'detail' in err && typeof (err as { detail?: unknown }).detail === 'string') {
    return (err as { detail: string }).detail;
  }
  return err instanceof Error ? err.message : String(err);
}
