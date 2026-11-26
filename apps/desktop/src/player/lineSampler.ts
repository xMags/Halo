/**
 * Measures the line while a network stream plays, from what mpv reports. The
 * player feeds it mpv's `cache-speed` (bytes per second), whether the cache is
 * idle (`demuxer-cache-idle`), stalls (`paused-for-cache`), seeks and the
 * start of playback, and calls `tick` once a second. No timers or I/O here.
 *
 * Only seconds in which the cache was actually filling count: once the cache
 * holds what it wants, the download pauses and the speed says nothing about
 * the line. The reading is the best average over a few consecutive filling
 * seconds, so a single burst is not taken for the line's speed.
 */

/** One-second samples averaged into one reading. */
export const LINE_SAMPLE_WINDOW = 5

/** A stall this soon after a seek is the seek's own refill, not a slow line. */
export const SEEK_STALL_GRACE_MS = 3_000

export interface LineSession {
  /** The best averaged reading of this session in megabits per second; 0 when none was taken. */
  peakMbps: number
  /** Playback stopped to buffer after it had started, and not because of a seek. */
  stalled: boolean
}

export class PlaybackLineSampler {
  private speedBytesPerSecond = 0
  /** Until mpv says otherwise there is no filling cache to measure. */
  private cacheIdle = true
  private started = false
  private lastSeekAtMs: number | null = null
  private window: number[] = []
  private peakMbps = 0
  private stalled = false

  /** mpv's `cache-speed`; anything but a non-negative finite number counts as 0. */
  cacheSpeed(bytesPerSecond: number | null): void {
    this.speedBytesPerSecond =
      bytesPerSecond !== null && Number.isFinite(bytesPerSecond) && bytesPerSecond > 0 ? bytesPerSecond : 0
  }

  /** mpv's `demuxer-cache-idle`; an idle cache breaks the run of samples. */
  cacheIdleChanged(idle: boolean): void {
    this.cacheIdle = idle
    if (idle) this.window = []
  }

  /** The first frame has played (mpv's first `playback-restart`). */
  playbackStarted(): void {
    this.started = true
  }

  seeked(nowMs: number): void {
    this.lastSeekAtMs = nowMs
  }

  /** mpv's `paused-for-cache`. */
  pausedForCache(paused: boolean, nowMs: number): void {
    if (!paused || !this.started) return
    if (this.lastSeekAtMs !== null && nowMs - this.lastSeekAtMs < SEEK_STALL_GRACE_MS) return
    this.stalled = true
  }

  /** One second has passed: takes a sample when the cache is filling. */
  tick(): void {
    if (this.cacheIdle) {
      this.window = []
      return
    }
    this.window.push((this.speedBytesPerSecond * 8) / 1_000_000)
    if (this.window.length > LINE_SAMPLE_WINDOW) this.window.shift()
    if (this.window.length < LINE_SAMPLE_WINDOW) return
    const average = this.window.reduce((total, sample) => total + sample, 0) / LINE_SAMPLE_WINDOW
    if (average > this.peakMbps) this.peakMbps = average
  }

  session(): LineSession {
    return { peakMbps: this.peakMbps, stalled: this.stalled }
  }
}
