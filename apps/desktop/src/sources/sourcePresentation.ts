/**
 * How the sources sheet talks about a source: a line-for-line port of the
 * native WinUI Halo Desktop's `ViewModels/SourcePresentation.cpp`, so both
 * clients print the same words for the same stream. Pure functions over
 * already-parsed fields; nothing here fetches or reads state.
 *
 * The field vocabulary is the native display source's: `quality` is `2160p`
 * … `SD`, `range` is `SDR`/`DV`/`HDR10`/`HLG`/`HDR`, and anything the parser
 * could not identify is the literal `UNKNOWN`, which the sheet must describe
 * as "not listed" rather than guess at.
 */

import type { Medal, MedalFinish } from '../components/medal'

/** How the sheet talks about picture: coarser than the parsed resolution. */
export const TIER = { ultraHd: 0, fullHd: 1, hd: 2, lower: 3 } as const
export type QualityTier = (typeof TIER)[keyof typeof TIER]

/** The only thing a viewer has to decide between. */
export const SPEED = { immediate: 0, shortWait: 1, needsDownload: 2 } as const
export type StartSpeed = (typeof SPEED)[keyof typeof SPEED]

/**
 * `caching` exists natively but no resolver ever assigns it: addons only say
 * cached, not cached, or nothing. It is kept so the palette stays complete.
 * A file already saved on this device is not a source: the sheet offers it
 * on a card of its own, outside the list.
 */
export type SourceStatus = 'instant' | 'caching' | 'uncached' | 'unknown'

/** The parser's placeholder for anything it could not identify. */
export const NOT_PARSED = 'UNKNOWN'

const SEPARATOR = ' · '

/** A source flattened out of its addon group with every decision already made. */
export interface SourceEntry {
  key: string
  /** Grouping identity; never shown. */
  providerId: string
  /** Sanitized provider name. */
  provider: string
  status: SourceStatus
  quality: string
  range: string
  codec: string
  audio: string
  /** Language tokens joined by ` · `, or `UNKNOWN`. */
  languages: string
  /** `8.42 GB`, or `UNKNOWN`. */
  size: string
  file: string
  subtitleLanguages: string[]
  tier: QualityTier
  speed: StartSpeed
  /** Zero when unknown. */
  sizeBytes: number
  rank: number
  /** Megabits per second the file needs; zero when size or runtime is unknown. */
  neededMbps: number
}

/** What the sheet knows about this device that no single source can say. */
export interface DeviceContext {
  /** Measured line speed in Mbps; zero means never measured. */
  lineMbps: number
  /** Runtime in seconds; zero means unknown. */
  durationSeconds: number
}

export interface DetailChip {
  label: string
  muted: boolean
}

export interface SourceDetails {
  resolution: string
  picture: string
  codec: string
  sound: string
  channels: string
  audioLanguages: DetailChip[]
  subtitles: DetailChip[]
  provider: string
  cacheLabel: string
  cacheGood: boolean
  lineLabel: string
  mbpsLabel: string
  headroom: string
  meterFraction: number
}

interface LanguageTag {
  tag: string
  name: string
}

const LANGUAGE_TAGS: LanguageTag[] = [
  { tag: 'ENG', name: 'English' }, { tag: 'JPN', name: 'Japanese' },
  { tag: 'GER', name: 'German' }, { tag: 'FRE', name: 'French' },
  { tag: 'SPA', name: 'Spanish' }, { tag: 'ITA', name: 'Italian' },
  { tag: 'KOR', name: 'Korean' }, { tag: 'CHI', name: 'Chinese' },
  { tag: 'HIN', name: 'Hindi' }, { tag: 'RUS', name: 'Russian' },
  { tag: 'POR', name: 'Portuguese' }, { tag: 'DUT', name: 'Dutch' },
  { tag: 'NOR', name: 'Norwegian' }, { tag: 'SWE', name: 'Swedish' },
  { tag: 'DAN', name: 'Danish' }, { tag: 'FIN', name: 'Finnish' },
  { tag: 'POL', name: 'Polish' }, { tag: 'TUR', name: 'Turkish' },
  { tag: 'ARA', name: 'Arabic' }, { tag: 'MULTI', name: 'several languages' },
  { tag: 'DUAL', name: 'two languages' },
  // Addons are not consistent about which three-letter spelling they send.
  { tag: 'DEU', name: 'German' }, { tag: 'FRA', name: 'French' },
  { tag: 'ZHO', name: 'Chinese' }, { tag: 'NLD', name: 'Dutch' },
  { tag: 'CES', name: 'Czech' }, { tag: 'CZE', name: 'Czech' },
  { tag: 'ELL', name: 'Greek' }, { tag: 'GRE', name: 'Greek' },
  { tag: 'RON', name: 'Romanian' }, { tag: 'RUM', name: 'Romanian' },
  { tag: 'SLK', name: 'Slovak' }, { tag: 'SLO', name: 'Slovak' },
  { tag: 'EN', name: 'English' }, { tag: 'JA', name: 'Japanese' },
  { tag: 'DE', name: 'German' }, { tag: 'FR', name: 'French' },
  { tag: 'ES', name: 'Spanish' }, { tag: 'IT', name: 'Italian' },
  { tag: 'KO', name: 'Korean' }, { tag: 'ZH', name: 'Chinese' },
  { tag: 'HI', name: 'Hindi' }, { tag: 'RU', name: 'Russian' },
  { tag: 'PT', name: 'Portuguese' }, { tag: 'NL', name: 'Dutch' },
]

