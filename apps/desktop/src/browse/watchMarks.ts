import type { MetaVideo, WatchState } from '@halo/core'

/** The video a mark is about, with the display fields its row carries. */
export interface WatchMarkTarget {
  videoId: string
  itemId: string
  /** The show's name for an episode, the title for a film: what the continue shelf prints. */
  name: string
  poster?: string
}

/**
 * The row "Mark as watched" writes. A measured duration is kept; otherwise the
 * addon's runtime stands in, and with neither the duration stays 0, which
 * every reader (the continue shelf, Detail, the sources sheet, the native
 * app) takes as "length unknown". It is never invented: the sources sheet
 * reads a row's duration as the video's real length for its bitrate estimate.
 */
export function watchedRow(
  target: WatchMarkTarget,
  existing: WatchState | undefined,
  runtimeSec: number | null,
  now: number,
): WatchState {
  const durationSec = existing && existing.durationSec > 0 ? existing.durationSec : (runtimeSec ?? 0)
  return {
    ...rowBase(target, existing, now),
    positionSec: durationSec,
    durationSec,
    watched: true,
  }
}

/**
 * The row "Mark as unwatched" writes. Rows cannot be deleted (sync is
 * last-write-wins and the server keeps every row), so this is the row that
 * reads as "never started" everywhere: no position and no length, which the
 * continue shelf and resume logic in both desktop clients skip, the same as a
 * missing row.
 */
export function unwatchedRow(target: WatchMarkTarget, existing: WatchState | undefined, now: number): WatchState {
  return {
    ...rowBase(target, existing, now),
    positionSec: 0,
    durationSec: 0,
    watched: false,
  }
}

/**
 * The episodes before `video`, first to last: every numbered episode of an
 * earlier season and the earlier ones of its own. Specials (season 0) are not
 * part of that run, so they only count before another special. Addons without
 * seasons (kitsu) put every episode in season 0, where this is simply the
 * episodes numbered lower.
 */
export function earlierEpisodes(videos: readonly MetaVideo[], video: MetaVideo): MetaVideo[] {
  const season = video.season ?? 0
  const episode = video.episode
  if (episode == null) return []
  return videos
    .filter((other) => {
      if (other.id === video.id || other.episode == null) return false
      const otherSeason = other.season ?? 0
      if (season === 0) return otherSeason === 0 && other.episode < episode
      if (otherSeason === 0) return false
      return otherSeason < season || (otherSeason === season && other.episode < episode)
    })
    .sort((a, b) => (a.season ?? 0) - (b.season ?? 0) || (a.episode ?? 0) - (b.episode ?? 0))
}

/**
 * Rows marking several videos watched at once, given first to last. Each is
 * stamped strictly later than the one before it, so the continue shelf, which
 * follows a show's newest row, lands after the last of them rather than on
 * whichever the server happens to list first among equal timestamps.
 */
export function watchedRowsInOrder(
  targets: readonly WatchMarkTarget[],
  existing: ReadonlyMap<string, WatchState>,
  runtimeSec: number | null,
  now: number,
): WatchState[] {
  let stamp = now
  return targets.map((target) => {
    const row = watchedRow(target, existing.get(target.videoId), runtimeSec, stamp)
    stamp = row.updatedAt + 1
    return row
  })
}

/** Whether a row records any viewing at all, as opposed to an unwatched mark. */
export function hasWatchProgress(state: Pick<WatchState, 'watched' | 'positionSec'>): boolean {
  return state.watched || state.positionSec > 0
}

function rowBase(
  target: WatchMarkTarget,
  existing: WatchState | undefined,
  now: number,
): Pick<WatchState, 'videoId' | 'itemId' | 'name' | 'poster' | 'updatedAt'> {
  // The server rejects an empty name, and a newer row must not lose the
  // display fields an older one carried.
  const name = target.name || existing?.name
  const poster = target.poster ?? existing?.poster
  return {
    videoId: target.videoId,
    itemId: target.itemId,
    ...(name ? { name } : {}),
    ...(poster ? { poster } : {}),
    // Strictly newer than the row it replaces, or the server keeps the old one.
    updatedAt: Math.max(now, (existing?.updatedAt ?? 0) + 1),
  }
}
