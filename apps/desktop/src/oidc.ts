import type { OidcAuthConfig } from '@halo/core'
import { invoke } from '@tauri-apps/api/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { openUrl } from '@tauri-apps/plugin-opener'

/**
 * OIDC session management — desktop port of apps/mobile/src/oidc.ts.
 * The browser dance is RFC 8252 (system browser + loopback redirect): the
 * Rust side listens on a fixed localhost port, the default browser opens the
 * IdP's authorize URL, and the redirect lands on the listener. PKCE with
 * WebCrypto; token/refresh/revoke are hand-rolled form POSTs over the native
 * fetch (same reasoning as mobile: the requests are trivial and nothing may
 * rewrite the endpoint URLs — Authentik's trailing slashes are load-bearing).
 * Persistence is localStorage — same plaintext-at-rest tradeoff as localAuth.
 */
const OIDC_KEY = 'halo.oidc'
/** Refresh this many ms before nominal expiry so in-flight requests don't race it. */
const EXPIRY_MARGIN_MS = 60_000
/** Must match CALLBACK_PORT in src-tauri/src/oauth.rs and the IdP-registered redirect URI. */
const OAUTH_CALLBACK_PORT = 17871
const REDIRECT_URI = `http://127.0.0.1:${OAUTH_CALLBACK_PORT}/callback`

interface OidcSession {
  clientId: string
  // Endpoints are captured at sign-in so refresh/revoke never need the
  // discovery document again.
  tokenEndpoint: string
  revocationEndpoint?: string
  endSessionEndpoint?: string
  accessToken: string
  refreshToken?: string
  /** Kept solely as the end-session `id_token_hint`. */
  idToken?: string
  /** Epoch ms when accessToken expires. */
  expiresAt: number
}

let session: OidcSession | null = null
let refreshInFlight: Promise<string | null> | null = null

function persist(): void {
  localStorage.setItem(OIDC_KEY, JSON.stringify(session))
}

/** Loads persisted tokens into memory. Returns true when a session exists. */
export function loadOidcSession(): boolean {
  const raw = localStorage.getItem(OIDC_KEY)
  session = raw ? (JSON.parse(raw) as OidcSession) : null
  return session !== null
}

export function clearOidcSession(): void {
  session = null
  localStorage.removeItem(OIDC_KEY)
}

/** OAuth error response from the IdP's token endpoint (as opposed to a network failure). */
class TokenEndpointError extends Error {
  constructor(
    readonly code: string,
    description?: string,
  ) {
    super(description ?? code)
    this.name = 'TokenEndpointError'
  }
}

interface TokenEndpointResponse {
  access_token: string
  refresh_token?: string
  id_token?: string
  expires_in?: number
}

async function postForm(url: string, form: Record<string, string>): Promise<TokenEndpointResponse> {
  const res = await nativeFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: Object.entries(form)
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&'),
  })
  const text = await res.text()
  let body: TokenEndpointResponse & { error?: string; error_description?: string }
  try {
    body = JSON.parse(text) as typeof body
  } catch {
    throw new TokenEndpointError(`http_${res.status}`, `IdP returned a non-JSON response (HTTP ${res.status})`)
  }
  if (!res.ok || body.error) throw new TokenEndpointError(body.error ?? `http_${res.status}`, body.error_description)
  return body
}

function applyTokens(
  current: Omit<OidcSession, 'accessToken' | 'refreshToken' | 'expiresAt'> & Partial<OidcSession>,
  tokens: TokenEndpointResponse,
): OidcSession {
  return {
    clientId: current.clientId,
    tokenEndpoint: current.tokenEndpoint,
    revocationEndpoint: current.revocationEndpoint,
    endSessionEndpoint: current.endSessionEndpoint,
    accessToken: tokens.access_token,
    // Authentik rotates refresh tokens; fall back to the old one if the
    // response omits it.
    refreshToken: tokens.refresh_token ?? current.refreshToken,
    idToken: tokens.id_token ?? current.idToken,
    // A missing expires_in counts as already stale, so the next request
    // refreshes instead of trusting an unknown TTL.
    expiresAt: tokens.expires_in ? Date.now() + tokens.expires_in * 1000 : 0,
  }
}

interface DiscoveryDocument {
  authorization_endpoint?: string
  token_endpoint?: string
  revocation_endpoint?: string
  end_session_endpoint?: string
}

async function fetchDiscovery(issuer: string): Promise<DiscoveryDocument> {
  const base = issuer.endsWith('/') ? issuer : `${issuer}/`
  const res = await nativeFetch(`${base}.well-known/openid-configuration`)
  if (!res.ok) throw new Error(`IdP discovery failed (HTTP ${res.status})`)
  return (await res.json()) as DiscoveryDocument
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '')
}

function randomToken(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return base64Url(bytes)
}

