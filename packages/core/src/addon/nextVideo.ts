import type { MetaVideo } from './types'

/** Specials (season 0) sort after every regular season — Stremio's convention. */
function seasonKey(season: number): number {
  return season === 0 ? Number.MAX_SAFE_INTEGER : season
}

/**
 * The episode that follows `currentVideoId` in playback order: seasons
 * ascending with specials (season 0) last, episodes ascending within a season.
 * Entries missing season/episode numbers keep their original array order (the
 * sort is stable and treats them as equal — fine for real metas, which number
 * either every video or none).
 *
 * Returns null when the current video isn't in the list, is the last one, or
 * its follower has a future release date — meta addons list unaired episodes,
 * and "next" must never point at something without streams.
 */
export function nextVideo(videos: MetaVideo[], currentVideoId: string, now = Date.now()): MetaVideo | null {
  const ordered = [...videos].sort((a, b) => {
    if (a.season === undefined || b.season === undefined || a.episode === undefined || b.episode === undefined) return 0
    return seasonKey(a.season) - seasonKey(b.season) || a.episode - b.episode
  })
  const index = ordered.findIndex((v) => v.id === currentVideoId)
  if (index === -1 || index === ordered.length - 1) return null
  const next = ordered[index + 1]!
  if (next.released && new Date(next.released).getTime() > now) return null
  return next
}
