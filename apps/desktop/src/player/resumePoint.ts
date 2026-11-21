import type { WatchState } from '@halo/core'

/**
 * How long the player waits for a fresh read of the watch history before it
 * resumes from what it already holds. Playback must never wait on a server
 * that does not answer.
 */
export const RESUME_READ_TIMEOUT_MS = 2_500

/**
 * The row a video resumes from: the newest by `updatedAt` among the sources
 * given (a fresh read, the cached history, progress this PC has not sent
 * yet). A cached copy can be older than progress made on another device, and
 * resuming from it would then report that old position as the newest one.
 * A newest row that is a removal means the video starts from the beginning.
 */
export function newestWatchState(
  videoId: string,
  sources: readonly (readonly WatchState[] | null | undefined)[],
): WatchState | null {
  let newest: WatchState | null = null
  for (const source of sources) {
    const row = source?.find((state) => state.videoId === videoId)
    if (row && (newest === null || row.updatedAt > newest.updatedAt)) newest = row
  }
  return newest?.removedAt ? null : newest
}

/** `read`'s answer, or null once `timeoutMs` passes or it fails. */
export function withinTimeout<T>(read: Promise<T>, timeoutMs: number = RESUME_READ_TIMEOUT_MS): Promise<T | null> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    read.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      () => {
        clearTimeout(timer)
        resolve(null)
      },
    )
  })
}
