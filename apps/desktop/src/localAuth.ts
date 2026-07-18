import type { LocalSessionToken } from '@halo/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'

/**
 * Local-mode session management — port of apps/mobile/src/localAuth.ts.
 * Differences from mobile: persistence is localStorage (WebView2 profile in
 * the app's data dir) instead of the OS keychain — plaintext at rest, accepted
 * for v1 since the machine account is the trust boundary on desktop.
 */
const LOCAL_KEY = 'halo.localSession'
/** Refresh this many ms before nominal expiry so in-flight requests don't race it. */
const EXPIRY_MARGIN_MS = 60_000
/**
 * Proactive sliding-refresh threshold. Tokens live 30 days; renewing whenever
 * the app is used inside the final 15 keeps active devices signed in forever
 * (up to the server's absolute cap) without refreshing on every request.
 */
const PROACTIVE_REFRESH_MS = 15 * 86400_000

interface LocalSession {
  /** The Halo server IS the token issuer in local mode; refresh posts back to it. */
  serverUrl: string
  token: string
  /** Epoch ms when the token expires. */
  expiresAt: number
}

let session: LocalSession | null = null
let refreshInFlight: Promise<string | null> | null = null

function persist(): void {
  localStorage.setItem(LOCAL_KEY, JSON.stringify(session))
}

/** Loads a persisted local session into memory. Returns true when one exists. */
export function loadLocalSession(): boolean {
  const raw = localStorage.getItem(LOCAL_KEY)
  session = raw ? (JSON.parse(raw) as LocalSession) : null
  return session !== null
}

export function clearLocalSession(): void {
  session = null
  localStorage.removeItem(LOCAL_KEY)
}

/** Auth error from the server (as opposed to a network failure). */
class LocalAuthError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'LocalAuthError'
  }
}

async function postJson(url: string, body: Record<string, string>, token?: string): Promise<LocalSessionToken> {
  const res = await nativeFetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  const parsed = (await res.json().catch(() => null)) as (LocalSessionToken & { error?: string }) | null
  if (!res.ok || !parsed?.token) {
    throw new LocalAuthError(res.status, parsed?.error ?? `Sign-in failed (HTTP ${res.status})`)
  }
  return parsed
}

/** Username/password sign-in against a local-mode Halo server. */
export async function signInWithPassword(serverUrl: string, username: string, password: string): Promise<void> {
  const tokens = await postJson(`${serverUrl}/auth/login`, { username, password })
  session = { serverUrl, token: tokens.token, expiresAt: tokens.expiresAt }
  persist()
}

/**
 * Current access token. Inside the proactive window the refresh is
 * best-effort — the current token is still valid, so a network failure just
 * returns it. Past expiry the refresh is mandatory and failures propagate.
 */
export async function getLocalAccessToken(): Promise<string | null> {
  if (!session) return null
  const remaining = session.expiresAt - Date.now()
  if (remaining <= EXPIRY_MARGIN_MS) return refreshLocalToken()
  if (remaining <= PROACTIVE_REFRESH_MS) {
    try {
      return (await refreshLocalToken()) ?? session.token
    } catch {
      return session.token
    }
  }
  return session.token
}

/**
 * Forces a refresh; concurrent callers share one in-flight request. Returns
 * null only when the server definitively rejected the session (expired past
 * the absolute cap, or the user was deleted) — a network error throws instead,
 * so an offline blip never signs the user out.
 */
export function refreshLocalToken(): Promise<string | null> {
  if (!session) return Promise.resolve(null)
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

async function doRefresh(): Promise<string | null> {
  const current = session!
  try {
    const tokens = await postJson(`${current.serverUrl}/auth/refresh`, {}, current.token)
    session = { serverUrl: current.serverUrl, token: tokens.token, expiresAt: tokens.expiresAt }
    persist()
    return session.token
  } catch (err) {
    // A 401 from the refresh endpoint is the local-mode invalid_grant: the
    // session is dead everywhere, not just flaky here.
    if (err instanceof LocalAuthError && err.status === 401) {
      clearLocalSession()
      return null
    }
    throw err
  }
}

/**
 * Local sign-out is purely client-side: tokens are stateless, so there is
 * nothing to revoke — the server-side kill switch is deleting the user.
 */
export function signOutLocal(): void {
  clearLocalSession()
}
