import type {
  AddonEntry,
  AddonRef,
  AddonsResponse,
  LibraryItem,
  SettingsPayload,
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
   * URL that fetches `target` through the API's CORS proxy. Only origins of
   * registered addons are allowed server-side.
   */
  proxyUrl(target: string): string {
    return `${this.baseUrl}/addon-proxy?url=${encodeURIComponent(target)}`
  }
}