function findTag(code: string): LanguageTag | undefined {
  const upper = code.toUpperCase()
  return LANGUAGE_TAGS.find((tag) => tag.tag === upper)
}

/** Splits a ` · ` joined token list, trimming each piece. */
function splitTokens(value: string): string[] {
  return value
    .split('·')
    .map((piece) => piece.trim())
    .filter((piece) => piece.length > 0)
}

/** "English, German and Spanish". */
function joinProse(values: string[]): string {
  return values.reduce(
    (result, value, index) =>
      index === 0 ? value : `${result}${index + 1 === values.length ? ' and ' : ', '}${value}`,
    '',
  )
}

/** Rounded to the nearest whole number, as native `llround` does for positives. */
function whole(value: number): string {
  return String(Math.round(value))
}

function dimensions(tier: QualityTier, quality: string): string | null {
  if (quality === '1440p') return '2560 × 1440'
  if (quality === '480p') return '854 × 480'
  switch (tier) {
    case TIER.ultraHd:
      return '3840 × 2160'
    case TIER.fullHd:
      return '1920 × 1080'
    case TIER.hd:
      return '1280 × 720'
    default:
      return null
  }
}

function codecName(codec: string): string {
  if (!codec || codec === NOT_PARSED) return ''
  if (codec === 'HEVC') return 'H.265'
  if (codec === 'HEVC 10-BIT') return 'H.265 10-bit'
  if (codec === 'AV1 10-BIT') return 'AV1 10-bit'
  if (codec === 'XVID') return 'Xvid'
  return codec
}

function rangeDetail(range: string): string {
  if (range === 'DV') return 'Dolby Vision'
  if (range === 'HLG') return 'HLG'
  if (range === 'HDR10') return 'HDR10'
  return 'HDR'
}

function soundDetail(audio: string): string {
  if (!audio || audio === NOT_PARSED) return 'Not listed'
  const text = audio.toUpperCase()
  if (text === 'ATMOS') return 'Dolby Atmos'
  if (text === 'TRUEHD') return 'Dolby TrueHD'
  if (text === 'DTS-HD') return 'DTS-HD'
  if (text === 'DTS') return 'DTS'
  const space = text.indexOf(' ')
  if (space !== -1) {
    const channels = text.slice(space + 1)
    return channels === '2.0' ? 'Stereo' : `Surround ${channels}`
  }
  return 'Stereo'
}

export function tierOf(quality: string): QualityTier {
  if (quality === '2160p') return TIER.ultraHd
  if (quality === '1440p' || quality === '1080p') return TIER.fullHd
  if (quality === '720p') return TIER.hd
  return TIER.lower
}

export function tierLabel(tier: QualityTier): string {
  switch (tier) {
    case TIER.ultraHd:
      return '4K'
    case TIER.fullHd:
      return 'Full HD'
    case TIER.hd:
      return 'HD'
    default:
      return 'Lower'
  }
}

/**
 * A quality as a medal: the resolution on the plate, the dynamic range or
 * else the tier as its tag, and the tier's metal. The phone's `medalFor`, so
 * both apps stamp a source alike.
 */
export function medalFor(entry: { quality: string; range: string }): Medal {
  const tier = tierOf(entry.quality)
  const plate = entry.quality === '2160p' ? '4K' : entry.quality === NOT_PARSED ? '—' : entry.quality
  const tierTag: Record<QualityTier, string | null> = {
    [TIER.ultraHd]: 'UHD',
    [TIER.fullHd]: 'FHD',
    [TIER.hd]: 'HD',
    [TIER.lower]: null,
  }
  const tag =
    entry.range === 'DV'
      ? 'DV'
      : isHdr(entry.range)
        ? 'HDR'
        : entry.quality === '1440p'
          ? 'QHD'
          : tierTag[tier]
  const finishes: Record<QualityTier, MedalFinish> = {
    [TIER.ultraHd]: 'gold',
    [TIER.fullHd]: 'silver',
    [TIER.hd]: 'bronze',
    [TIER.lower]: 'plain',
  }
  return { plate, tag, finish: finishes[tier] }
}

export function speedOf(status: SourceStatus): StartSpeed {
  if (status === 'instant') return SPEED.immediate
  if (status === 'caching') return SPEED.shortWait
  return SPEED.needsDownload
}

export function statusLabel(status: SourceStatus): string {
  switch (status) {
    case 'instant':
      return 'Plays instantly'
    case 'caching':
      return 'Ready in about a minute'
    case 'uncached':
      return 'Downloads before it plays'
    case 'unknown':
      // Not the same report as "not cached", but the same wait for the viewer.
      return 'May download before it plays'
  }
}

export function isHdr(range: string): boolean {
  return range.length > 0 && range !== 'SDR'
}

