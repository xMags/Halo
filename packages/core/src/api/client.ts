import type { CatalogResponse, MetaResponse } from '../addon/types'
import type {
  AddonEntry,
  AddonRef,
  AddonsResponse,
  LibraryItem,
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

export interface HaloClientOptions {
  baseUrl: string
  /** Called on 401 so the app can drop the stored token and show login. */
  onUnauthorized?: () => void
  fetch?: typeof fetch
}

/**
 * Typed client for the Halo API. Token is held by the instance; persistence
 * (localStorage / SecureStore) is the app's concern via get/setToken.
 */
export class HaloClient {
  private token: string | null = null
  private readonly baseUrl: string
  private readonly onUnauthorized?: () => void
  private readonly doFetch: typeof fetch

  constructor(opts: HaloClientOptions) {
    this.baseUrl = opts.baseUrl.replace(/\/$/, '')
    this.onUnauthorized = opts.onUnauthorized
    this.doFetch = opts.fetch ?? fetch
  }

  setToken(token: string | null): void {
    this.token = token
  }

  getToken(): string | null {
    return this.token
  }

  get isAuthenticated(): boolean {
    return this.token !== null
  }

  private async request<T>(method: 'GET' | 'PUT' | 'POST', path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = {}
    if (this.token) headers.Authorization = `Bearer ${this.token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    const res = await this.doFetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

    if (res.status === 401) {
      this.onUnauthorized?.()
      throw new HaloApiError(401, 'Unauthorized')
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new HaloApiError(res.status, text || `Request failed: ${method} ${path}`)
    }
    return (await res.json()) as T
  }

  async login(username: string, password: string): Promise<string> {
    const { token } = await this.request<{ token: string }>('POST', '/auth/login', { username, password })
    this.token = token
    return token
  }

  /** Self-service password change for the authenticated user. */
  changePassword(current: string, next: string): Promise<{ ok: true }> {
    return this.request<{ ok: true }>('POST', '/auth/password', { current, next })
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
  getCatalog(addon: string, type: string, id: string, extra?: Record<string, string>): Promise<CatalogResponse> {
    const qs = new URLSearchParams({ addon, type, id, ...(extra ?? {}) })
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
