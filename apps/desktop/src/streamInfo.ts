import type { Stream } from '@halo/core'

/**
 * Stremio's stream objects carry no structured technical metadata — addons put
 * it in free text (`name` is usually the addon + quality badge, `title` the
 * release file name plus a line of emoji-decorated facts). Every client that
 * shows a useful source list therefore parses that text, and so do we.
 *
 * The rules here are deliberately conservative: a token has to look
 * unambiguous to be claimed, and anything unrecognised is simply left out
 * rather than guessed at. A wrong badge is worse than a missing one — the
 * whole point of the source list is deciding which file to commit to.
 */

/** Ordered best-first; the index doubles as the ranking weight. */
const QUALITY_ORDER = ['2160p', '1440p', '1080p', '720p', '480p', 'SD'] as const
export type Quality = (typeof QUALITY_ORDER)[number]

export interface StreamInfo {
  /** Release file name — the mono line that identifies the source. */
  filename: string
  quality: Quality | null
  /** `HDR10` / `DV` / `HLG`, when the release advertises one. */
  dynamicRange: string | null
  /** `HEVC 10-BIT`, `AV1`, `H.264`… */
  codec: string | null
  /** `ATMOS`, `DDP 5.1`, `DTS-HD`, `AAC`… */
  audio: string | null
  /** Bytes, from the behaviour hint when present, else parsed from the text. */
  sizeBytes: number | null
  /**
   * True when the addon says the file is already on the user's debrid (no
   * wait). Null means the addon didn't say — not "no".
   */
  cached: boolean | null
  /** Uppercased language tokens the release names, e.g. `['ENG', 'JPN']`. */
  languages: string[]
  /** Free-text line shown under the file name (seeders, provider, …). */
  detail: string
}

function searchText(stream: Stream): string {
  return [stream.name, stream.title, stream.description].filter(Boolean).join('\n')
}

function matchQuality(text: string): Quality | null {
  if (/\b(2160p?|4k|uhd)\b/i.test(text)) return '2160p'
  if (/\b1440p\b/i.test(text)) return '1440p'
  if (/\b1080p?\b|\bfullhd\b/i.test(text)) return '1080p'
  if (/\b720p?\b|\bhd\b/i.test(text)) return '720p'
  if (/\b480p?\b/i.test(text)) return '480p'
  if (/\b(sd|cam|ts)\b/i.test(text)) return 'SD'
  return null
}

function matchDynamicRange(text: string): string | null {
  // Dolby Vision is claimed only on the explicit tokens: a bare "DV" appears
  // in unrelated group names often enough to be untrustworthy.
  if (/\b(dolby[ .]?vision|dovi)\b/i.test(text) || /\bDV\b/.test(text)) return 'DV'
  if (/\bhdr10\+?\b/i.test(text)) return 'HDR10'
  if (/\bhlg\b/i.test(text)) return 'HLG'
  if (/\bhdr\b/i.test(text)) return 'HDR'
  return null
}

function matchCodec(text: string): string | null {
  const tenBit = /\b10[ .-]?bit\b/i.test(text)
  if (/\b(hevc|h[ .]?265|x265)\b/i.test(text)) return tenBit ? 'HEVC 10-BIT' : 'HEVC'
  if (/\bav1\b/i.test(text)) return tenBit ? 'AV1 10-BIT' : 'AV1'
  if (/\b(avc|h[ .]?264|x264)\b/i.test(text)) return 'H.264'
  if (/\bxvid\b/i.test(text)) return 'XVID'
  return null
}

function matchAudio(text: string): string | null {
  if (/\batmos\b/i.test(text)) return 'ATMOS'
  if (/\btrue[ .]?hd\b/i.test(text)) return 'TRUEHD'
  if (/\bdts[ .-]?hd\b/i.test(text)) return 'DTS-HD'
  if (/\bdts\b/i.test(text)) return 'DTS'
  if (/\b(ddp|eac3|e-ac-3)\b/i.test(text)) return matchChannels(text, 'DDP')
  if (/\b(dd|ac3)\b/i.test(text)) return matchChannels(text, 'DD')
  if (/\baac\b/i.test(text)) return matchChannels(text, 'AAC')
  if (/\bopus\b/i.test(text)) return 'OPUS'
  if (/\bflac\b/i.test(text)) return 'FLAC'
  return null
}

