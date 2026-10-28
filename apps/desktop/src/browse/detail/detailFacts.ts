import type { MetaDetail, MetaPerson, Stream } from '@halo/core'
import { runtimeMinutes } from '../../format'
import { parseStreamInfo, qualityRank, qualityTier } from '../../sources/streamInfo'

/** One quality the addons offer, as the source picker names it. */
export interface QualityGroup {
  /** The picker's tier label: `4K`, `FULL HD`, `HD`, `SD`, or `OTHER`. */
  label: string
  /** Gold is the picker's mark for the top tier, and only for it. */
  gold: boolean
  count: number
  /** Best-first ordering; lower is better. */
  rank: number
}

export interface Availability {
  groups: QualityGroup[]
  total: number
  subtitleLanguages: number
}

const UNKNOWN = 'OTHER'

/**
 * What the addons offer for one video, grouped the way the picker shows it.
 * Tiers that share a label (480p and SD both read `SD`) count together, at
 * the better of their ranks.
 */
export function summarizeAvailability(streams: readonly Stream[]): Availability {
  const byLabel = new Map<string, QualityGroup>()
  const languages = new Set<string>()
  for (const stream of streams) {
    const { quality } = parseStreamInfo(stream)
    const label = quality ? qualityTier(quality) : UNKNOWN
    const rank = qualityRank(quality)
    const group = byLabel.get(label)
    if (group) {
      group.count += 1
      group.rank = Math.min(group.rank, rank)
    } else {
      byLabel.set(label, { label, gold: quality === '2160p', count: 1, rank })
    }
    for (const sub of stream.subtitles ?? []) languages.add(sub.lang)
  }
  const groups = [...byLabel.values()].sort((a, b) => a.rank - b.rank)
  return { groups, total: streams.length, subtitleLanguages: languages.size }
}

/** `2 h 30 min` from an addon's `150 min`; the addon's own text when it is not minutes. */
export function formatRuntime(runtime: string | undefined): string | null {
  if (!runtime) return null
  const minutes = runtimeMinutes(runtime)
  if (minutes === null || minutes <= 0) return runtime
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (hours === 0) return `${rest} min`
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/** A person on the cast row: the part they play, or their crew role. */
export interface Credit {
  name: string
  role: string | null
  photo: string | null
}

/**
 * The cast row: the addon's pictured cast when it sends one (TMDB), else the
 * plain names every addon has; the director(s) close the row either way.
 * A name appears once, at its first credit.
 */
export function creditsOf(meta: Pick<MetaDetail, 'cast' | 'director' | 'app_extras'>): Credit[] {
  const pictured: MetaPerson[] = meta.app_extras?.cast ?? []
  const cast: Credit[] =
    pictured.length > 0
      ? pictured.map((person) => ({
          name: person.name,
          role: person.character || null,
          photo: person.photo || null,
        }))
      : (meta.cast ?? []).map((name) => ({ name, role: null, photo: null }))
  const crew: Credit[] = (meta.director ?? []).map((name) => ({ name, role: 'Director', photo: null }))
  const seen = new Set<string>()
  return [...cast, ...crew].filter((credit) => {
    const key = credit.name.trim().toLowerCase()
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/**
 * Where a cast card leads: IMDb's people search for the name. Addons send no
 * person id, so a search is the honest link; IMDb because its ratings are the
 * ones the page shows.
 */
export function personSearchUrl(name: string): string {
  return `https://www.imdb.com/find/?q=${encodeURIComponent(name.trim())}&s=nm`
}

/** A YouTube video id as YouTube issues them; anything else is not linked to. */
const YOUTUBE_ID = /^[A-Za-z0-9_-]{6,20}$/

/**
 * The title's trailer on YouTube, from the addon's trailer streams or its
 * older trailer list; null when it sends none. The id comes from an addon, so
 * it is checked before it becomes a URL the browser opens.
 */
export function trailerUrl(meta: Pick<MetaDetail, 'trailerStreams' | 'trailers'>): string | null {
  const candidates = [
    ...(meta.trailerStreams ?? []).map((trailer) => trailer.ytId),
    ...(meta.trailers ?? [])
      .filter((trailer) => !trailer.type || trailer.type.toLowerCase() === 'trailer')
      .map((trailer) => trailer.source),
  ]
  const id = candidates.find((candidate): candidate is string => Boolean(candidate && YOUTUBE_ID.test(candidate)))
  return id ? `https://www.youtube.com/watch?v=${id}` : null
}

/** `18 July 2025` from an ISO date; null when there is none or it does not parse. */
export function formatReleaseDate(released: string | undefined): string | null {
  if (!released) return null
  const date = new Date(released)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
}

/** The About panel's rows, in reading order; facts the addon does not send are left out. */
export function aboutRows(meta: MetaDetail): Array<[label: string, value: string]> {
  const rows: Array<[string, string | undefined | null]> = [
    ['Director', meta.director?.join(', ')],
    ['Writers', meta.writer?.join(', ')],
    ['Released', formatReleaseDate(meta.released)],
    ['Country', meta.country],
    ['Language', meta.language],
    ['Awards', meta.awards],
  ]
  return rows.filter((row): row is [string, string] => Boolean(row[1]?.trim()))
}
