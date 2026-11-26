import { isActionStream, withoutActionEntries, withoutActionStreams } from '../addon/actionEntries'
import type { CatalogResponse, MetaResponse } from '../addon/types'
import { isMagnetId, readMagnet, type MagnetRequest } from '../magnets/readers'
import {
  NO_CAPABILITIES,
  readCapabilities,
  readIdLookup,
  readPeopleSearch,
  readPerson,
  type Capabilities,
  type IdLookup,
  type PeopleSearch,
  type PersonDetail,
} from '../people/readers'
import { normalizeRankingInput, type StreamRankingInput } from '../sources/ranking'
import type {
  AddonEntry,
  AddonsResponse,
  LibraryItem,
  Me,
  NextEpisodeResult,
  SettingsPayload,
  StreamsResult,
  SubtitlesResult,
  UserSettings,
  WatchState,
} from './types'

/** The server's sentence when an account's plan refuses something; screens print these messages as they are. */
function planRefusalText(body: string): string | undefined {
  try {
    const parsed: unknown = JSON.parse(body)
    if (typeof parsed !== 'object' || parsed === null) return undefined
    const { code, error } = parsed as { code?: unknown; error?: unknown }
    if (code !== 'plan_required' && code !== 'plan_limit_reached') return undefined
    return typeof error === 'string' && error ? error : undefined
  } catch {
    return undefined
  }
}
type HttpMethod = 'GET' | 'PUT' | 'POST' | 'PATCH' | 'DELETE'

export class HaloApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'HaloApiError'
  }
}

/** OIDC deployment: the app authenticates against this IdP in the system browser. */
export interface OidcAuthConfig {
  mode: 'oidc'
  issuer: string
  clientId: string
  scopes: string[]
}

/** Local-accounts deployment: the app shows a username/password form and posts to /auth/login. */
export interface LocalAuthConfig {
  mode: 'local'
  /** True when a TV can sign in with a code approved on a phone (/auth/device). */
  deviceSignIn?: boolean
}

/** How the server at /auth/config says it authenticates. */
export type AuthConfig = OidcAuthConfig | LocalAuthConfig

/**
 * A sign-in mode this library does not know. An app may handle it with a
 * sign-in add-on; its fields are whatever the server sends for that mode, so
 * they are untrusted until the add-on has checked them.
 */
export interface OtherAuthConfig {
  mode: string
  [field: string]: unknown
}

/** Session token minted by a local-mode server (login and refresh both return this). */
export interface LocalSessionToken {
  token: string
  /** Epoch ms when the token expires — schedule the sliding refresh off this. */
  expiresAt: number
}

/** Per-call options for the resolution methods; callers own timeout policy via the signal. */
export interface RequestOptions {
  signal?: AbortSignal
}

export interface HaloClientOptions {
  baseUrl: string
  /**
   * Current access token, or null when signed out (requests then go out
   * unauthenticated). The provider owns expiry/refresh policy; the client
   * only asks.
   */
  getAccessToken?: () => Promise<string | null>
  /**
   * Called once after a 401 to obtain a fresh token for a single retry.
   * Return null when the session is truly dead (refresh token rejected).
   */
  refreshAccessToken?: () => Promise<string | null>
  /**
   * Called when the session is definitively over: the refresh was rejected, or
   * there is no way to refresh. The app should sign out. A 401 that survives a
   * successful refresh does not call it (see `request`).
   */
  onUnauthorized?: () => void
  fetch?: typeof fetch
}

/**
 * Typed client for the Halo API. Tokens are supplied per-request by the app's
 * token provider; the client holds no auth state of its own.
 */
export class HaloClient {
  readonly baseUrl: string
  private readonly getAccessToken?: () => Promise<string | null>
  private readonly refreshAccessToken?: () => Promise<string | null>
  private readonly onUnauthorized?: () => void
  private readonly doFetch: typeof fetch

