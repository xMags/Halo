import type { AddonError, NextEpisodeResult, StreamsResult, SubtitlesResult } from '../api/types'
import { addonSupportsResource, getCatalog, getMeta, getStreams, getSubtitles, transportBase } from './client'
import { parseManifest } from './manifest'
import { nextVideo } from './nextVideo'
import { addonError, normalizeStreamsResponse, normalizeSubtitlesResponse, safeAddonName } from './normalize'
import type { CatalogResponse, Manifest, MetaResponse, Stream } from './types'

/**
 * Addon resolution: which installed addons to ask, in what order, and how
 * their answers combine. The API server runs this for signed-in users (over
 * its SSRF-guarded fetch) and the on-device backend runs it for people using
 * Halo without an account, so the two cannot drift apart.
 */

/** Per-addon request budget for every resolution call. */
export const RESOLVE_TIMEOUT_MS = 10_000

export const MAX_BINGE_GROUP_LENGTH = 512
const MAX_CATALOG_EXTRAS = 8
const MAX_CATALOG_EXTRA_KEY_LENGTH = 64
const MAX_CATALOG_EXTRA_VALUE_LENGTH = 256
const MAX_SUBTITLE_FILENAME_LENGTH = 1_024

/** An installed addon as resolution needs it: always with its transport URL. */
export interface ResolvableAddon {
  id: string
  transportUrl: string
  manifest: Manifest
}

export type ResolutionRoute = '/streams' | '/subtitles' | '/next-episode'

export interface ResolveContext {
  fetch: typeof fetch
  /** Budget for each addon request. */
  timeoutMs: number
  /** Caller cancellation, combined with each request's own timeout. */
  signal?: AbortSignal
  /** Told about every addon that failed, with its sanitized error. */
  onFailure?: (route: ResolutionRoute, error: AddonError, durationMs: number) => void
}

export interface SubtitleQuery {
  videoHash?: string
  videoSize?: number
  filename?: string
}

/** Why a catalog request's extras are refused, or null when they are fine. */
export function catalogExtraProblem(extra: Record<string, string>): string | null {
  const keys = Object.keys(extra)
  if (keys.length > MAX_CATALOG_EXTRAS) return 'too many extra params'
  if (
    keys.some((key) => key.length > MAX_CATALOG_EXTRA_KEY_LENGTH) ||
    Object.values(extra).some((value) => value.length > MAX_CATALOG_EXTRA_VALUE_LENGTH)
  ) {
    return 'extra param too long'
  }
  return null
}

/** Why a subtitle query is refused, or null when it is fine. */
export function subtitleQueryProblem(query: SubtitleQuery): string | null {
  if (query.videoHash !== undefined && !/^[0-9a-fA-F]{16}$/.test(query.videoHash)) {
    return 'videoHash must be 16 hex chars'
  }
  if (query.videoSize !== undefined && !(Number.isInteger(query.videoSize) && query.videoSize > 0)) {
    return 'videoSize must be a positive integer'
  }
  if (query.filename !== undefined && query.filename.length > MAX_SUBTITLE_FILENAME_LENGTH) return 'filename too long'
  return null
}

/** One catalog from one addon. Throws when the addon fails; the caller decides what that means. */
export function fetchCatalog(
  addon: ResolvableAddon,
  type: string,
  id: string,
  extra: Record<string, string>,
  ctx: ResolveContext,
): Promise<CatalogResponse> {
  return getCatalog(addon.transportUrl, type, id, extra, { fetch: ctx.fetch, signal: requestSignal(ctx) })
}

/** First addon that can describe this type/id wins; null if none can. */
export async function resolveMeta(
  addons: readonly ResolvableAddon[],
  type: string,
  id: string,
  ctx: ResolveContext,
): Promise<MetaResponse | null> {
  for (const addon of addons) {
    if (!addonSupportsResource(addon.manifest, 'meta', type, id)) continue
    try {
      return await getMeta(addon.transportUrl, type, id, { fetch: ctx.fetch, signal: requestSignal(ctx) })
    } catch {
      // Try the next addon that can describe this id.
    }
  }
  return null
}

/** Asks every stream-capable addon at once; playable streams grouped by addon, plus each failure. */
export async function resolveStreams(
  addons: readonly ResolvableAddon[],
  type: string,
  videoId: string,
  ctx: ResolveContext,
): Promise<StreamsResult> {
  const capable = addons.filter((a) => addonSupportsResource(a.manifest, 'stream', type, videoId))
  const startedAt = capable.map(() => Date.now())
  const settled = await Promise.allSettled(
    capable.map(async (a) => {
      const res = await getStreams(a.transportUrl, type, videoId, { fetch: ctx.fetch, signal: requestSignal(ctx) })
      return normalizeStreamsResponse(res)
    }),
  )
  const result: StreamsResult = { results: [], errors: [] }
  settled.forEach((r, i) => {
    const a = capable[i]!
    if (r.status === 'fulfilled') {
      if (r.value.length > 0) result.results.push({ addon: { id: a.id, name: safeAddonName(a.manifest.name) }, streams: r.value })
      return
    }
    const error = addonError(a.id, a.manifest.name, r.reason)
    result.errors.push(error)
    reportFailure(ctx, '/streams', error, Date.now() - startedAt[i]!)
  })
  return result
}

