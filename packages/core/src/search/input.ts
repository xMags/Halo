/*
 * What the search box holds: words to search the catalogs for, or a link (or
 * bare id) that names one thing directly. A hand-written parser on purpose:
 * what counts as a web link here is what playback may open, so the rules are
 * spelled out rather than left to a general URL parser, which would accept
 * (and normalise) more than the player should be handed. Shared by every
 * client; the Kotlin app carries the same rules, both held to
 * `test/vectors/search-input.json`.
 */

export type TvdbKind = 'series' | 'movie'

export type SearchInput =
  | { kind: 'text' }
  /** `tt` and 7 to 10 digits, lower case. */
  | { kind: 'imdbTitle'; imdbId: string }
  /** `nm` and 7 to 10 digits, lower case. */
  | { kind: 'imdbPerson'; personId: string }
  | { kind: 'tvdbNumeric'; tvdbId: string; tvdbKind: TvdbKind }
  /** `displayName` is the decoded slug, for showing only. */
  | { kind: 'tvdbSlug'; tvdbKind: TvdbKind; displayName: string }
  /** 40 lower-case hex characters; `displayName` is the decoded `dn`, for showing only. Trackers are never kept. */
  | { kind: 'magnet'; infoHash: string; displayName: string }
  /** The link as given (checked), its host in lower case, and what the player calls it. */
  | { kind: 'webLink'; url: string; host: string; displayName: string }
  /** A sentence for the viewer. */
  | { kind: 'unsupported'; reason: string }

export type SearchInputKind = SearchInput['kind']

/** The longest link that counts as a web link. */
export const SEARCH_WEB_LINK_MAX_LENGTH = 2048
/** The most of a magnet's `dn` (or a TVDB slug) kept for showing. */
export const SEARCH_MAGNET_NAME_MAX_LENGTH = 200
/** The most of a link's file name the player shows as its title. */
export const SEARCH_WEB_LINK_TITLE_MAX_LENGTH = 120

export const UNSUPPORTED_REASONS = {
  localFile: "Files on this PC can't be opened from Search.",
  otherScheme: "This kind of link isn't supported.",
  tooLong: 'This link is too long.',
  credentials: "Links with a user name or password aren't supported.",
  invalidLink: "This link isn't valid.",
  magnetV2: "This magnet link uses the newer v2 format, which isn't supported.",
  magnetInvalid: 'This magnet link has no valid info hash.',
  imdbOther: "This IMDb link isn't a title or a person.",
  tvdbOther: "This TVDB link isn't a film or a series.",
  videoSite: "YouTube and live TV links aren't supported.",
} as const

/**
 * Schemes that are links although no `//` follows them. Anything else with a
 * colon in it (`Re:Zero`, `Dune: Part Two`) stays words to search.
 */
const OPAQUE_SCHEMES: ReadonlySet<string> = new Set([
  'magnet',
  'file',
  'data',
  'javascript',
  'vbscript',
  'mailto',
  'about',
  'blob',
  'urn',
  'ed2k',
  'acestream',
  'stremio',
])

const VIDEO_SITE_HOSTS = ['youtube.com', 'youtu.be', 'twitch.tv'] as const

/** ASCII-only lower case: what these rules compare by, whatever the locale. */
function lowerAscii(text: string): string {
  return text.replace(/[A-Z]/g, (letter) => letter.toLowerCase())
}

function equalsIgnoreCase(left: string, right: string): boolean {
  return left.length === right.length && lowerAscii(left) === lowerAscii(right)
}

function startsWithIgnoreCase(text: string, prefix: string): boolean {
  return text.length >= prefix.length && equalsIgnoreCase(text.slice(0, prefix.length), prefix)
}

const isDigit = (unit: string): boolean => unit >= '0' && unit <= '9'
const isAsciiLetter = (unit: string): boolean => (unit >= 'a' && unit <= 'z') || (unit >= 'A' && unit <= 'Z')
const allDigits = (text: string): boolean => /^[0-9]+$/.test(text)

function hexValue(unit: string): number {
  if (isDigit(unit)) return unit.charCodeAt(0) - 48
  const lower = lowerAscii(unit)
  if (lower >= 'a' && lower <= 'f') return lower.charCodeAt(0) - 87
  return -1
}