export function languageName(code: string): string {
  return findTag(code)?.name ?? code
}

/** `8.42 GB`: binary gigabytes, two decimals below ten and one above. */
export function formatStreamSize(bytes: number | null): string {
  if (bytes == null) return NOT_PARSED
  const gib = bytes / 1024 ** 3
  return `${gib.toFixed(gib >= 10 ? 1 : 2)} GB`
}

export function bitrateWarning(entry: SourceEntry, lineMbps: number): string {
  if (entry.neededMbps <= 0 || lineMbps <= 0) return ''
  if (entry.neededMbps <= lineMbps) return ''
  return `Needs ${whole(entry.neededMbps)} Mbps, more than your line`
}

/**
 * The one mono line a card prints beside its medal, as the phone does: codec,
 * sound and size, each left out when the release did not name it. The range
 * is the medal's to say; languages and subtitles wait in the details.
 */
export function metaLine(entry: Pick<SourceEntry, 'codec' | 'audio' | 'size'>): string {
  return [entry.codec, entry.audio, entry.size]
    .filter((part) => part.length > 0 && part !== NOT_PARSED)
    .map((part) => part.toUpperCase())
    .join(SEPARATOR)
}

export function detailsFor(
  entry: SourceEntry,
  device: DeviceContext,
  maximumNeededMbps: number,
): SourceDetails {
  const audioLanguages: DetailChip[] = splitTokens(entry.languages)
    .filter((token) => token !== NOT_PARSED)
    .map((token) => ({ label: languageName(token).toUpperCase(), muted: false }))
  // An empty tile reads as a rendering fault, so absence is said out loud.
  if (audioLanguages.length === 0) audioLanguages.push({ label: 'NOT LISTED', muted: true })
  const subtitles: DetailChip[] = entry.subtitleLanguages.map((track) => ({
    label: languageName(track).toUpperCase(),
    muted: false,
  }))
  if (subtitles.length === 0) subtitles.push({ label: 'NONE INCLUDED', muted: true })

  const cache: Record<SourceStatus, { label: string; good: boolean }> = {
    instant: { label: 'Already cached for you', good: true },
    caching: { label: 'Caching now', good: false },
    uncached: { label: 'Not cached yet', good: false },
    unknown: { label: 'Cache state not reported', good: false },
  }

  const audio = entry.audio.toUpperCase()
  const space = audio.indexOf(' ')
  const maximum = maximumNeededMbps > 0 ? maximumNeededMbps : entry.neededMbps
  const measured = entry.neededMbps > 0
  return {
    resolution: dimensions(entry.tier, entry.quality) ?? 'Resolution not listed',
    // Every chip is set in the mono plate, which is drawn for upper case.
    picture: !entry.range || entry.range === 'SDR' ? 'SDR' : rangeDetail(entry.range).toUpperCase(),
    codec: codecName(entry.codec) || 'Codec not listed',
    sound: soundDetail(entry.audio),
    channels: space === -1 ? 'Channels not listed' : audio.slice(space + 1),
    audioLanguages,
    subtitles,
    provider: entry.provider || 'An addon',
    cacheLabel: cache[entry.status].label,
    cacheGood: cache[entry.status].good,
    lineLabel: maximum > 0 ? `HEAVIEST HERE ${whole(maximum)} MBPS` : 'BANDWIDTH NOT MEASURED',
    mbpsLabel: measured ? `${whole(entry.neededMbps)} Mbps` : 'Not available',
    headroom: !measured
      ? 'File size or runtime is not listed'
      : device.lineMbps > 0
        ? `${whole((entry.neededMbps / device.lineMbps) * 100)}% of your ${whole(device.lineMbps)} Mbps line`
        : 'Line speed has not been measured yet',
    meterFraction: measured && maximum > 0 ? Math.min(1, Math.max(0, entry.neededMbps / maximum)) : 0,
  }
}

export function countLabel(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`
}

/** Sentence fragments completing "<provider> …" in the banner and footer. */
export function addonFailureCopy(code: string | undefined): string {
  switch (code) {
    case 'timeout':
      return 'did not answer in time'
    case 'upstream_http':
      return 'returned an error'
    case 'blocked_target':
      return 'was blocked for safety'
    case 'invalid_response':
      return 'returned invalid data'
    default:
      return 'is unavailable'
  }
}

/**
 * An addon's name as the sheet may print it: control characters dropped, and
 * anything that looks like a URL, a path or a token replaced outright, since
 * a misconfigured addon can report its manifest URL or a key as its name.
 */
export function sanitizeAddonName(name: string | undefined): string {
  if (name == null) return 'An addon'
  const cleaned = [...name]
    .filter((character) => {
      const code = character.codePointAt(0)!
      return code >= 0x20 && code !== 0x7f
    })
    .join('')
    .trim()
  if (!cleaned || cleaned.includes('://') || cleaned.includes('/') || cleaned.includes('\\')) {
    return 'An addon'
  }
  if (/[A-Za-z0-9_-]{32,}/.test(cleaned)) return 'An addon'
  return cleaned.length > 80 ? cleaned.slice(0, 80) : cleaned
}