/** Asks every subtitle-capable addon at once. `hashMatched` is true iff a video hash was sent. */
export async function resolveSubtitles(
  addons: readonly ResolvableAddon[],
  type: string,
  videoId: string,
  query: SubtitleQuery,
  ctx: ResolveContext,
): Promise<SubtitlesResult> {
  const capable = addons.filter((a) => addonSupportsResource(a.manifest, 'subtitles', type, videoId))
  const startedAt = capable.map(() => Date.now())
  const settled = await Promise.allSettled(
    capable.map(async (a) => {
      const res = await getSubtitles(a.transportUrl, type, videoId, query, { fetch: ctx.fetch, signal: requestSignal(ctx) })
      return normalizeSubtitlesResponse(res)
    }),
  )
  const result: SubtitlesResult = { results: [], errors: [], hashMatched: query.videoHash !== undefined }
  settled.forEach((r, i) => {
    const a = capable[i]!
    if (r.status === 'fulfilled') {
      result.results.push({ addon: { id: a.id, name: safeAddonName(a.manifest.name) }, subtitles: r.value })
      return
    }
    const error = addonError(a.id, a.manifest.name, r.reason)
    result.errors.push(error)
    reportFailure(ctx, '/subtitles', error, Date.now() - startedAt[i]!)
  })
  return result
}

export interface NextEpisodeQuery {
  type: string
  metaId: string
  videoId: string
  /** The addon that served the current stream. */
  addonId?: string
  /** The current stream's bingeGroup. */
  bingeGroup?: string
}

/**
 * Binge continuation: the episode after `videoId` in `metaId`'s ordering,
 * plus, when the addon that served the current stream is still installed,
 * that addon's stream for the next episode with the same bingeGroup.
 * Matching is Stremio's rule exactly: same addon, exact group equality, no
 * fuzzy tier. `stream: null` means "fall back to the stream picker". Returns
 * null when no addon can describe the title at all.
 */
export async function resolveNextEpisode(
  addons: readonly ResolvableAddon[],
  query: NextEpisodeQuery,
  ctx: ResolveContext,
): Promise<NextEpisodeResult | null> {
  const meta = await resolveMeta(addons, query.type, query.metaId, ctx)
  if (!meta) return null
  const next = nextVideo(meta.meta.videos ?? [], query.videoId)
  if (!next) return { video: null, stream: null }

  // A missing addon id is benign: the addon was uninstalled mid-playback.
  // The next episode is still reported, just without a matched stream.
  const entry = query.addonId ? addons.find((a) => a.id === query.addonId) : undefined
  let stream: Stream | null = null
  if (entry && query.bingeGroup && addonSupportsResource(entry.manifest, 'stream', query.type, next.id)) {
    const startedAt = Date.now()
    try {
      const res = await getStreams(entry.transportUrl, query.type, next.id, { fetch: ctx.fetch, signal: requestSignal(ctx) })
      stream = normalizeStreamsResponse(res).find((s) => s.behaviorHints?.bingeGroup === query.bingeGroup) ?? null
    } catch (reason) {
      // Best-effort: an unreachable addon degrades to the picker, not a failure.
      reportFailure(ctx, '/next-episode', addonError(entry.id, entry.manifest.name, reason), Date.now() - startedAt)
    }
  }
  return { video: next, stream }
}

export interface ResolvedManifest {
  transportUrl: string
  manifest: Manifest
}

/**
 * Fetches and validates the manifest for every URL, all-or-nothing: the
 * entries, or the first URL whose manifest could not be fetched or is
 * invalid. Fetches run concurrently. Without `timeoutMs` a fetch is bounded
 * only by `fetch` itself.
 */
export async function fetchManifests(
  urls: readonly string[],
  opts: { fetch: (url: string, init?: RequestInit) => Promise<Response>; timeoutMs?: number },
): Promise<{ entries: ResolvedManifest[] } | { error: string }> {
  const results = await Promise.all(
    urls.map(async (transportUrl) => {
      try {
        const manifestUrl = `${transportBase(transportUrl)}/manifest.json`
        const res =
          opts.timeoutMs === undefined
            ? await opts.fetch(manifestUrl)
            : await opts.fetch(manifestUrl, { signal: AbortSignal.timeout(opts.timeoutMs) })
        if (!res.ok) return { transportUrl, manifest: null }
        return { transportUrl, manifest: parseManifest(await res.json()) }
      } catch {
        return { transportUrl, manifest: null }
      }
    }),
  )
  const failed = results.find((r) => r.manifest === null)
  if (failed) return { error: `could not fetch a valid manifest for ${failed.transportUrl}` }
  return { entries: results.map((r) => ({ transportUrl: r.transportUrl, manifest: r.manifest! })) }
}

function requestSignal(ctx: ResolveContext): AbortSignal {
  const timeout = AbortSignal.timeout(ctx.timeoutMs)
  return ctx.signal ? AbortSignal.any([ctx.signal, timeout]) : timeout
}

function reportFailure(ctx: ResolveContext, route: ResolutionRoute, error: AddonError, durationMs: number): void {
  try {
    ctx.onFailure?.(route, error, durationMs)
  } catch {
    // Observability must never turn an addon failure into a resolution failure.
  }
}
