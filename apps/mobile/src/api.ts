import AsyncStorage from '@react-native-async-storage/async-storage'
import * as SecureStore from 'expo-secure-store'
import { DEFAULT_ADDON_URLS, HaloClient } from '@halo/core'

const SERVER_URL_KEY = 'halo.serverUrl'
const TOKEN_KEY = 'halo.token'

// Local dev overrides via EXPO_PUBLIC_API_URL (e.g. http://localhost:8787 on
// the simulator) or by typing the address on the login screen.
export const DEFAULT_SERVER_URL = process.env.EXPO_PUBLIC_API_URL ?? 'https://halo.ditto.moe'

let client: HaloClient | null = null
let onSessionExpired: (() => void) | null = null

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

function createClient(baseUrl: string): HaloClient {
  return new HaloClient({
    baseUrl,
    onUnauthorized: () => {
      void SecureStore.deleteItemAsync(TOKEN_KEY)
      client?.setToken(null)
      onSessionExpired?.()
    },
  })
}

/** Restores a persisted session. Returns null when login is needed. */
export async function restoreSession(): Promise<HaloClient | null> {
  const [serverUrl, token] = await Promise.all([
    AsyncStorage.getItem(SERVER_URL_KEY),
    SecureStore.getItemAsync(TOKEN_KEY),
  ])
  if (!serverUrl || !token) return null
  client = createClient(serverUrl)
  client.setToken(token)
  return client
}

export async function login(serverUrl: string, username: string, password: string): Promise<HaloClient> {
  const normalized = serverUrl.replace(/\/$/, '')
  const fresh = createClient(normalized)
  await fresh.login(username, password)
  await Promise.all([
    AsyncStorage.setItem(SERVER_URL_KEY, normalized),
    SecureStore.setItemAsync(TOKEN_KEY, fresh.getToken()!),
  ])
  client = fresh
  await seedDefaultAddons(fresh)
  return fresh
}

export async function logout(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKEN_KEY)
  client?.setToken(null)
  client = null
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
    await c.putAddons(DEFAULT_ADDON_URLS.map((transportUrl, position) => ({ transportUrl, position })))
  } catch {
    // Best-effort seed.
  }
}
