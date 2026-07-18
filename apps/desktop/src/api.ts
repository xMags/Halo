import { HaloClient } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { getLocalAccessToken, refreshLocalToken } from './localAuth'

/**
 * All API traffic goes through the shell's native fetch (tauri-plugin-http →
 * reqwest), never the webview's — that's what lets the app talk to any
 * self-hosted server without a CORS allowlist deploy, and it matches how the
 * native side must fetch stream bytes for subtitle hashing anyway.
 */
const SERVER_KEY = 'halo.serverUrl'

let client: HaloClient | null = null
let unauthorizedHandler: (() => void) | null = null

export function getServerUrl(): string | null {
  return localStorage.getItem(SERVER_KEY)
}

export function setServerUrl(url: string): void {
  localStorage.setItem(SERVER_KEY, url.replace(/\/$/, ''))
  client = null
}

export function clearServerUrl(): void {
  localStorage.removeItem(SERVER_KEY)
  client = null
}

/** The app-level sign-out reaction; session.tsx installs it. */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler
}

export function getClient(): HaloClient {
  const serverUrl = getServerUrl()
  if (!serverUrl) throw new Error('No server configured')
  client ??= new HaloClient({
    baseUrl: serverUrl,
    fetch: nativeFetch,
    getAccessToken: getLocalAccessToken,
    refreshAccessToken: refreshLocalToken,
    onUnauthorized: () => unauthorizedHandler?.(),
  })
  return client
}
