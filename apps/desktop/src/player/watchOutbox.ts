import type { WatchState } from '@halo/core'

/**
 * Watch progress that has not reached the backend yet, kept on this PC.
 *
 * The player stages its position here every couple of seconds and before every
 * report, and a report sends everything staged and removes what was accepted.
 * So progress survives the two ways it used to be lost: the window closing
 * mid-playback (nothing runs at that point, but the last staged position is
 * already on disk and goes out at the next launch), and a report that fails,
 * such as every report while a download plays offline.
 *
 * Rows keep the `updatedAt` of the moment they were watched, so a row sent
 * late still loses to anything newer the account already has.
 *
 * The outbox belongs to one backend (an account, or this PC): it is cleared
 * whenever the backend changes, as the settings mirror is.
 */

const OUTBOX_KEY = 'halo.watchOutbox.v1'
/** Far more videos than one sitting leaves unsent; a bound against a store that only ever grows. */
const MAX_PENDING = 200

/** `incoming` over `pending`, one row per video: the newer `updatedAt` wins, a tie keeps what was there. */
export function mergePending(pending: readonly WatchState[], incoming: readonly WatchState[]): WatchState[] {
  const byVideo = new Map(pending.map((state) => [state.videoId, state]))
  for (const state of incoming) {
    const existing = byVideo.get(state.videoId)
    if (!existing || state.updatedAt > existing.updatedAt) byVideo.set(state.videoId, state)
  }
  // Newest last; when over the bound, the oldest rows are the ones dropped.
  return [...byVideo.values()].sort((a, b) => a.updatedAt - b.updatedAt).slice(-MAX_PENDING)
}

/** `pending` without the rows `sent` covered. A row staged again since, with a newer time, stays. */
export function withoutSettled(pending: readonly WatchState[], sent: readonly WatchState[]): WatchState[] {
  const sentAt = new Map(sent.map((state) => [state.videoId, state.updatedAt]))
  return pending.filter((state) => {
    const at = sentAt.get(state.videoId)
    return at === undefined || state.updatedAt > at
  })
}

function isStagedRow(value: unknown): value is WatchState {
  if (typeof value !== 'object' || value === null) return false
  const row = value as Partial<WatchState>
  return typeof row.videoId === 'string' && typeof row.itemId === 'string' && typeof row.updatedAt === 'number'
}

function read(): WatchState[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(OUTBOX_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter(isStagedRow) : []
  } catch {
    // Unreadable or unavailable storage: nothing staged, and reports still go out directly.
    return []
  }
}

function write(pending: readonly WatchState[]): void {
  try {
    if (pending.length === 0) localStorage.removeItem(OUTBOX_KEY)
    else localStorage.setItem(OUTBOX_KEY, JSON.stringify(pending))
  } catch {
    // Storage full or blocked: this report is then only as durable as its request.
  }
}

export function stageWatchStates(states: readonly WatchState[]): void {
  if (states.length === 0) return
  write(mergePending(read(), states))
}

export function pendingWatchStates(): WatchState[] {
  return read()
}

/** Call once the backend has accepted `sent`. */
export function settleWatchStates(sent: readonly WatchState[]): void {
  write(withoutSettled(read(), sent))
}

export function clearWatchOutbox(): void {
  write([])
}