function matchChannels(text: string, codec: string): string {
  // No leading \b: the channel count is usually glued to the codec ("DDP5.1").
  const channels = /([2578])[ .]([01])(?!\d)/.exec(text)
  return channels ? `${codec} ${channels[1]}.${channels[2]}` : codec
}

const SIZE_RE = /(\d+(?:[.,]\d+)?)\s*(GB|GiB|MB|MiB)\b/i

function matchSize(text: string): number | null {
  const match = SIZE_RE.exec(text)
  if (!match) return null
  const value = Number(match[1]!.replace(',', '.'))
  if (!Number.isFinite(value)) return null
  return /^g/i.test(match[2]!) ? value * 1024 ** 3 : value * 1024 ** 2
}

/**
 * Debrid addons flag an already-downloaded file with a `+` in their bracketed
 * provider tag (`[RD+]`), a lightning bolt, or the word "cached"/"instant".
 * The bracketed `[RD download]` form is the explicit opposite — a bare
 * "download" anywhere in the text is not, since release names contain it.
 */
function matchCached(text: string): boolean | null {
  if (/\[\w{2,3}\+\]|⚡|\b(cached|instant)\b/i.test(text)) return true
  if (/\[\w{2,3}\s*download\]/i.test(text)) return false
  return null
}

const LANGUAGE_TOKENS = /\b(ENG|ENGLISH|JPN|JAPANESE|GER|FRE|FRENCH|SPA|SPANISH|ITA|KOR|CHI|HIN|RUS|POR|DUT|NOR|SWE|DAN|FIN|POL|TUR|ARA|MULTI|DUAL)\b/gi
const LANGUAGE_ALIASES: Record<string, string> = {
  ENGLISH: 'ENG',
  JAPANESE: 'JPN',
  FRENCH: 'FRE',
  SPANISH: 'SPA',
}

function matchLanguages(text: string): string[] {
  const found = new Set<string>()
  for (const match of text.matchAll(LANGUAGE_TOKENS)) {
    const token = match[0]!.toUpperCase()
    found.add(LANGUAGE_ALIASES[token] ?? token)
  }
  return [...found].slice(0, 4)
}

/**
 * Everything on the `title` after the file-name line: seeders, provider,
 * upload age. Emoji are stripped — the design's row is a single quiet line,
 * and addons are wildly inconsistent about which pictograms they use.
 */
function detailLine(stream: Stream, filename: string): string {
  const raw = stream.title ?? stream.description ?? ''
  const rest = raw
    .split('\n')
    .filter((line) => line.trim() && line.trim() !== filename)
    .join(' · ')
  return rest
    .replace(/[\p{Extended_Pictographic}️]/gu, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

export function parseStreamInfo(stream: Stream): StreamInfo {
  const text = searchText(stream)
  const firstTitleLine = (stream.title ?? stream.description ?? '').split('\n')[0]?.trim()
  const filename = stream.behaviorHints?.filename ?? firstTitleLine ?? stream.name ?? 'Unnamed source'

  return {
    filename,
    quality: matchQuality(text),
    dynamicRange: matchDynamicRange(text),
    codec: matchCodec(text),
    audio: matchAudio(text),
    sizeBytes: stream.behaviorHints?.videoSize ?? matchSize(text),
    cached: matchCached(text),
    languages: matchLanguages(text),
    detail: detailLine(stream, filename),
  }
}

/** Rank weight for a quality; unknown sorts below everything recognised. */
export function qualityRank(quality: Quality | null): number {
  const index = quality ? QUALITY_ORDER.indexOf(quality) : -1
  return index === -1 ? QUALITY_ORDER.length : index
}

/**
 * Best-first ordering: a file that plays now beats a better file that has to
 * be fetched, then resolution, then the larger file at equal resolution
 * (bitrate is the only quality signal left once the labels match).
 */
export function compareStreams(a: StreamInfo, b: StreamInfo): number {
  const cachedRank = (info: StreamInfo) => (info.cached === true ? 0 : 1)
  return (
    cachedRank(a) - cachedRank(b) ||
    qualityRank(a.quality) - qualityRank(b.quality) ||
    (b.sizeBytes ?? 0) - (a.sizeBytes ?? 0)
  )
}
