import type { CatalogResponse, MetaResponse } from '../addon/types'
import type {
  AddonEntry,
  AddonRef,
  AddonsResponse,
  LibraryItem,
  Me,
  SettingsPayload,
  StreamsResult,
  SubtitlesResult,
  UserSettings,
  WatchState,
} from './types'

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
}

/** How the server at /auth/config says it authenticates. */
export type AuthConfig = OidcAuthConfig | LocalAuthConfig

/** Session token minted by a local-mode server (login and refresh both return this). */
export interface LocalSessionToken {
  token: string
  /** Epoch ms when the token expires — schedule the sliding refresh off this. */
  expiresAt: number
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
  /** Called when a request stays unauthorized after the refresh retry; the app should sign out. */
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

  private async request<T>(method: 'GET' | 'PUT' | 'POST', path: string, body?: unknown, retried = false): Promise<T> {
    const token = (await this.getAccessToken?.()) ?? null
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    const res = await this.doFetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

    if (res.status === 401) {
      // One refresh-and-retry: a 401 with a live refresh token usually just
      // means the access token aged out between the provider's expiry check
      // and the server's.
      if (!retried && this.refreshAccessToken && (await this.refreshAccessToken())) {
        return this.request(method, path, body, true)
      }
      this.onUnauthorized?.()
      throw new HaloApiError(401, 'Unauthorized')
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new HaloApiError(res.status, text || `Request failed: ${method} ${path}`)
    }
    return (await res.json()) as T
  }

  /** Public endpoint: which IdP to authenticate against and as which client. */
  getAuthConfig(): Promise<AuthConfig> {
    return this.request<AuthConfig>('GET', '/auth/config')
  }

  /** The authenticated user incl. admin status; drives admin-only UI. */
  getMe(): Promise<Me> {
    return this.request<Me>('GET', '/auth/me')
  }

  /** Global (admin-managed) addons plus the caller's own, each ordered by position. */
  getAddons(): Promise<AddonsResponse> {
    return this.request<AddonsResponse>('GET', '/addons')
  }

  /**
   * Replaces the caller's own addon list. Only transportUrl + position are sent;
   * the server fetches and validates each manifest itself.
   */
  putAddons(entries: AddonRef[]): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('PUT', '/addons', entries)
  }

  /** Admin-only: replaces the global addon list shown to every user. */
  putGlobalAddons(entries: AddonRef[]): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('PUT', '/addons/global', entries)
  }

  /**
   * Server-side addon resolution. These hit the API's resolution endpoints
   * (which walk the caller's effective addon set) rather than talking to addons
   * directly, so the app doesn't need per-addon URLs or CORS handling.
   */
  /** `addonId` is the opaque `AddonEntry.id` — clients never address addons by transport URL. */
  getCatalog(addonId: string, type: string, id: string, extra?: Record<string, string>): Promise<CatalogResponse> {
    const qs = new URLSearchParams({ addon: addonId, type, id, ...(extra ?? {}) })
    return this.request<CatalogResponse>('GET', `/catalog?${qs.toString()}`)
  }

  /** First effective addon that can describe this type/id wins; 404 if none. */
  getMeta(type: string, id: string): Promise<MetaResponse> {
    const qs = new URLSearchParams({ type, id })
    return this.request<MetaResponse>('GET', `/meta?${qs.toString()}`)
  }

  /** Fans out to every effective addon; playable streams grouped by addon. */
  getStreams(type: string, videoId: string): Promise<StreamsResult> {
    const qs = new URLSearchParams({ type, videoId })
    return this.request<StreamsResult>('GET', `/streams?${qs.toString()}`)
  }

  getSubtitles(
    type: string,
    videoId: string,
    extra?: { videoHash?: string; videoSize?: number; filename?: string },
  ): Promise<SubtitlesResult> {
    const qs = new URLSearchParams({ type, videoId })
    if (extra?.videoHash) qs.set('videoHash', extra.videoHash)
    if (extra?.videoSize !== undefined) qs.set('videoSize', String(extra.videoSize))
    if (extra?.filename) qs.set('filename', extra.filename)
    return this.request<SubtitlesResult>('GET', `/subtitles?${qs.toString()}`)
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
   * URL that fetches `target` through the API's CORS proxy. The server does not
   * origin-allowlist; it authenticates the request and rejects targets that
   * resolve to private/reserved IPs, re-validating every redirect hop.
   */
  proxyUrl(target: string): string {
    return `${this.baseUrl}/addon-proxy?url=${encodeURIComponent(target)}`
  }
}
