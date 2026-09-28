/** Failed pairing attempts per client IP, to make guessing codes impractical. */
export class FailureLimiter {
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
