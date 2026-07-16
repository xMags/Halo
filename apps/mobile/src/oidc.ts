import * as AuthSession from 'expo-auth-session'
import * as Linking from 'expo-linking'
import * as SecureStore from 'expo-secure-store'
import * as WebBrowser from 'expo-web-browser'
import type { OidcAuthConfig } from '@halo/core'

// Completes a pending auth session when the app regains focus. No-op on
// native (ASWebAuthenticationSession handles it), required on web.
WebBrowser.maybeCompleteAuthSession()

const OIDC_KEY = 'halo.oidc'
/** Refresh this many ms before nominal expiry so in-flight requests don't race it. */
const EXPIRY_MARGIN_MS = 60_000

interface OidcSession {
  clientId: string
  // Endpoints are captured at sign-in so refresh/revoke never need the
  // discovery document again — refresh must work without reaching the issuer's
  // well-known route first.
  tokenEndpoint: string
  revocationEndpoint?: string
  endSessionEndpoint?: string
  accessToken: string
  refreshToken?: string
  /** Epoch ms when accessToken expires. */
  expiresAt: number
}

let session: OidcSession | null = null
let refreshInFlight: Promise<string | null> | null = null

async function persist(): Promise<void> {
  await SecureStore.setItemAsync(OIDC_KEY, JSON.stringify(session))
}

/** Loads persisted tokens into memory. Returns true when a session exists. */
export async function loadOidcSession(): Promise<boolean> {
  const raw = await SecureStore.getItemAsync(OIDC_KEY)
  session = raw ? (JSON.parse(raw) as OidcSession) : null
  return session !== null
}

export async function clearOidcSession(): Promise<void> {
  session = null
  await SecureStore.deleteItemAsync(OIDC_KEY)
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
  expires_in?: number
}

/**
 * Direct form POST to an OAuth endpoint. Deliberately not
 * `AuthSession.exchangeCodeAsync`/`refreshAsync`: expo-auth-session strips the
 * trailing slash from every request URL, which turns Authentik's `/token/`
 * into a Django APPEND_SLASH 301 — fetch downgrades the redirected POST to a
 * body-less GET and the exchange dies with an empty 405. The requests are
 * trivial form posts, so we own them.
 */
