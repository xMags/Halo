/**
 * Request headers an addon says its stream needs (`behaviorHints.proxyHeaders
 * .request`: a Referer, an auth header), in the shapes playback uses them.
 *
 * They come from an addon, possibly through a server this app does not
 * control, so they are checked here again before they reach mpv or a fetch:
 * a header name must be a plain token, a value must not break out of its
 * line, and headers that belong to the connection or to the player (which
 * sends its own Range for every seek) are never taken from an addon.
 */

const MAX_HEADERS = 64
const MAX_NAME_LENGTH = 128
const MAX_VALUE_LENGTH = 4096
const HEADER_NAME = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/
const UNSAFE_IN_VALUE = /[\r\n\u0000]/

/** Lowercase names the player or the connection owns. */
const NOT_FROM_ADDONS: ReadonlySet<string> = new Set([
  'host',
  'range',
  'content-length',
  'connection',
  'transfer-encoding',
  'accept-encoding',
])

/** The usable headers, or undefined when there are none. */
export function playbackHeaders(raw: unknown): Record<string, string> | undefined {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return undefined
  const headers: Record<string, string> = {}
  for (const [name, value] of Object.entries(raw).slice(0, MAX_HEADERS)) {
    if (name.length > MAX_NAME_LENGTH || !HEADER_NAME.test(name)) continue
    if (NOT_FROM_ADDONS.has(name.toLowerCase())) continue
    if (typeof value !== 'string' || value.length > MAX_VALUE_LENGTH || UNSAFE_IN_VALUE.test(value)) continue
    headers[name] = value
  }
  return Object.keys(headers).length > 0 ? headers : undefined
}

/** One `Name: value` line per header, as mpv's `http-header-fields` takes them. */
export function headerLines(headers: Record<string, string> | undefined): string[] {
  return Object.entries(playbackHeaders(headers) ?? {}).map(([name, value]) => `${name}: ${value}`)
}

/** `fetch` with the stream's headers on every request; a request's own headers (its Range) win. */
export function fetchWithHeaders(base: typeof fetch, headers: Record<string, string> | undefined): typeof fetch {
  const extra = playbackHeaders(headers)
  if (!extra) return base
  return ((input: RequestInfo | URL, init?: RequestInit) =>
    base(input, { ...init, headers: { ...extra, ...(init?.headers as Record<string, string> | undefined) } })) as typeof fetch
}
