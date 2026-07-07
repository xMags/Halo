import type { AddonEntry, LibraryItem, WatchState } from './types'

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

  async login(password: string): Promise<string> {
    const { token } = await this.request<{ token: string }>('POST', '/auth/login', { password })
    this.token = token
    return token
  }

  getAddons(): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('GET', '/addons')
  }

  putAddons(addons: AddonEntry[]): Promise<AddonEntry[]> {
    return this.request<AddonEntry[]>('PUT', '/addons', addons)
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

  /**
   * URL that fetches `target` through the API's CORS proxy. Only origins of
   * registered addons are allowed server-side.
   */
  proxyUrl(target: string): string {
    return `${this.baseUrl}/addon-proxy?url=${encodeURIComponent(target)}`
  }
}