async function postForm(url: string, form: Record<string, string>): Promise<TokenEndpointResponse> {
  const res = await fetch(url, {
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

function applyTokens(current: Omit<OidcSession, 'accessToken' | 'refreshToken' | 'expiresAt'> & Partial<OidcSession>, tokens: TokenEndpointResponse): OidcSession {
  return {
    clientId: current.clientId,
    tokenEndpoint: current.tokenEndpoint,
    revocationEndpoint: current.revocationEndpoint,
    endSessionEndpoint: current.endSessionEndpoint,
    accessToken: tokens.access_token,
    // Authentik rotates refresh tokens; fall back to the old one if the
    // response omits it.
    refreshToken: tokens.refresh_token ?? current.refreshToken,
    // A missing expires_in counts as already stale, so the next request
    // refreshes instead of trusting an unknown TTL.
    expiresAt: tokens.expires_in ? Date.now() + tokens.expires_in * 1000 : 0,
  }
}

function parseRedirectParams(url: string): Record<string, string> {
  const query = url.split('?')[1] ?? ''
  const params: Record<string, string> = {}
  for (const pair of query.split('&')) {
    if (!pair) continue
    const [key, value = ''] = pair.split('=')
    params[decodeURIComponent(key!)] = decodeURIComponent(value)
  }
  return params
}

/** Browser-based PKCE sign-in against the IdP the Halo server names. */
export async function signInWithOidc(config: OidcAuthConfig): Promise<void> {
  const discovery = await AuthSession.fetchDiscoveryAsync(config.issuer)
  if (!discovery.tokenEndpoint) throw new Error('IdP discovery document has no token endpoint')
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'halo', path: 'oauth/callback' })
  const request = new AuthSession.AuthRequest({
    clientId: config.clientId,
    redirectUri,
    scopes: config.scopes,
  })

  // Capture the redirect in parallel with promptAsync. On Android the browser
  // "dismiss" (AppState turning active) races the Linking event that carries
  // the authorization code, so a successful login can surface as a dismissal —
  // and promptAsync tears down its own listener before the late event lands.
  // Registering ours first closes that gap.
  let redirectUrl: string | null = null
  const subscription = Linking.addEventListener('url', (event) => {
    if (event.url.startsWith(redirectUri)) redirectUrl = event.url
  })
  try {
    const result = await request.promptAsync(discovery)
    if (result.type === 'success') {
      return await exchangeCode(config, discovery, request, redirectUri, result.params)
    }
    if (result.type === 'error') throw new Error(result.error?.description ?? 'Sign-in failed')
    if (result.type === 'dismiss') {
      // Grace window for a Linking event that lost the race.
      if (!redirectUrl) await new Promise((resolve) => setTimeout(resolve, 1500))
      if (redirectUrl) {
        return await exchangeCode(config, discovery, request, redirectUri, parseRedirectParams(redirectUrl))
      }
    }
    throw new Error('Sign-in was cancelled')
  } finally {
    subscription.remove()
  }
}

async function exchangeCode(
  config: OidcAuthConfig,
  discovery: AuthSession.DiscoveryDocument,
  request: AuthSession.AuthRequest,
  redirectUri: string,
  params: Record<string, string>,
): Promise<void> {
  if (params.error) throw new Error(params.error_description || `Sign-in failed: ${params.error}`)
  if (!params.code) throw new Error('Sign-in failed: no authorization code returned')
  // promptAsync validates state on its success path; the fallback path must too.
  if (params.state !== request.state) throw new Error('Sign-in failed: state mismatch')

  const tokens = await postForm(discovery.tokenEndpoint!, {
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code: params.code,
    redirect_uri: redirectUri,
    code_verifier: request.codeVerifier!,
  })
  session = applyTokens(
    {
      clientId: config.clientId,
      tokenEndpoint: discovery.tokenEndpoint!,
      revocationEndpoint: discovery.revocationEndpoint,
      endSessionEndpoint: discovery.endSessionEndpoint,
    },
    tokens,
  )
  await persist()
}

/** Current access token, refreshed behind a single-flight lock when stale. */
export async function getAccessToken(): Promise<string | null> {
  if (!session) return null
  if (Date.now() < session.expiresAt - EXPIRY_MARGIN_MS) return session.accessToken
  return refreshAccessToken()
}

/**
 * Forces a refresh; concurrent callers share one in-flight request. Returns
 * null only when the IdP definitively rejected the session — a network error
 * throws instead, so an offline blip never signs the user out.
 */
export function refreshAccessToken(): Promise<string | null> {
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
    await persist()
    return session.accessToken
  } catch (err) {
    // invalid_grant = the refresh token is revoked/expired; the session is
    // dead everywhere, not just flaky here.
    if (err instanceof TokenEndpointError && err.code === 'invalid_grant') {
      await clearOidcSession()
      return null
    }
    throw err
  }
}

/** Best-effort revocation + IdP session logout, then the local wipe. */
export async function signOutOidc(): Promise<void> {
  const current = session
  await clearOidcSession()
  if (!current) return
  if (current.refreshToken && current.revocationEndpoint) {
    try {
      await fetch(current.revocationEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `client_id=${encodeURIComponent(current.clientId)}&token=${encodeURIComponent(current.refreshToken)}&token_type_hint=refresh_token`,
      })
    } catch {
      // Offline or IdP down — the local wipe still signs this device out.
    }
  }
  // Revocation only kills the refresh token; the browser still holds the SSO
  // cookie, which would silently re-login the same account on the next
  // sign-in. RP-initiated logout ends that session (the provider's
  // invalidation flow must include the logout stage for this to stick).
  // Fire-and-forget: the device is already signed out either way.
  if (current.endSessionEndpoint) {
    void WebBrowser.openBrowserAsync(current.endSessionEndpoint).catch(() => {})
  }
}
