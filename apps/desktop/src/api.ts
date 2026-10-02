import { DEFAULT_ADDON_URLS, HaloClient } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { parseServerUrl } from './serverUrl'
import {
  clearLocalSession,
  getLocalAccessToken,
  getLocalDownloadOwner,
  loadLocalSession,
  refreshLocalToken,
  setLocalDownloadOwner,
} from './auth/localAuth'
import {
  clearOidcSession,
  getOidcAccessToken,
  getOidcDownloadOwner,
  loadOidcSession,
  refreshOidcToken,
  setOidcDownloadOwner,
} from './auth/oidc'
import { installedSignInAddons, signInAddonFor } from './auth/installedAddons'
import type { SignInAddon } from './auth/signInAddons'

/**
 * All API traffic goes through the shell's native fetch (tauri-plugin-http →
 * reqwest), never the webview's — that's what lets the app talk to any
 * self-hosted server without a CORS allowlist deploy, and it matches how
 * subtitle hashing must fetch stream bytes anyway.
 */

/** The server this build signs in to, fixed at build time (see serverUrl.ts). */
const SERVER_URL = parseServerUrl(import.meta.env.VITE_HALO_SERVER_URL)

/** Which server the persisted session was issued for. */
const SESSION_SERVER_KEY = 'halo.serverUrl'

/**
 * Which auth flavor the active session uses; drives token providers and
 * sign-out. A sign-in add-on's sessions are `addon:<its mode>`.
 */
export type SessionKind = 'oidc' | 'local' | `addon:${string}`

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

export function getServerUrl(): string {
  return SERVER_URL
}

/**
 * A session persisted by a build for another server (the same machine ran a
 * build for a different one) must never reach this server. It is wiped
 * locally only: signing out of the other server is not this build's call.
 */
function forgetSessionsFromAnotherServer(): void {
  if (localStorage.getItem(SESSION_SERVER_KEY) === SERVER_URL) return
  clearOidcSession()
  clearLocalSession()
  for (const addon of installedSignInAddons) addon.clear()
  localStorage.setItem(SESSION_SERVER_KEY, SERVER_URL)
}

/** The app-level sign-out reaction; session.tsx installs it. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

interface SessionProvider {
  get: () => Promise<string | null>
  refresh: () => Promise<string | null>
  clear: () => void
  getDownloadOwner: () => string | null
  setDownloadOwner: (downloadOwner: string) => void
}

const builtInProviders: Record<'oidc' | 'local', SessionProvider> = {
  oidc: {
    get: getOidcAccessToken,
    refresh: refreshOidcToken,
    clear: clearOidcSession,
    getDownloadOwner: getOidcDownloadOwner,
    setDownloadOwner: setOidcDownloadOwner,
  },
  local: {
    get: getLocalAccessToken,
    refresh: refreshLocalToken,
    clear: clearLocalSession,
    getDownloadOwner: getLocalDownloadOwner,
    setDownloadOwner: setLocalDownloadOwner,
  },
}

/** The session kind a sign-in add-on's sessions run under. */
export function addonSessionKind(addon: SignInAddon): SessionKind {
  return `addon:${addon.mode}`
}

/** The add-on behind an `addon:` session kind, if this build has it. */
export function sessionAddon(kind: SessionKind | null): SignInAddon | undefined {
  return kind?.startsWith('addon:') ? signInAddonFor(kind.slice('addon:'.length)) : undefined
}

function providerFor(kind: SessionKind): SessionProvider {
  if (kind === 'oidc' || kind === 'local') return builtInProviders[kind]
  const addon = sessionAddon(kind)
  if (!addon) throw new Error(`This build has no sign-in add-on for ${kind}`)
  return {
    get: () => addon.getAccessToken(),
    refresh: () => addon.refreshAccessToken(),
    clear: () => addon.clear(),
    getDownloadOwner: () => addon.getDownloadOwner(),
    setDownloadOwner: (downloadOwner) => addon.setDownloadOwner(downloadOwner),
  }
}

/**
 * Restores a persisted session of any kind and binds the client to it.
 * Returns the kind, or null when a fresh login is needed.
 */
export function restoreSession(): SessionKind | null {
  forgetSessionsFromAnotherServer()
  if (loadOidcSession()) return activateSession('oidc')
  if (loadLocalSession()) return activateSession('local')
  const addon = installedSignInAddons.find((candidate) => candidate.restore())
  if (addon) return activateSession(addonSessionKind(addon))
  return null
}

/** Called after a successful sign-in of the given kind (session already persisted). */
export function activateSession(kind: SessionKind): SessionKind {
  const tokens = providerFor(kind)
  sessionKind = kind
  client = new HaloClient({
    baseUrl: SERVER_URL,
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

/** The active session's opaque downloads partition, if one was derived yet. */
export function getSessionDownloadOwner(): string | null {
  return sessionKind ? providerFor(sessionKind).getDownloadOwner() : null
}

export function setSessionDownloadOwner(downloadOwner: string): void {
  if (sessionKind) providerFor(sessionKind).setDownloadOwner(downloadOwner)
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