/** `prefix` (two letters, any case) and 7 to 10 digits, as IMDb's ids are; lower case. */
function imdbStyleId(text: string, prefix: string): string | null {
  if (!startsWithIgnoreCase(text, prefix)) return null
  const digits = text.slice(prefix.length)
  if (digits.length < 7 || digits.length > 10 || !allDigits(digits)) return null
  return prefix + digits
}

function appendUtf8(bytes: number[], point: number): void {
  if (point < 0x80) bytes.push(point)
  else if (point < 0x800) bytes.push(0xc0 | (point >> 6), 0x80 | (point & 0x3f))
  else if (point < 0x10000) bytes.push(0xe0 | (point >> 12), 0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f))
  else bytes.push(0xf0 | (point >> 18), 0x80 | ((point >> 12) & 0x3f), 0x80 | ((point >> 6) & 0x3f), 0x80 | (point & 0x3f))
}

/**
 * Strict UTF-8: anything malformed (overlong, surrogate, truncated) reads as
 * U+FFFD one byte at a time, so a hostile `%`-sequence cannot smuggle
 * anything through.
 */
function decodeUtf8(bytes: readonly number[]): string {
  let text = ''
  let index = 0
  while (index < bytes.length) {
    const lead = bytes[index]!
    if (lead < 0x80) {
      text += String.fromCharCode(lead)
      index += 1
      continue
    }
    let length: number
    let point: number
    let minimum: number
    if ((lead & 0xe0) === 0xc0) [length, point, minimum] = [2, lead & 0x1f, 0x80]
    else if ((lead & 0xf0) === 0xe0) [length, point, minimum] = [3, lead & 0x0f, 0x800]
    else if ((lead & 0xf8) === 0xf0) [length, point, minimum] = [4, lead & 0x07, 0x10000]
    else {
      text += '�'
      index += 1
      continue
    }
    let valid = index + length <= bytes.length
    for (let offset = 1; valid && offset < length; offset += 1) {
      const next = bytes[index + offset]!
      if ((next & 0xc0) !== 0x80) {
        valid = false
        break
      }
      point = (point << 6) | (next & 0x3f)
    }
    if (!valid || point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) {
      text += '�'
      index += 1
      continue
    }
    text += String.fromCodePoint(point)
    index += length
  }
  return text
}

/** `%XX` as bytes of UTF-8 (and `+` as a space in a query value); the characters already in the text are kept as they are. */
function percentDecoded(text: string, plusIsSpace: boolean): string {
  const bytes: number[] = []
  for (let index = 0; index < text.length; index += 1) {
    const unit = text[index]!
    if (unit === '%' && index + 2 < text.length) {
      const high = hexValue(text[index + 1]!)
      const low = hexValue(text[index + 2]!)
      if (high >= 0 && low >= 0) {
        bytes.push((high << 4) | low)
        index += 2
        continue
      }
    }
    if (unit === '+' && plusIsSpace) {
      bytes.push(0x20)
      continue
    }
    const code = text.charCodeAt(index)
    let point = code
    const nextCode = index + 1 < text.length ? text.charCodeAt(index + 1) : 0
    if (code >= 0xd800 && code <= 0xdbff && nextCode >= 0xdc00 && nextCode <= 0xdfff) {
      point = 0x10000 + ((code - 0xd800) << 10) + (nextCode - 0xdc00)
      index += 1
    } else if (code >= 0xd800 && code <= 0xdfff) {
      point = 0xfffd
    }
    appendUtf8(bytes, point)
  }
  return decodeUtf8(bytes)
}

/** Control characters, and the direction overrides that could make a name read as something it is not. */
function isHiddenCharacter(code: number): boolean {
  return (
    code < 0x20 ||
    (code >= 0x7f && code <= 0x9f) ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069) ||
    code === 0x200e ||
    code === 0x200f
  )
}

function isSpace(code: number): boolean {
  return code === 0x20 || code === 0xa0 || code === 0x3000 || (code >= 0x2000 && code <= 0x200b)
}