async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

function parseCallbackParams(path: string): Record<string, string> {
  const query = path.split('?')[1] ?? ''
  const params: Record<string, string> = {}
  for (const [key, value] of new URLSearchParams(query)) params[key] = value
  return params
}

/** Browser-based PKCE sign-in against the IdP the Halo server names. */
export async function signInWithOidc(config: OidcAuthConfig): Promise<void> {
  const discovery = await fetchDiscovery(config.issuer)
  if (!discovery.authorization_endpoint || !discovery.token_endpoint) {
    throw new Error('IdP discovery document is missing OAuth endpoints')
  }

  const state = randomToken()
  const codeVerifier = randomToken()
  const authUrl =
    `${discovery.authorization_endpoint}?` +
    new URLSearchParams({
      response_type: 'code',
      client_id: config.clientId,
      redirect_uri: REDIRECT_URI,
      scope: config.scopes.join(' '),
      state,
      code_challenge: await pkceChallenge(codeVerifier),
      code_challenge_method: 'S256',
    }).toString()

  if (import.meta.env.DEV) {
    ;(window as Window & { __haloOidcDebug?: unknown }).__haloOidcDebug = { state, authUrl }
  }

  // Listener first, then browser — the redirect must never race the bind.
  const callback = invoke<string>('oauth_wait_callback')
  try {
    await openUrl(authUrl)
  } catch (err) {
    // Unblock the Rust listener so the port frees immediately, then surface a
    // real Error (plugin rejections are plain strings).
    void nativeFetch(`http://127.0.0.1:${OAUTH_CALLBACK_PORT}/cancel`).catch(() => {})
    void callback.catch(() => {})
    throw err instanceof Error ? err : new Error(String(err))
  }
  const params = parseCallbackParams(await callback)

  if (params.error) throw new Error(params.error_description || `Sign-in failed: ${params.error}`)
  if (!params.code) throw new Error('Sign-in failed: no authorization code returned')
  if (params.state !== state) throw new Error('Sign-in failed: state mismatch')

  const tokens = await postForm(discovery.token_endpoint, {
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code: params.code,
    redirect_uri: REDIRECT_URI,
    code_verifier: codeVerifier,
  })
  session = applyTokens(
    {
      clientId: config.clientId,
      tokenEndpoint: discovery.token_endpoint,
      revocationEndpoint: discovery.revocation_endpoint,
      endSessionEndpoint: discovery.end_session_endpoint,
    },
    tokens,
  )
  persist()
}

/** Current access token, refreshed behind a single-flight lock when stale. */
export async function getOidcAccessToken(): Promise<string | null> {
  if (!session) return null
  if (Date.now() < session.expiresAt - EXPIRY_MARGIN_MS) return session.accessToken
  return refreshOidcToken()
}

/**
 * Forces a refresh; concurrent callers share one in-flight request. Returns
 * null only when the IdP definitively rejected the session — a network error
 * throws instead, so an offline blip never signs the user out.
 */
export function refreshOidcToken(): Promise<string | null> {
  if (!session?.refreshToken) return Promise.resolve(null)
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

async function doRefresh(): Promise<string | null> {
  const current = session!
  try {
    const tokens = await postForm(current.tokenEndpoint, {
      grant_type: 'refresh_token',
      client_id: current.clientId,
      refresh_token: current.refreshToken!,
    })
    session = applyTokens(current, tokens)
    persist()
    return session.accessToken
  } catch (err) {
    // invalid_grant = the refresh token is revoked/expired; the session is
    // dead everywhere, not just flaky here.
    if (err instanceof TokenEndpointError && err.code === 'invalid_grant') {
      clearOidcSession()
      return null
    }
    throw err
  }
}

/** Best-effort revocation + IdP session logout, then the local wipe. */
export async function signOutOidc(): Promise<void> {
  const current = session
  clearOidcSession()
  if (!current) return
  if (current.refreshToken && current.revocationEndpoint) {
    try {
      await nativeFetch(current.revocationEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `client_id=${encodeURIComponent(current.clientId)}&token=${encodeURIComponent(current.refreshToken)}&token_type_hint=refresh_token`,
      })
    } catch {
      // Offline or IdP down — the local wipe still signs this device out.
    }
  }
  // Revocation only kills the refresh token; the browser still holds the SSO
  // cookie, which would silently re-login the same account next time. Opening
  // the end-session endpoint (with id_token_hint so Authentik accepts it)
  // ends that browser session; without a registered post-logout redirect the
  // tab just lands on the IdP's signed-out page, which is fine.
  if (current.endSessionEndpoint) {
    const url = current.idToken
      ? `${current.endSessionEndpoint}?id_token_hint=${encodeURIComponent(current.idToken)}`
      : current.endSessionEndpoint
    void openUrl(url).catch(() => {})
  }
}
