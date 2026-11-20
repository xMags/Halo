/*
 * A pasted magnet's request as a server answers it (`POST /magnets`,
 * `GET /magnets/:id`). Everything is untrusted and checked strictly: a state
 * outside the fixed list, a size that is not a whole non-negative number, an
 * id of the wrong shape, or a play link whose path is not exactly
 * `/play/m1.<token>/<file name>` makes the whole answer an error. A play link
 * is kept only as that path, rebuilt on the signed-in server: the server
 * writes its links on its public API address, which may be another host than
 * the one this app talks to, and whatever host it names is never opened.
 * Names are display only, so they are cut rather than refused.
 */

export type MagnetState = 'ready' | 'not_cached' | 'downloading' | 'no_video' | 'too_large' | 'failed'

export interface MagnetFile {
  id: string
  name: string
  sizeBytes: number
  season: number | null
  episode: number | null
  /** On the signed-in server, never the host the answer named. */
  playUrl: string
}

export interface MagnetIdentity {
  type: 'movie' | 'series'
  id: string
  name: string
  year: string | null
  poster: string | null
}

export interface MagnetRequest {
  id: string
  state: MagnetState
  reason: string | null
  name: string
  sizeBytes: number
  progress: number
  /** Only a ready request has files, and it always has some. */
  files: MagnetFile[]
  identified: MagnetIdentity | null
}

export class MagnetAnswerError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MagnetAnswerError'
  }
}

const MAX_ID = 64
const MAX_FILE_ID = 32
const MAX_REASON = 40
const MAX_NAME = 300
const MAX_TITLE_NAME = 200
const MAX_PLAY_URL = 4096
/** The server lists at most 200 video files. */
const MAX_FILES = 200
const MAX_SEASON = 1000
const MAX_EPISODE = 10000
const MAX_TOKEN = 2048
const TMDB_IMAGE_PREFIX = 'https://image.tmdb.org/t/p/'
const STATES: ReadonlySet<string> = new Set(['ready', 'not_cached', 'downloading', 'no_video', 'too_large', 'failed'])
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/g