  constructor(opts: HaloClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/$/, '')
    this.getAccessToken = opts.getAccessToken
    this.refreshAccessToken = opts.refreshAccessToken
    this.onUnauthorized = opts.onUnauthorized
    this.doFetch = opts.fetch ?? fetch
  }

  private async request<T>(
    method: HttpMethod,
    path: string,
    body?: unknown,
    opts?: RequestOptions,
  ): Promise<T> {
    const res = await this.send(method, path, body, opts)
    return (await res.json()) as T
  }

  /** One authenticated request with the 401 policy below; resolves only on a 2xx. */
  private async send(
    method: HttpMethod,
    path: string,
    body?: unknown,
    opts?: RequestOptions,
    retried = false,
  ): Promise<Response> {
    const token = (await this.getAccessToken?.()) ?? null
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    const res = await this.doFetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: opts?.signal,
    })

    if (res.status === 401) {
      // The refresh succeeded, so the session is alive: the server can briefly
      // refuse a token the identity provider has just issued (after a
      // signing-key rotation, until its cached key set refreshes). Fail this
      // request and keep the session; the next request tries again.
      if (retried) throw new HaloApiError(401, 'Unauthorized')
      // One refresh-and-retry: a 401 with a live refresh token usually just
      // means the access token aged out between the provider's expiry check
      // and the server's.
      if (this.refreshAccessToken && (await this.refreshAccessToken())) {
        return this.send(method, path, body, opts, true)
      }
      this.onUnauthorized?.()
      throw new HaloApiError(401, 'Unauthorized')
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new HaloApiError(res.status, planRefusalText(text) ?? (text || `Request failed: ${method} ${path}`))
    }
    return res
  }

  /** Public endpoint: which IdP to authenticate against and as which client, or a mode only an add-on knows. */
  getAuthConfig(): Promise<AuthConfig | OtherAuthConfig> {
    return this.request<AuthConfig | OtherAuthConfig>('GET', '/auth/config')
  }

  /** The authenticated user incl. admin status; drives admin-only UI. */
  getMe(): Promise<Me> {
    return this.request<Me>('GET', '/auth/me')
  }

  /**
   * The account's profile picture, or null when it has none (or the server
   * keeps no pictures). Fetch it again only when `Me.avatarVersion` changes.
   */
  async getAvatar(opts?: RequestOptions): Promise<Blob | null> {
    try {
      return await (await this.send('GET', '/auth/avatar', undefined, opts)).blob()
    } catch (error) {
      if (error instanceof HaloApiError && error.status === 404) return null
      throw error
    }
  }

  /** Global (admin-managed) addons plus the caller's own, each ordered by position. */
  getAddons(): Promise<AddonsResponse> {
    return this.request<AddonsResponse>('GET', '/addons')
  }

  /**
   * Declares the caller's own addon list (array order = priority). Only
   * transport URLs are sent — the server fetches and validates each manifest
   * itself, and applies the list as a diff so unchanged entries keep their ids.
   */
  putAddons(transportUrls: string[]): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('PUT', '/addons', transportUrls)
  }

  /** Admin-only: declares the global addon list shown to every user. Same diff contract as putAddons. */
  putGlobalAddons(transportUrls: string[]): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('PUT', '/addons/global', transportUrls)
  }

  /** Per-addon knobs on the caller's own entries (currently catalog visibility). */
  patchAddon(addonId: string, patch: { hideCatalogs: boolean }): Promise<void> {
    return this.request<void>('PATCH', `/addons/${encodeURIComponent(addonId)}`, patch)
  }

  /** Admin-only: per-addon knobs on a global entry — applies to every user. */
  patchGlobalAddon(addonId: string, patch: { hideCatalogs: boolean }): Promise<void> {
    return this.request<void>('PATCH', `/addons/global/${encodeURIComponent(addonId)}`, patch)
  }

  /**
   * Server-side addon resolution. These hit the API's resolution endpoints
   * (which walk the caller's effective addon set) rather than talking to addons
   * directly, so the app doesn't need per-addon URLs or CORS handling.
   */
  /** `addonId` is the opaque `AddonEntry.id` — clients never address addons by transport URL. */
  async getCatalog(addonId: string, type: string, id: string, extra?: Record<string, string>, opts?: RequestOptions): Promise<CatalogResponse> {
    const qs = new URLSearchParams({ addon: addonId, type, id, ...(extra ?? {}) })
    const response = await this.request<CatalogResponse>('GET', `/catalog?${qs.toString()}`, undefined, opts)
    // Filtered here too: the server may be one that passes addon actions through.
    return { ...response, metas: withoutActionEntries(response.metas ?? []) }
  }

  /** First effective addon that can describe this type/id wins; 404 if none. */
  getMeta(type: string, id: string, opts?: RequestOptions): Promise<MetaResponse> {
    const qs = new URLSearchParams({ type, id })
    return this.request<MetaResponse>('GET', `/meta?${qs.toString()}`, undefined, opts)
  }

  /**
   * Fans out to every effective addon; playable streams grouped by addon. The
   * ranking input describes this device and title, which the server ranks the
   * streams for (`halo` on each stream). Unknown values are left out, and
   * the server reads a missing one as unknown.
   */
  async getStreams(
    type: string,
    videoId: string,
    ranking?: Partial<StreamRankingInput>,
    opts?: RequestOptions,
  ): Promise<StreamsResult> {
    const qs = new URLSearchParams({ type, videoId })
    const device = normalizeRankingInput(ranking)
    if (device.lineMbps > 0) qs.set('lineMbps', String(device.lineMbps))
    if (device.screenHeight > 0) qs.set('screenHeight', String(device.screenHeight))
    if (device.runtimeSeconds > 0) qs.set('runtimeSeconds', String(device.runtimeSeconds))
    const response = await this.request<StreamsResult>('GET', `/streams?${qs.toString()}`, undefined, opts)
    return {
      ...response,
      results: response.results
        .map((group) => ({ ...group, streams: withoutActionStreams(group.streams) }))
        .filter((group) => group.streams.length > 0),
    }
  }

  /**
   * The episode after `videoId` in `metaId`'s ordering plus, when the addon
   * that served the current stream still exists, the next episode's stream
   * with the same bingeGroup (Stremio's binge-continuation rule: exact group
   * equality, same addon only).
   */
  async getNextEpisode(
    params: { type: string; metaId: string; videoId: string; addonId?: string; bingeGroup?: string },
    opts?: RequestOptions,
  ): Promise<NextEpisodeResult> {
    const qs = new URLSearchParams({ type: params.type, metaId: params.metaId, videoId: params.videoId })
    if (params.addonId) qs.set('addon', params.addonId)
    if (params.bingeGroup) qs.set('bingeGroup', params.bingeGroup)
    const response = await this.request<NextEpisodeResult>('GET', `/next-episode?${qs.toString()}`, undefined, opts)
    return response.stream && isActionStream(response.stream) ? { ...response, stream: null } : response
  }

  getSubtitles(
    type: string,
    videoId: string,
    extra?: { videoHash?: string; videoSize?: number; filename?: string },
    opts?: RequestOptions,
  ): Promise<SubtitlesResult> {
    const qs = new URLSearchParams({ type, videoId })
    if (extra?.videoHash) qs.set('videoHash', extra.videoHash)
    if (extra?.videoSize !== undefined) qs.set('videoSize', String(extra.videoSize))
    if (extra?.filename) qs.set('filename', extra.filename)
    return this.request<SubtitlesResult>('GET', `/subtitles?${qs.toString()}`, undefined, opts)
  }

  getLibrary(): Promise<LibraryItem[]> {
    return this.request<LibraryItem[]>('GET', '/library')
  }

  putLibrary(items: LibraryItem[]): Promise<LibraryItem[]> {
    return this.request<LibraryItem[]>('PUT', '/library', items)
  }

  getWatchStates(): Promise<WatchState[]> {
    return this.request<WatchState[]>('GET', '/watch-state')
  }

  /** Batched upsert; the server applies each entry last-write-wins. */
  putWatchStates(states: WatchState[]): Promise<WatchState[]> {
    return this.request<WatchState[]>('PUT', '/watch-state', states)
  }

  getSettings(): Promise<SettingsPayload> {
    return this.request<SettingsPayload>('GET', '/settings')
  }

  /** LWW like watch-state: the server keeps the newest updatedAt. */
  putSettings(value: UserSettings, updatedAt: number): Promise<SettingsPayload> {
    return this.request<SettingsPayload>('PUT', '/settings', { value, updatedAt })
  }

  /**
   * What this server offers beyond the shared contract. A server that has no
   * such route (404) offers none of it, which is a definitive answer; any
   * other failure throws, so the caller treats everything as off for now and
   * asks again later.
   */
  async getCapabilities(opts?: RequestOptions): Promise<Capabilities> {
    try {
      return readCapabilities(await this.request<unknown>('GET', '/capabilities', undefined, opts))
    } catch (error) {
      if (error instanceof HaloApiError && error.status === 404) return { ...NO_CAPABILITIES }
      throw error
    }
  }

  /** People named so (or, for a misspelling, the ones it most likely means). The query is 2 to 120 characters once trimmed. */
  async searchPeople(query: string, opts?: RequestOptions): Promise<PeopleSearch> {
    const q = query.trim()
    if (q.length < 2 || q.length > 120) throw new HaloApiError(400, 'q must be 2 to 120 characters')
    const qs = new URLSearchParams({ q })
    return readPeopleSearch(await this.request<unknown>('GET', `/people/search?${qs.toString()}`, undefined, opts))
  }

  /** A person page: who they are and their films and series. */
  async getPerson(tmdbId: number, opts?: RequestOptions): Promise<PersonDetail> {
    if (!Number.isSafeInteger(tmdbId) || tmdbId <= 0) throw new HaloApiError(400, 'tmdbId must be a positive whole number')
    return readPerson(await this.request<unknown>('GET', `/people/${tmdbId}`, undefined, opts))
  }

  /** A pasted IMDb or TVDB link, as the title (by IMDb id) or person it names. */
  async lookup(source: 'imdb' | 'tvdb', id: string, opts?: RequestOptions): Promise<IdLookup> {
    if (source === 'tvdb' ? !/^\d{1,10}$/.test(id) : !/^(?:tt|nm)\d{1,10}$/.test(id)) {
      throw new HaloApiError(400, `not a ${source} id`)
    }
    const qs = new URLSearchParams({ source, id })
    return readIdLookup(await this.request<unknown>('GET', `/lookup?${qs.toString()}`, undefined, opts))
  }

  /**
   * Hands a pasted magnet to the server: only its info hash (and its name,
   * for showing) leave the app, never a tracker. Play links in the answer are
   * rebuilt on this client's own server.
   */
  async addMagnet(infoHash: string, name: string | null, opts?: RequestOptions): Promise<MagnetRequest> {
    if (!/^[0-9a-f]{40}$/.test(infoHash)) throw new HaloApiError(400, 'infoHash must be 40 lower-case hex characters')
    const body = name ? { infoHash, name } : { infoHash }
    return readMagnet(await this.request<unknown>('POST', '/magnets', body, opts), this.baseUrl)
  }

  async getMagnet(id: string, opts?: RequestOptions): Promise<MagnetRequest> {
    if (!isMagnetId(id)) throw new HaloApiError(400, 'not a magnet request id')
    return readMagnet(await this.request<unknown>('GET', `/magnets/${id}`, undefined, opts), this.baseUrl)
  }

  /** Stops and forgets a magnet request (a download under way is cancelled). */
  async forgetMagnet(id: string, opts?: RequestOptions): Promise<void> {
    if (!isMagnetId(id)) throw new HaloApiError(400, 'not a magnet request id')
    await this.send('DELETE', `/magnets/${id}`, undefined, opts)
  }

  /**
   * URL that fetches `target` through the API's CORS proxy. The server does not
   * origin-allowlist; it authenticates the request and rejects targets that
   * resolve to private/reserved IPs, re-validating every redirect hop.
   */
  proxyUrl(target: string): string {
    return `${this.baseUrl}/addon-proxy?url=${encodeURIComponent(target)}`
  }
}