/**
 * What may be shown of a name taken from a link: no hidden characters, no
 * surrounding space, and at most `limit` UTF-16 units without cutting a
 * surrogate pair in half.
 */
function displayable(text: string, limit: number): string {
  let shown = ''
  for (let index = 0; index < text.length; index += 1) {
    if (!isHiddenCharacter(text.charCodeAt(index))) shown += text[index]
  }
  let first = 0
  while (first < shown.length && isSpace(shown.charCodeAt(first))) first += 1
  let last = shown.length
  while (last > first && isSpace(shown.charCodeAt(last - 1))) last -= 1
  shown = shown.slice(first, last)
  if (shown.length > limit) {
    let length = limit
    const end = shown.charCodeAt(length - 1)
    if (length > 0 && end >= 0xd800 && end <= 0xdbff) length -= 1
    shown = shown.slice(0, length)
    while (shown.length > 0 && isSpace(shown.charCodeAt(shown.length - 1))) shown = shown.slice(0, -1)
  }
  return shown
}

const unsupported = (reason: string): SearchInput => ({ kind: 'unsupported', reason })

function isDrivePath(text: string): boolean {
  return text.length >= 3 && isAsciiLetter(text[0]!) && text[1] === ':' && (text[2] === '\\' || text[2] === '/')
}

function isUncPath(text: string): boolean {
  return text.startsWith('\\\\') || text.startsWith('//')
}

/** The scheme, lower case, when `text` is a link: a valid scheme of two or more characters followed by `//`, or an opaque scheme. */
function linkScheme(text: string): string | null {
  const colon = text.indexOf(':')
  if (colon < 2 || !isAsciiLetter(text[0]!)) return null
  for (let index = 1; index < colon; index += 1) {
    const unit = text[index]!
    if (!isAsciiLetter(unit) && !isDigit(unit) && unit !== '+' && unit !== '-' && unit !== '.') return null
  }
  const scheme = lowerAscii(text.slice(0, colon))
  if (text.slice(colon + 1).startsWith('//')) return scheme
  return OPAQUE_SCHEMES.has(scheme) ? scheme : null
}

function pathSegments(path: string): string[] {
  return path.split('/').filter((segment) => segment.length > 0)
}

/** `key=value` pairs of a query, keys in lower case, values left encoded. */
function queryPairs(query: string): Array<[string, string]> {
  const pairs: Array<[string, string]> = []
  for (const part of query.split('&')) {
    const equals = part.indexOf('=')
    if (equals >= 0) pairs.push([lowerAscii(part.slice(0, equals)), part.slice(equals + 1)])
  }
  return pairs
}

function hostIsOrUnder(host: string, domain: string): boolean {
  return host === domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] === '.')
}

/** RFC 4648 base32 (A to Z, 2 to 7), any case, 32 characters: 20 bytes, as hex. */
function base32ToHex(text: string): string | null {
  if (text.length !== 32) return null
  let hex = ''
  let buffer = 0
  let bits = 0
  for (const unit of text) {
    const lower = lowerAscii(unit)
    let digit: number
    if (lower >= 'a' && lower <= 'z') digit = lower.charCodeAt(0) - 97
    else if (lower >= '2' && lower <= '7') digit = lower.charCodeAt(0) - 50 + 26
    else return null
    buffer = ((buffer << 5) | digit) & 0xfffff
    bits += 5
    while (bits >= 4) {
      bits -= 4
      hex += ((buffer >> bits) & 0xf).toString(16)
    }
  }
  return hex
}

/** 40 hex characters or 32 base32 ones, as lower-case hex. */
function normalizedInfoHash(text: string): string | null {
  if (text.length === 40) return /^[0-9a-fA-F]{40}$/.test(text) ? lowerAscii(text) : null
  return base32ToHex(text)
}

function isMagnetTopicKey(key: string): boolean {
  return key === 'xt' || (key.startsWith('xt.') && allDigits(key.slice(3)))
}

