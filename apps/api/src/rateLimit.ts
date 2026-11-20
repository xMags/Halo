/**
 * A fixed-window request budget per key (a user id), in memory. It protects
 * shared upstream credentials, such as the TMDB token, from one account
 * spending them for everyone. Single-process: each server keeps its own.
 *
 * `take` checks and counts in one synchronous step, so two requests arriving
 * together cannot both see the last free slot.
 */
export class WindowRateLimiter {
  private readonly windows = new Map<string, { count: number; windowStart: number }>()

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly maxTracked = 10_000,
    private readonly now: () => number = Date.now,
  ) {}

  /** Counts one request for `key`: null when allowed, else the seconds until the window resets. */
  take(key: string): number | null {
    const now = this.now()
    let entry = this.windows.get(key)
    if (entry && now - entry.windowStart >= this.windowMs) {
      this.windows.delete(key)
      entry = undefined
    }
    if (!entry) {
      this.makeRoom(now)
      entry = { count: 0, windowStart: now }
      this.windows.set(key, entry)
    }
    if (entry.count >= this.limit) return Math.max(1, Math.ceil((entry.windowStart + this.windowMs - now) / 1000))
    entry.count += 1
    return null
  }

  /** Keeps the map within `maxTracked` before a new key is added: expired windows first, then the oldest. */
  private makeRoom(now: number): void {
    if (this.windows.size < this.maxTracked) return
    for (const [key, entry] of this.windows) {
      if (now - entry.windowStart >= this.windowMs) this.windows.delete(key)
    }
    // A Map iterates in insertion order, so these are the oldest windows.
    for (const key of this.windows.keys()) {
      if (this.windows.size < this.maxTracked) break
      this.windows.delete(key)
    }
  }
}
