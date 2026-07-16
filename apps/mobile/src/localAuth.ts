import * as SecureStore from 'expo-secure-store'
import type { LocalSessionToken } from '@halo/core'

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

async function persist(): Promise<void> {
  await SecureStore.setItemAsync(LOCAL_KEY, JSON.stringify(session))
}

/** Loads a persisted local session into memory. Returns true when one exists. */
export async function loadLocalSession(): Promise<boolean> {
  const raw = await SecureStore.getItemAsync(LOCAL_KEY)
  session = raw ? (JSON.parse(raw) as LocalSession) : null
  return session !== null
}

export async function clearLocalSession(): Promise<void> {
  session = null
  await SecureStore.deleteItemAsync(LOCAL_KEY)
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
  const res = await fetch(url, {
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
  await persist()
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
    await persist()
    return session.token
  } catch (err) {
    // A 401 from the refresh endpoint is the local-mode invalid_grant: the
    // session is dead everywhere, not just flaky here.
    if (err instanceof LocalAuthError && err.status === 401) {
      await clearLocalSession()
      return null
    }
    throw err
  }
}

/**
 * Local sign-out is purely client-side: tokens are stateless, so there is
 * nothing to revoke — the server-side kill switch is deleting the user.
 */
export async function signOutLocal(): Promise<void> {
  await clearLocalSession()
}