function classifyMagnet(text: string): SearchInput {
  const prefix = 'magnet:?'
  if (!startsWithIgnoreCase(text, prefix)) return unsupported(UNSUPPORTED_REASONS.magnetInvalid)
  let query = text.slice(prefix.length)
  const fragment = query.indexOf('#')
  if (fragment >= 0) query = query.slice(0, fragment)
  const hashes = new Set<string>()
  let invalidHash = false
  let sawV2 = false
  let name: string | null = null
  for (const [key, value] of queryPairs(query)) {
    if (isMagnetTopicKey(key)) {
      const topic = percentDecoded(value, false)
      if (startsWithIgnoreCase(topic, 'urn:btih:')) {
        const hash = normalizedInfoHash(topic.slice(9))
        if (hash) hashes.add(hash)
        else invalidHash = true
      } else if (startsWithIgnoreCase(topic, 'urn:btmh:')) {
        sawV2 = true
      }
    } else if (key === 'dn' && name === null) {
      name = displayable(percentDecoded(value, true), SEARCH_MAGNET_NAME_MAX_LENGTH)
    }
    // `tr` and every other key are dropped: no tracker is ever kept.
  }
  if (!invalidHash && hashes.size === 1) return { kind: 'magnet', infoHash: [...hashes][0]!, displayName: name ?? '' }
  if (!invalidHash && hashes.size === 0 && sawV2) return unsupported(UNSUPPORTED_REASONS.magnetV2)
  return unsupported(UNSUPPORTED_REASONS.magnetInvalid)
}

function classifyImdb(path: string[]): SearchInput {
  let segments = path
  // IMDb's localised pages put the language first: `/fr/title/tt…`.
  if (segments.length > 2 && (segments[0]!.length === 2 || (segments[0]!.length === 5 && segments[0]![2] === '-'))) {
    segments = segments.slice(1)
  }
  if (segments.length >= 2 && equalsIgnoreCase(segments[0]!, 'title')) {
    const id = imdbStyleId(segments[1]!, 'tt')
    if (id) return { kind: 'imdbTitle', imdbId: id }
  }
  if (segments.length >= 2 && equalsIgnoreCase(segments[0]!, 'name')) {
    const id = imdbStyleId(segments[1]!, 'nm')
    if (id) return { kind: 'imdbPerson', personId: id }
  }
  return unsupported(UNSUPPORTED_REASONS.imdbOther)
}

function classifyTvdb(segments: string[], query: string): SearchInput {
  const [first, second, third] = segments
  if (
    segments.length >= 3 &&
    equalsIgnoreCase(first!, 'dereferrer') &&
    (equalsIgnoreCase(second!, 'series') || equalsIgnoreCase(second!, 'movie')) &&
    allDigits(third!) &&
    third!.length <= 10
  ) {
    return { kind: 'tvdbNumeric', tvdbId: third!, tvdbKind: lowerAscii(second!) as TvdbKind }
  }
  if (segments.length >= 2 && (equalsIgnoreCase(first!, 'series') || equalsIgnoreCase(first!, 'movies'))) {
    return {
      kind: 'tvdbSlug',
      tvdbKind: equalsIgnoreCase(first!, 'series') ? 'series' : 'movie',
      displayName: displayable(percentDecoded(second!, false), SEARCH_MAGNET_NAME_MAX_LENGTH),
    }
  }
  if (segments.length === 0 || (segments.length === 1 && equalsIgnoreCase(first!, 'index.php'))) {
    let seriesTab = false
    let id: string | null = null
    for (const [key, value] of queryPairs(query)) {
      if (key === 'tab' && equalsIgnoreCase(value, 'series')) seriesTab = true
      if (key === 'id' && allDigits(value) && value.length <= 10) id = value
    }
    if (seriesTab && id) return { kind: 'tvdbNumeric', tvdbId: id, tvdbKind: 'series' }
  }
  return unsupported(UNSUPPORTED_REASONS.tvdbOther)
}

/** What the player calls a pasted link: its file name, else its host. */
function webLinkTitle(segments: string[], host: string): string {
  const last = segments.at(-1)
  if (last !== undefined) {
    const name = displayable(percentDecoded(last, false), SEARCH_WEB_LINK_TITLE_MAX_LENGTH)
    if (name) return name
  }
  return host
}