function refuse(why: string): never {
  throw new MagnetAnswerError(why)
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

/** A field that is present must have the expected type; absent or null is fine. */
function field(object: Record<string, unknown>, key: string, type: 'string' | 'number' | 'object' | 'array', why: string): unknown {
  const value = object[key]
  if (value === undefined || value === null) return undefined
  const ok = type === 'array' ? Array.isArray(value) : type === 'object' ? record(value) !== null : typeof value === type
  if (!ok) refuse(why)
  return value
}

function required(object: Record<string, unknown>, key: string, type: 'string' | 'number' | 'array', why: string): unknown {
  const value = field(object, key, type, why)
  if (value === undefined) refuse(why)
  return value
}

/** Trimmed, control characters as spaces, cut at `maximum` (never on half of a surrogate pair). */
function displayText(raw: string, maximum: number): string {
  let text = raw.trim().slice(0, maximum)
  const last = text.charCodeAt(text.length - 1)
  if (last >= 0xd800 && last <= 0xdbff) text = text.slice(0, -1)
  return text.replace(CONTROL, ' ').trimEnd()
}

function wholeSize(object: Record<string, unknown>, key: string, why: string): number {
  const number = required(object, key, 'number', why) as number
  if (!Number.isSafeInteger(number) || number < 0) refuse(why)
  return number
}

function smallCount(object: Record<string, unknown>, key: string, maximum: number, why: string): number | null {
  const number = field(object, key, 'number', why) as number | undefined
  if (number === undefined) return null
  if (!Number.isInteger(number) || number < 0 || number > maximum) refuse(why)
  return number
}

const isPlainId = (text: string, maximum: number): boolean => text.length > 0 && text.length <= maximum && /^[A-Za-z0-9_-]+$/.test(text)

interface Origin {
  scheme: string
  host: string
  port: string
  /** What follows the authority: empty, or from its `/`, `?` or `#` on. */
  rest: string
}

/**
 * scheme://host[:port] of an absolute http(s) link, or null. No user name or
 * password, no white space or control characters, a host of letters, digits,
 * dots and hyphens (or a bracketed IPv6 literal).
 */
function originOf(url: string): Origin | null {
  const schemeEnd = url.indexOf('://')
  if (schemeEnd <= 0) return null
  const scheme = url.slice(0, schemeEnd).toLowerCase()
  if (scheme !== 'http' && scheme !== 'https') return null
  const afterScheme = url.slice(schemeEnd + 3)
  const authorityEnd = afterScheme.search(/[/?#]/)
  const authority = authorityEnd < 0 ? afterScheme : afterScheme.slice(0, authorityEnd)
  const rest = authorityEnd < 0 ? '' : afterScheme.slice(authorityEnd)
  if (!authority) return null
  let host = authority
  let port = ''
  let portGiven = false
  if (authority.startsWith('[')) {
    const close = authority.indexOf(']')
    if (close < 0) return null
    host = authority.slice(0, close + 1)
    const after = authority.slice(close + 1)
    if (after) {
      if (after[0] !== ':') return null
      port = after.slice(1)
      portGiven = true
    }
    if (!/^[0-9a-fA-F:.]+$/.test(host.slice(1, -1))) return null
  } else {
    const colon = authority.lastIndexOf(':')
    if (colon >= 0) {
      host = authority.slice(0, colon)
      port = authority.slice(colon + 1)
      portGiven = true
    }
    if (!/^[A-Za-z0-9.-]+$/.test(host)) return null
  }
  if (portGiven && !port) return null
  if (port) {
    if (port.length > 5 || !/^\d+$/.test(port)) return null
    // Without leading zeros, so 443 and 0443 cannot read as different.
    port = port.replace(/^0+(?=\d)/, '')
  }
  return { scheme, host: host.toLowerCase(), port: port || (scheme === 'https' ? '443' : '80'), rest }
}

/** A path of printable ASCII only, as the server writes its links (the file name is percent-encoded). No fragment, no backslash. */
function isPlainPath(rest: string): boolean {
  return rest.startsWith('/') && /^[\x21-\x7e]+$/.test(rest) && !/[\\#"<>`{}|^]/.test(rest)
}

/** `m1.<payload>.<signature>`, both parts base64url, as the server signs a magnet's play token. */
function isMagnetToken(token: string): boolean {
  return token.length <= MAX_TOKEN && /^m1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)
}

/** The percent-encoded file name: never a dot segment, and never an encoded dot, slash or backslash a client might decode into one. */
function isPlayFileName(name: string): boolean {
  if (!name || name === '.' || name === '..') return false
  const lower = name.toLowerCase()
  return !lower.includes('%2e') && !lower.includes('%2f') && !lower.includes('%5c')
}

/**
 * The play link's `/play/<token>/<file name>` on `serverUrl` (the app's own
 * server base, joined as every other call joins its path), or null when the
 * link is not exactly that shape. The link's own scheme, host and port are
 * checked but never used.
 */
export function magnetPlayLinkOn(url: string, serverUrl: string): string | null {
  if (!url || url.length > MAX_PLAY_URL) return null
  const link = originOf(url)
  if (!link || !isPlainPath(link.rest) || link.rest.includes('?')) return null
  const at = link.rest.lastIndexOf('/play/')
  if (at < 0) return null
  const tail = link.rest.slice(at)
  const segments = tail.slice('/play/'.length)
  const slash = segments.indexOf('/')
  if (slash < 0) return null
  const token = segments.slice(0, slash)
  const name = segments.slice(slash + 1)
  if (!isMagnetToken(token) || name.includes('/') || !isPlayFileName(name)) return null
  const server = originOf(serverUrl)
  if (!server || /[?#]/.test(server.rest)) return null
  return serverUrl.replace(/\/+$/, '') + tail
}

function readFile(item: unknown, serverUrl: string): MagnetFile {
  const why = 'The magnet answer lists a file of the wrong shape.'
  const object = record(item) ?? refuse(why)
  const id = required(object, 'id', 'string', why) as string
  if (!isPlainId(id, MAX_FILE_ID)) refuse(why)
  const name = displayText(required(object, 'name', 'string', why) as string, MAX_NAME)
  if (!name) refuse(why)
  const playUrl = magnetPlayLinkOn(required(object, 'playUrl', 'string', why) as string, serverUrl)
  if (!playUrl) refuse('The magnet answer has a play link of the wrong shape.')
  return {
    id,
    name,
    sizeBytes: wholeSize(object, 'sizeBytes', why),
    season: smallCount(object, 'season', MAX_SEASON, why),
    episode: smallCount(object, 'episode', MAX_EPISODE, why),
    playUrl,
  }
}

function readIdentity(root: Record<string, unknown>): MagnetIdentity | null {
  const why = 'The magnet answer names a title of the wrong shape.'
  const value = field(root, 'identified', 'object', why)
  if (value === undefined) return null
  const object = value as Record<string, unknown>
  const type = required(object, 'type', 'string', why)
  if (type !== 'movie' && type !== 'series') refuse(why)
  const id = required(object, 'id', 'string', why) as string
  if (!/^tt\d{1,10}$/.test(id)) refuse(why)
  const name = displayText(required(object, 'name', 'string', why) as string, MAX_TITLE_NAME)
  if (!name) refuse(why)
  const yearText = field(object, 'year', 'string', why) as string | undefined
  if (yearText && !/^\d{4}$/.test(yearText)) refuse(why)
  // Artwork only from TMDB's own image host; another poster is left out rather than refused.
  const posterText = field(object, 'poster', 'string', why) as string | undefined
  const poster =
    posterText && posterText.length <= 300 && posterText.startsWith(TMDB_IMAGE_PREFIX) && !posterText.includes('..') &&
    /^[A-Za-z0-9/_.-]+$/.test(posterText.slice(TMDB_IMAGE_PREFIX.length))
      ? posterText
      : null
  return { type, id, name, year: yearText || null, poster }
}

/** One magnet answer, its play links rebuilt on `serverUrl`. Throws `MagnetAnswerError` when anything is out of shape. */
export function readMagnet(value: unknown, serverUrl: string): MagnetRequest {
  const root = record(value) ?? refuse('The magnet answer must be an object.')
  const why = 'The magnet answer has a field of the wrong shape.'
  const id = required(root, 'id', 'string', why) as string
  if (!id.startsWith('mg_') || !isPlainId(id.slice(3), MAX_ID)) refuse(why)
  const state = required(root, 'state', 'string', why) as string
  if (!STATES.has(state)) refuse('The magnet answer has an unknown state.')
  const reasonText = field(root, 'reason', 'string', 'The magnet answer has a reason of the wrong shape.') as string | undefined
  if (reasonText !== undefined && !(reasonText.length > 0 && reasonText.length <= MAX_REASON && /^[a-z_]+$/.test(reasonText))) {
    refuse('The magnet answer has a reason of the wrong shape.')
  }
  const nameText = field(root, 'name', 'string', why) as string | undefined
  const progress = field(root, 'progress', 'number', why) as number | undefined
  if (progress !== undefined && !(Number.isFinite(progress) && progress >= 0 && progress <= 1)) refuse(why)
  const request: MagnetRequest = {
    id,
    state: state as MagnetState,
    reason: reasonText ?? null,
    name: nameText === undefined ? '' : displayText(nameText, MAX_NAME),
    sizeBytes: wholeSize(root, 'sizeBytes', why),
    progress: progress ?? 0,
    files: [],
    identified: readIdentity(root),
  }
  if (request.state !== 'ready') return request
  const files = required(root, 'files', 'array', 'A ready magnet answer has no files.') as unknown[]
  if (files.length === 0 || files.length > MAX_FILES) refuse('A ready magnet answer has no files, or too many.')
  const ids = new Set<string>()
  for (const item of files) {
    const file = readFile(item, serverUrl)
    if (ids.has(file.id)) refuse('The magnet answer lists one file twice.')
    ids.add(file.id)
    request.files.push(file)
  }
  return request
}

/** A magnet request id as the server mints them, checked before it goes into a path. */
export function isMagnetId(id: string): boolean {
  return id.startsWith('mg_') && isPlainId(id.slice(3), MAX_ID)
}
