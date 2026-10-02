import { DEFAULT_ADDON_URLS, DeviceBackend, HaloClient, type HaloBackend } from '@halo/core'
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
import { nativeDeviceStore, type DeviceProfile } from './device/deviceStore'

/**
 * All API traffic goes through the shell's native fetch (tauri-plugin-http →
 * reqwest), never the webview's — that's what lets the app talk to any
 * self-hosted server without a CORS allowlist deploy, and it matches how
 * subtitle hashing must fetch stream bytes anyway. Without an account the
 * same fetch reaches the addons directly.
 *
 * Screens talk to one `HaloBackend`: the account's `HaloClient` when signed
 * in, or a `DeviceBackend` that keeps everything on this PC when Halo is used
 * without an account.
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

/** The signed-in account's client; null without an account. */
let client: HaloClient | null = null
/** What every screen asks: the account's client, or this PC's backend. */
let backend: HaloBackend | null = null
let sessionKind: SessionKind | null = null
/** Set while Halo is used without an account on this PC. */
let deviceProfile: DeviceProfile | null = null
let unauthorizedHandler: (() => void) | null = null

// Dev-only hook so scripts/cdp.mjs can exercise the API without OS input
// (same precedent as nav.tsx's __haloNav).
if (import.meta.env.DEV) {
  Object.defineProperty(window, '__haloClient', {
    get: () => backend,
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
      backend = null
      sessionKind = null
      unauthorizedHandler?.()
    },
  })
  backend = client
  return kind
}

/** Answers every screen from this PC, for Halo without an account. */
export function activateDevice(profile: DeviceProfile): void {
  client = null
  sessionKind = null
  deviceProfile = profile
  backend = new DeviceBackend({ fetch: nativeFetch, store: nativeDeviceStore })
}

/** Holds on to a profile whose data still has to move into the signed-in account, without using it. */
export function rememberDeviceProfile(profile: DeviceProfile): void {
  deviceProfile = profile
}

/** The profile of Halo without an account, while that mode is in use or its data awaits a move into an account. */
export function getDeviceProfile(): DeviceProfile | null {
  return deviceProfile
}

/** Called once the device data has moved into an account and been cleared. */
export function forgetDeviceProfile(): void {
  deviceProfile = null
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
  backend = null
  sessionKind = null
}

/** The backend every screen uses: the account's, or this PC's without one. */
export function getClient(): HaloBackend {
  if (!backend) throw new Error('No backend: Halo is neither signed in nor used without an account')
  return backend
}

/** Account-only calls (who is signed in, the admin's global addons); null without an account. */
export function getAccountClient(): HaloClient | null {
  return client
}

/** First boot of an account or of this PC: install Cinemeta + OpenSubtitles so the app isn't empty. */
export async function seedDefaultAddons(): Promise<void> {
  try {
    const existing = await getClient().getAddons()
    if (existing.global.length + existing.user.length > 0) return
    await getClient().putAddons([...DEFAULT_ADDON_URLS])
  } catch {
    // Best-effort seed — first login must still succeed if a default is down.
  }
}
