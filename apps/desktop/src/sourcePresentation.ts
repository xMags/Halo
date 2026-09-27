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

/** How the sheet talks about picture: coarser than the parsed resolution. */
export const TIER = { ultraHd: 0, fullHd: 1, hd: 2, lower: 3 } as const
export type QualityTier = (typeof TIER)[keyof typeof TIER]

/** The only thing a viewer has to decide between. */
export const SPEED = { immediate: 0, shortWait: 1, needsDownload: 2 } as const
export type StartSpeed = (typeof SPEED)[keyof typeof SPEED]

/**
 * `caching` exists natively but no resolver ever assigns it: addons only say
 * cached, not cached, or nothing. It is kept so the palette stays complete.
 */
export type SourceStatus = 'instant' | 'ondisk' | 'caching' | 'uncached' | 'unknown'

/** The parser's placeholder for anything it could not identify. */
export const NOT_PARSED = 'UNKNOWN'

const SEPARATOR = ' · '

/** A source flattened out of its addon group with every decision already made. */
export interface SourceEntry {
  key: string
  /** Grouping identity; never shown. */
  providerId: string
  /** Sanitized provider name, or `On this device`. */
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
  hdr: boolean
  surround: boolean
  /** Zero when unknown. */
  sizeBytes: number
  rank: number
  /** Megabits per second the file needs; zero when size or runtime is unknown. */
  neededMbps: number
}

/** What the sheet knows about this device that no single source can say. */
export interface DeviceContext {
  preferredSubtitleLanguage: string | null
  /** Measured line speed in Mbps; zero means never measured. */
  lineMbps: number
  /** Runtime in seconds; zero means unknown. */
  durationSeconds: number
  watchedSeconds: number
  watchedDurationSeconds: number
}

export interface DetailChip {
  label: string
  muted: boolean
}

/** The mono strip under a card's headline, and its subtitle plate. */
export interface MetaLine {
  line: string
  subtitles: string
  hasSubtitles: boolean
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
  /** The short form the compact strip uses. */
  code: string
}