function hostOf(authority: string): string | null {
  let host = authority
  let port = ''
  if (authority.startsWith('[')) {
    const close = authority.indexOf(']')
    if (close < 0) return null
    host = authority.slice(0, close + 1)
    const rest = authority.slice(close + 1)
    if (rest) {
      if (rest[0] !== ':') return null
      port = rest.slice(1)
    }
    for (const unit of host.slice(1, -1)) if (hexValue(unit) < 0 && unit !== ':' && unit !== '.') return null
    if (host.length <= 2) return null
  } else {
    const colon = authority.lastIndexOf(':')
    if (colon >= 0) {
      host = authority.slice(0, colon)
      port = authority.slice(colon + 1)
    }
    for (const unit of host) {
      if (!isAsciiLetter(unit) && !isDigit(unit) && unit !== '-' && unit !== '.' && unit !== '_' && unit.charCodeAt(0) < 0x80) return null
    }
  }
  if (port) {
    if (port.length > 5 || !allDigits(port)) return null
    const number = Number(port)
    if (number === 0 || number > 65535) return null
  }
  let lower = lowerAscii(host)
  if (lower.endsWith('.')) lower = lower.slice(0, -1)
  if (!lower || lower.startsWith('.')) return null
  return lower
}

function classifyWeb(text: string, schemeLength: number): SearchInput {
  if (text.length > SEARCH_WEB_LINK_MAX_LENGTH) return unsupported(UNSUPPORTED_REASONS.tooLong)
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index)
    // White space, control characters and backslashes have no place in a
    // link, and different parsers read them differently.
    if (code <= 0x20 || isHiddenCharacter(code) || isSpace(code) || text[index] === '\\') {
      return unsupported(UNSUPPORTED_REASONS.invalidLink)
    }
  }
  let rest = text.slice(schemeLength + 1)
  if (!rest.startsWith('//')) return unsupported(UNSUPPORTED_REASONS.invalidLink)
  rest = rest.slice(2)
  const authorityEnd = rest.search(/[/?#]/)
  const end = authorityEnd < 0 ? rest.length : authorityEnd
  const authority = rest.slice(0, end)
  if (authority.includes('@')) return unsupported(UNSUPPORTED_REASONS.credentials)
  const host = hostOf(authority)
  if (!host) return unsupported(UNSUPPORTED_REASONS.invalidLink)
  let tail = rest.slice(end)
  const fragment = tail.indexOf('#')
  if (fragment >= 0) tail = tail.slice(0, fragment)
  let query = ''
  const question = tail.indexOf('?')
  if (question >= 0) {
    query = tail.slice(question + 1)
    tail = tail.slice(0, question)
  }
  const segments = pathSegments(tail)
  if (VIDEO_SITE_HOSTS.some((site) => hostIsOrUnder(host, site))) return unsupported(UNSUPPORTED_REASONS.videoSite)
  if (host === 'imdb.com' || host === 'www.imdb.com' || host === 'm.imdb.com') return classifyImdb(segments)
  if (hostIsOrUnder(host, 'imdb.com')) return unsupported(UNSUPPORTED_REASONS.imdbOther)
  if (host === 'thetvdb.com' || host === 'www.thetvdb.com') return classifyTvdb(segments, query)
  return { kind: 'webLink', url: text, host, displayName: webLinkTitle(segments, host) }
}

/** Reads the box's text, trimmed of surrounding white space. */
export function classifySearchInput(trimmed: string): SearchInput {
  if (!trimmed) return { kind: 'text' }
  const id = imdbStyleId(trimmed, 'tt')
  if (id) return { kind: 'imdbTitle', imdbId: id }
  if (isDrivePath(trimmed) || isUncPath(trimmed)) return unsupported(UNSUPPORTED_REASONS.localFile)
  const scheme = linkScheme(trimmed)
  if (!scheme) return { kind: 'text' }
  if (scheme === 'magnet') return classifyMagnet(trimmed)
  if (scheme === 'http' || scheme === 'https') return classifyWeb(trimmed, scheme.length)
  if (scheme === 'file') return unsupported(UNSUPPORTED_REASONS.localFile)
  return unsupported(UNSUPPORTED_REASONS.otherScheme)
}
