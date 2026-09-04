/** Display formatters shared across the shell and every view. */

/** `27:58`, or `1:04:12` once an hour is on the clock. */
export function formatClock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const secs = String(total % 60).padStart(2, '0')
  return hours > 0 ? `${hours}:${minutes}:${secs}` : `${minutes}:${secs}`
}

/** `18 min left` — the remaining-time chip on cards and resume buttons. */
export function formatTimeLeft(positionSec: number, durationSec: number): string {
  const remaining = Math.max(0, durationSec - positionSec)
  if (remaining < 60) return 'under a min left'
  const minutes = Math.round(remaining / 60)
  if (minutes < 60) return `${minutes} min left`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} h left` : `${hours} h ${rest} min left`
}

/** `6.2 GB`. Addons report sizes in bytes; anything smaller than a MB is noise. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  const gb = bytes / 1024 ** 3
  if (gb >= 1) return `${gb.toFixed(gb >= 10 ? 0 : 1)} GB`
  return `${Math.round(bytes / 1024 ** 2)} MB`
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** `just now` / `18 m` / `4 h` / `3 d` — the recent-searches column. */
export function formatRelative(timestamp: number, now = Date.now()): string {
  const elapsed = Math.max(0, now - timestamp)
  if (elapsed < MINUTE) return 'just now'
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)} m`
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)} h`
  if (elapsed < 30 * DAY) return `${Math.floor(elapsed / DAY)} d`
  return `${Math.floor(elapsed / (30 * DAY))} mo`
}

/** `S02E04`, the shared episode tag. Null when the video isn't numbered. */
export function episodeTag(season: number | undefined, episode: number | undefined): string | null {
  if (season == null || episode == null) return null
  return `S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`
}

/**
 * Minutes from an addon's free-text runtime. Cinemeta writes `48 min`, others
 * `2 h 46 min`, `2h46`, or a bare number of minutes. Anything that doesn't
 * parse returns null rather than a guess — the value feeds a bitrate estimate,
 * and a wrong runtime would print a confidently wrong number.
 */
export function runtimeMinutes(runtime: string | undefined): number | null {
  if (!runtime) return null
  const text = runtime.toLowerCase()
  const hours = /(\d+)\s*(?:h|hr|hour)/.exec(text)
  const mins = /(\d+)\s*(?:m|min)/.exec(text)
  if (hours || mins) {
    const total = Number(hours?.[1] ?? 0) * 60 + Number(mins?.[1] ?? 0)
    return total > 0 ? total : null
  }
  const bare = /^\s*(\d{2,3})\s*$/.exec(text)
  return bare ? Number(bare[1]) : null
}

/** Up to two letters for an avatar or addon tile. */
export function initials(name: string): string {
  const words = name.trim().split(/[\s._-]+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0]!.slice(0, 2).toUpperCase()
  return (words[0]![0]! + words[1]![0]!).toUpperCase()
}

/** `FEB 07 2026` — episode air dates, which arrive as ISO strings from addons. */
export function formatAirDate(released: string | undefined): string {
  if (!released) return ''
  const date = new Date(released)
  if (Number.isNaN(date.getTime())) return ''
  const month = date.toLocaleString('en-US', { month: 'short', timeZone: 'UTC' }).toUpperCase()
  return `${month} ${String(date.getUTCDate()).padStart(2, '0')} ${date.getUTCFullYear()}`
}

/** Four-digit year of an ISO date, for a season's mono summary line. */
export function airYear(released: string | undefined): string {
  if (!released) return ''
  const date = new Date(released)
  return Number.isNaN(date.getTime()) ? '' : String(date.getUTCFullYear())
}