const LANGUAGE_TAGS: LanguageTag[] = [
  { tag: 'ENG', name: 'English', code: 'EN' }, { tag: 'JPN', name: 'Japanese', code: 'JA' },
  { tag: 'GER', name: 'German', code: 'DE' }, { tag: 'FRE', name: 'French', code: 'FR' },
  { tag: 'SPA', name: 'Spanish', code: 'ES' }, { tag: 'ITA', name: 'Italian', code: 'IT' },
  { tag: 'KOR', name: 'Korean', code: 'KO' }, { tag: 'CHI', name: 'Chinese', code: 'ZH' },
  { tag: 'HIN', name: 'Hindi', code: 'HI' }, { tag: 'RUS', name: 'Russian', code: 'RU' },
  { tag: 'POR', name: 'Portuguese', code: 'PT' }, { tag: 'DUT', name: 'Dutch', code: 'NL' },
  { tag: 'NOR', name: 'Norwegian', code: 'NO' }, { tag: 'SWE', name: 'Swedish', code: 'SV' },
  { tag: 'DAN', name: 'Danish', code: 'DA' }, { tag: 'FIN', name: 'Finnish', code: 'FI' },
  { tag: 'POL', name: 'Polish', code: 'PL' }, { tag: 'TUR', name: 'Turkish', code: 'TR' },
  { tag: 'ARA', name: 'Arabic', code: 'AR' }, { tag: 'MULTI', name: 'several languages', code: 'MULTI' },
  { tag: 'DUAL', name: 'two languages', code: 'DUAL' },
  // Addons are not consistent about which three-letter spelling they send.
  { tag: 'DEU', name: 'German', code: 'DE' }, { tag: 'FRA', name: 'French', code: 'FR' },
  { tag: 'ZHO', name: 'Chinese', code: 'ZH' }, { tag: 'NLD', name: 'Dutch', code: 'NL' },
  { tag: 'CES', name: 'Czech', code: 'CS' }, { tag: 'CZE', name: 'Czech', code: 'CS' },
  { tag: 'ELL', name: 'Greek', code: 'EL' }, { tag: 'GRE', name: 'Greek', code: 'EL' },
  { tag: 'RON', name: 'Romanian', code: 'RO' }, { tag: 'RUM', name: 'Romanian', code: 'RO' },
  { tag: 'SLK', name: 'Slovak', code: 'SK' }, { tag: 'SLO', name: 'Slovak', code: 'SK' },
  { tag: 'EN', name: 'English', code: 'EN' }, { tag: 'JA', name: 'Japanese', code: 'JA' },
  { tag: 'DE', name: 'German', code: 'DE' }, { tag: 'FR', name: 'French', code: 'FR' },
  { tag: 'ES', name: 'Spanish', code: 'ES' }, { tag: 'IT', name: 'Italian', code: 'IT' },
  { tag: 'KO', name: 'Korean', code: 'KO' }, { tag: 'ZH', name: 'Chinese', code: 'ZH' },
  { tag: 'HI', name: 'Hindi', code: 'HI' }, { tag: 'RU', name: 'Russian', code: 'RU' },
  { tag: 'PT', name: 'Portuguese', code: 'PT' }, { tag: 'NL', name: 'Dutch', code: 'NL' },
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

/** `27:58`, or `1:04:12` once an hour is on the clock. */
function clock(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const secs = String(total % 60).padStart(2, '0')
  return hours > 0 ? `${hours}:${minutes}:${secs}` : `${minutes}:${secs}`
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

function languageCode(code: string): string {
  return findTag(code)?.code ?? code.toUpperCase()
}

/** The channel layout on its own; the codec name where no layout was given. */
function soundToken(audio: string): string {
  if (!audio || audio === NOT_PARSED) return 'SOUND NOT LISTED'
  const text = audio.toUpperCase()
  const space = text.indexOf(' ')
  return space === -1 ? text : text.slice(space + 1)
}

/** Four codes fit beside the plate before the strip wraps; the rest are counted. */
function languageToken(languages: string): string {
  const shown = 4
  const codes: string[] = []
  for (const token of splitTokens(languages)) {
    if (token === NOT_PARSED) continue
    const code = languageCode(token)
    if (!codes.includes(code)) codes.push(code)
  }
  if (codes.length === 0) return 'LANGUAGE NOT LISTED'
  const listed = codes.slice(0, shown).join('/')
  return codes.length > shown ? `${listed} +${codes.length - shown}` : listed
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

/** The tier in the badge's voice: still tiers, since Full HD covers 1440p too. */
export function badgeTierLabel(tier: QualityTier): string {
  return tierLabel(tier).toUpperCase()
}

/** Gold is spent only on the top tier, so it still means something. */
export function isPremiumTier(tier: QualityTier): boolean {
  return tier === TIER.ultraHd
}

export function speedOf(status: SourceStatus): StartSpeed {
  if (status === 'instant' || status === 'ondisk') return SPEED.immediate
  if (status === 'caching') return SPEED.shortWait
  return SPEED.needsDownload
}

export function statusLabel(status: SourceStatus): string {
  switch (status) {
    case 'instant':
      return 'Plays instantly'
    case 'ondisk':
      return 'Already on this device'
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

export function isSurround(audio: string): boolean {
  const text = audio.toUpperCase()
  if (text === 'ATMOS' || text === 'TRUEHD' || text === 'DTS-HD' || text === 'DTS') return true
  // Every other layout is written "N.M"; five in front of the point is surround.
  const point = text.indexOf('.')
  if (point <= 0) return false
  const leading = text[point - 1]!
  return leading >= '5' && leading <= '9'
}

export function sizeLabel(size: string): string {
  return !size || size === NOT_PARSED ? 'size not listed' : size
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
  // A file on the disk plays off local storage; the line is not the question.
  if (entry.status === 'ondisk') return ''
  if (entry.neededMbps <= 0 || lineMbps <= 0) return ''
  if (entry.neededMbps <= lineMbps) return ''
  return `Needs ${whole(entry.neededMbps)} Mbps, more than your line`
}

export function ordinal(position: number): string {
  const value = position < 1 ? 1 : position
  const lastTwo = value % 100
  if (lastTwo >= 11 && lastTwo <= 13) return `${value}th`
  switch (value % 10) {
    case 1:
      return `${value}st`
    case 2:
      return `${value}nd`
    case 3:
      return `${value}rd`
    default:
      return `${value}th`
  }
}

export function reasonFor(entry: SourceEntry, pick: SourceEntry | null, isSmallest: boolean): string {
  if (entry.status === 'ondisk') return 'On your disk'
  if (pick && pick.key === entry.key) return 'Halo’s pick'
  const place = ordinal(entry.rank + 1)
  if (!pick) return place
  if (pick.surround && !entry.surround) return `${place} · stereo only`
  if (pick.hdr && !entry.hdr) return `${place} · no HDR`
  if (entry.tier > pick.tier) return `${place} · lower picture`
  if (isSmallest) return `${place} · smallest file`
  return place
}

/** The single sentence under the quality plate on the recommended pick. */
export function pickHeadline(entry: SourceEntry, alone: boolean): string {
  // Before the alone case: a saved file answered for itself, not a provider.
  if (entry.status === 'ondisk') return 'Already saved here, so it starts with no connection at all.'
  if (alone) return 'The only source that answered for this.'
  if (entry.speed === SPEED.immediate) {
    return entry.tier === TIER.ultraHd || entry.tier === TIER.fullHd
      ? 'The best picture here that starts with no waiting.'
      : 'The one that starts with no waiting.'
  }
  if (entry.speed === SPEED.shortWait) return 'The best picture here, once the cache catches up.'
  return 'The best picture here, but it has to come down first.'
}

/**
 * The pick names its dynamic range because it is the one card arguing for
 * itself; rows leave that to their plate. The preferred subtitle language
 * decides what the plate can promise.
 */
export function metaLineFor(
  entry: SourceEntry,
  includeRange: boolean,
  preferredSubtitleLanguage: string | null,
): MetaLine {
  const parts = [sizeLabel(entry.size).toUpperCase()]
  if (includeRange && isHdr(entry.range)) parts.push(rangeDetail(entry.range).toUpperCase())
  parts.push(soundToken(entry.audio))
  parts.push(languageToken(entry.languages))
  const line = parts.join(SEPARATOR)

  const tracks = entry.subtitleLanguages
  if (tracks.length === 0) return { line, subtitles: 'NO SUBS', hasSubtitles: false }
  if (preferredSubtitleLanguage) {
    const wantedName = languageName(preferredSubtitleLanguage)
    const wantedTag = preferredSubtitleLanguage.toUpperCase()
    const found = tracks.some(
      (track) => track.toUpperCase() === wantedTag || languageName(track) === wantedName,
    )
    const code = languageCode(preferredSubtitleLanguage)
    return found
      ? { line, subtitles: `SUB ${code}`, hasSubtitles: true }
      : { line, subtitles: `NO ${code} SUBS`, hasSubtitles: false }
  }
  return {
    line,
    subtitles: tracks.length === 1 ? '1 SUB' : `${tracks.length} SUBS`,
    hasSubtitles: true,
  }
}

export function hasWatchProgress(device: DeviceContext): boolean {
  if (device.watchedDurationSeconds <= 0 || device.watchedSeconds <= 0) return false
  const fraction = device.watchedSeconds / device.watchedDurationSeconds
  return fraction > 0.02 && fraction < 0.98
}

export function watchNote(device: DeviceContext): string {
  if (!hasWatchProgress(device)) return ''
  const fraction = device.watchedSeconds / device.watchedDurationSeconds
  return `You watched ${whole(fraction * 100)}% of this already`
}

/** `Watch progress` row text; kept beside the note so the clock format is shared. */
export function watchProgressLine(device: DeviceContext): string {
  return `${clock(device.watchedSeconds)} of ${clock(device.watchedDurationSeconds)}`
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
    ondisk: { label: 'Saved on this device', good: true },
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
