import type {
  CatalogResponse,
  Manifest,
  ManifestResource,
  MetaResponse,
  ResourceName,
  StreamsResponse,
  SubtitlesResponse,
} from './types'

/**
 * Client for the Stremio addon protocol. Addons are plain HTTP servers; every
 * resource is a GET returning JSON:
 *
 *   {transport}/manifest.json
 *   {transport}/{resource}/{type}/{id}.json
 *   {transport}/{resource}/{type}/{id}/{extra}.json
 *
 * where {transport} is the manifest URL minus the trailing /manifest.json and
 * {extra} is querystring-encoded (e.g. "skip=100&genre=Action").
 */

export class AddonRequestError extends Error {
  constructor(
    readonly url: string,
    readonly status: number,
  ) {
    super(`Addon request failed with ${status}: ${url}`)
    this.name = 'AddonRequestError'
  }
}

export interface AddonFetchOptions {
  /** Override fetch, e.g. to route through the API's /addon-proxy. */
  fetch?: typeof fetch
  signal?: AbortSignal
}

/** Strips /manifest.json so both manifest URLs and bare base URLs are accepted. */
export function transportBase(transportUrl: string): string {
  return transportUrl.replace(/\/manifest\.json$/, '').replace(/\/$/, '')
}

async function getJson<T>(url: string, opts: AddonFetchOptions): Promise<T> {
  const doFetch = opts.fetch ?? fetch
  const res = await doFetch(url, { signal: opts.signal })
  if (!res.ok) throw new AddonRequestError(url, res.status)
  return (await res.json()) as T
}

export function encodeExtra(extra: Record<string, string | number>): string {
  return Object.entries(extra)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&')
}

function resourceUrl(
  transportUrl: string,
  resource: ResourceName,
  type: string,
  id: string,
  extra?: Record<string, string | number>,
): string {
  const base = `${transportBase(transportUrl)}/${resource}/${encodeURIComponent(type)}/${encodeURIComponent(id)}`
  const extraStr = extra && Object.keys(extra).length > 0 ? `/${encodeExtra(extra)}` : ''
  return `${base}${extraStr}.json`
}

export function fetchManifest(transportUrl: string, opts: AddonFetchOptions = {}): Promise<Manifest> {
  return getJson<Manifest>(`${transportBase(transportUrl)}/manifest.json`, opts)
}

export function getCatalog(
  transportUrl: string,
  type: string,
  id: string,
  extra?: Record<string, string | number>,
  opts: AddonFetchOptions = {},
): Promise<CatalogResponse> {
  return getJson<CatalogResponse>(resourceUrl(transportUrl, 'catalog', type, id, extra), opts)
}

export function getMeta(
  transportUrl: string,
  type: string,
  id: string,
  opts: AddonFetchOptions = {},
): Promise<MetaResponse> {
  return getJson<MetaResponse>(resourceUrl(transportUrl, 'meta', type, id), opts)
}

export function getStreams(
  transportUrl: string,
  type: string,
  videoId: string,
  opts: AddonFetchOptions = {},
): Promise<StreamsResponse> {
  return getJson<StreamsResponse>(resourceUrl(transportUrl, 'stream', type, videoId), opts)
}

export function getSubtitles(
  transportUrl: string,
  type: string,
  videoId: string,
  extra?: { videoHash?: string; videoSize?: number; filename?: string },
  opts: AddonFetchOptions = {},
): Promise<SubtitlesResponse> {
  const cleanExtra: Record<string, string | number> = {}
  if (extra?.videoHash) cleanExtra.videoHash = extra.videoHash
  if (extra?.videoSize) cleanExtra.videoSize = extra.videoSize
  if (extra?.filename) cleanExtra.filename = extra.filename
  return getJson<SubtitlesResponse>(resourceUrl(transportUrl, 'subtitles', type, videoId, cleanExtra), opts)
}

/**
 * Whether an addon serves a resource for the given type/id. `resources` mixes
 * bare strings (governed by the manifest-level `types`/`idPrefixes`) and
 * per-resource objects with their own constraints.
 */
export function addonSupportsResource(
  manifest: Manifest,
  resource: ResourceName,
  type: string,
  id?: string,
): boolean {
  const matchesPrefixes = (prefixes: string[] | undefined) =>
    !id || !prefixes || prefixes.some((p) => id.startsWith(p))

  for (const entry of manifest.resources) {
    if (typeof entry === 'string') {
      if (entry === resource && manifest.types.includes(type) && matchesPrefixes(manifest.idPrefixes)) {
        return true
      }
    } else if (isResourceForType(entry, resource, type) && matchesPrefixes(entry.idPrefixes ?? manifest.idPrefixes)) {
      return true
    }
  }
  return false
}

function isResourceForType(entry: ManifestResource, resource: ResourceName, type: string): boolean {
  return entry.name === resource && entry.types.includes(type)
}

/** Streams Halo can actually play: direct URLs only (no torrents/yt/external). */
export function isPlayableStream<T extends { url?: string; infoHash?: string }>(
  stream: T,
): stream is T & { url: string } {
  return typeof stream.url === 'string' && /^https?:\/\//.test(stream.url) && !stream.infoHash
}
