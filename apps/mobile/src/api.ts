import AsyncStorage from '@react-native-async-storage/async-storage'
import * as SecureStore from 'expo-secure-store'
import { DEFAULT_ADDON_URLS, HaloClient } from '@halo/core'
import {
  clearLocalSession,
  getLocalAccessToken,
  loadLocalSession,
  refreshLocalToken,
  signInWithPassword,
  signOutLocal,
} from './localAuth'
import {
  clearOidcSession,
  getAccessToken,
  loadOidcSession,
  refreshAccessToken,
  signInWithOidc,
  signOutOidc,
} from './oidc'

const SERVER_URL_KEY = 'halo.serverUrl'
// Pre-OIDC session JWT; deleted on sight so no dead secret lingers in the keychain.
const LEGACY_TOKEN_KEY = 'halo.token'

// Local dev overrides via EXPO_PUBLIC_API_URL (e.g. http://localhost:8787 on
// the simulator) or by typing the address on the login screen.
export const DEFAULT_SERVER_URL = process.env.EXPO_PUBLIC_API_URL ?? 'https://halo.ditto.moe'

/** Which auth flavor the active session uses; drives token providers and sign-out. */
type SessionKind = 'oidc' | 'local'

let client: HaloClient | null = null
let sessionKind: SessionKind | null = null
let onSessionExpired: (() => void) | null = null

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

/** Last server the user signed into; survives sign-out so the login screen can prefill it. */
export async function getStoredServerUrl(): Promise<string> {
  return (await AsyncStorage.getItem(SERVER_URL_KEY)) ?? DEFAULT_SERVER_URL
}

function createClient(baseUrl: string, kind: SessionKind): HaloClient {
  const tokens =
    kind === 'oidc'
      ? { get: getAccessToken, refresh: refreshAccessToken, clear: clearOidcSession }
      : { get: getLocalAccessToken, refresh: refreshLocalToken, clear: clearLocalSession }
  return new HaloClient({
    baseUrl,
    getAccessToken: tokens.get,
    refreshAccessToken: tokens.refresh,
    onUnauthorized: () => {
      // Only reached after a refresh attempt failed or was impossible — the
      // session is dead, not merely stale.
      void tokens.clear()
      client = null
      sessionKind = null
      onSessionExpired?.()
    },
  })
}

/** Restores a persisted session of either kind. Returns null when login is needed. */
export async function restoreSession(): Promise<HaloClient | null> {
  void SecureStore.deleteItemAsync(LEGACY_TOKEN_KEY)
  const [serverUrl, hasOidc, hasLocal] = await Promise.all([
    AsyncStorage.getItem(SERVER_URL_KEY),
    loadOidcSession(),
    loadLocalSession(),
  ])
  if (!serverUrl || (!hasOidc && !hasLocal)) return null
  sessionKind = hasOidc ? 'oidc' : 'local'
  client = createClient(serverUrl, sessionKind)
  return client
}

/**
 * Starts sign-in against `serverUrl`. The server's /auth/config decides the
 * flow: OIDC runs the browser PKCE dance to completion; a local-mode server
 * needs credentials the UI hasn't collected yet, so the caller shows the
 * username/password form and follows up with `loginLocal`.
 */
export async function login(serverUrl: string): Promise<'ready' | 'credentials-required'> {
  const normalized = serverUrl.replace(/\/$/, '')
  // /auth/config is public — probe with a bare client before any auth exists.
  const authConfig = await new HaloClient({ baseUrl: normalized }).getAuthConfig()
  if (authConfig.mode === 'local') return 'credentials-required'

  await signInWithOidc(authConfig)
  await AsyncStorage.setItem(SERVER_URL_KEY, normalized)
  const fresh = createClient(normalized, 'oidc')
  client = fresh
  sessionKind = 'oidc'
  await seedDefaultAddons(fresh)
  return 'ready'
}

/** Completes sign-in against a local-mode server with collected credentials. */
export async function loginLocal(serverUrl: string, username: string, password: string): Promise<HaloClient> {
  const normalized = serverUrl.replace(/\/$/, '')
  await signInWithPassword(normalized, username, password)
  await AsyncStorage.setItem(SERVER_URL_KEY, normalized)
  const fresh = createClient(normalized, 'local')
  client = fresh
  sessionKind = 'local'
  await seedDefaultAddons(fresh)
  return fresh
}

export async function logout(): Promise<void> {
  if (sessionKind === 'local') await signOutLocal()
  else await signOutOidc()
  client = null
  sessionKind = null
}

/** The configured client; screens behind the auth gate may assume it exists. */
export function api(): HaloClient {
  if (!client) throw new Error('HaloClient not initialized — user is not logged in')
  return client
}

/** First boot: install Cinemeta + OpenSubtitles so the app isn't empty. */
async function seedDefaultAddons(c: HaloClient): Promise<void> {
  const existing = await c.getAddons()
  if (existing.global.length + existing.user.length > 0) return
  try {
    // The server fetches each manifest; a default being down fails the whole
    // set (all-or-nothing), so swallow it — first login must still succeed.
    await c.putAddons([...DEFAULT_ADDON_URLS])
  } catch {
    // Best-effort seed.
  }
}
