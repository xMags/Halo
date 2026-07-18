import { DEFAULT_ADDON_URLS, HaloClient } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { clearLocalSession, getLocalAccessToken, loadLocalSession, refreshLocalToken } from './localAuth'
import { clearOidcSession, getOidcAccessToken, loadOidcSession, refreshOidcToken } from './oidc'

/**
 * All API traffic goes through the shell's native fetch (tauri-plugin-http →
 * reqwest), never the webview's — that's what lets the app talk to any
 * self-hosted server without a CORS allowlist deploy, and it matches how
 * subtitle hashing must fetch stream bytes anyway.
 */
const SERVER_KEY = 'halo.serverUrl'

/** Which auth flavor the active session uses; drives token providers and sign-out. */
export type SessionKind = 'oidc' | 'local'

let client: HaloClient | null = null
let sessionKind: SessionKind | null = null
let unauthorizedHandler: (() => void) | null = null

// Dev-only hook so scripts/cdp.mjs can exercise the API without OS input
// (same precedent as nav.tsx's __haloNav).
if (import.meta.env.DEV) {
  Object.defineProperty(window, '__haloClient', {
    get: () => client,
    configurable: true,
  })
}

export function getServerUrl(): string | null {
  return localStorage.getItem(SERVER_KEY)
}

export function setServerUrl(url: string): void {
  localStorage.setItem(SERVER_KEY, url.replace(/\/$/, ''))
}

export function clearServerUrl(): void {
  localStorage.removeItem(SERVER_KEY)
  client = null
  sessionKind = null
}

/** The app-level sign-out reaction; session.tsx installs it. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

const providers = {
  oidc: { get: getOidcAccessToken, refresh: refreshOidcToken, clear: clearOidcSession },
  local: { get: getLocalAccessToken, refresh: refreshLocalToken, clear: clearLocalSession },
} as const

/**
 * Restores a persisted session of either kind and binds the client to it.
 * Returns the kind, or null when a fresh login is needed.
 */
export function restoreSession(): SessionKind | null {
  if (!getServerUrl()) return null
  if (loadOidcSession()) return activateSession('oidc')
  if (loadLocalSession()) return activateSession('local')
  return null
}

/** Called after a successful sign-in of the given kind (session already persisted). */
export function activateSession(kind: SessionKind): SessionKind {
  const tokens = providers[kind]
  sessionKind = kind
  client = new HaloClient({
    baseUrl: getServerUrl()!,
    fetch: nativeFetch,
    getAccessToken: tokens.get,
    refreshAccessToken: tokens.refresh,
    onUnauthorized: () => {
      // Only reached after a refresh attempt failed or was impossible — the
      // session is dead, not merely stale.
      tokens.clear()
      client = null
      sessionKind = null
      unauthorizedHandler?.()
    },
  })
  return kind
}

export function getSessionKind(): SessionKind | null {
  return sessionKind
}

export function deactivateSession(): void {
  client = null
  sessionKind = null
}

export function getClient(): HaloClient {
  if (!client) throw new Error('HaloClient not initialized — user is not signed in')
  return client
}

/** First boot: install Cinemeta + OpenSubtitles so the app isn't empty. */
export async function seedDefaultAddons(): Promise<void> {
  try {
    const existing = await getClient().getAddons()
    if (existing.global.length + existing.user.length > 0) return
    await getClient().putAddons([...DEFAULT_ADDON_URLS])
  } catch {
    // Best-effort seed — first login must still succeed if a default is down.
  }
}
