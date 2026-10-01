/**
 * PKCE and callback helpers shared by the browser sign-ins.
 * Pure WebCrypto, no Tauri, so the sign-in logic built on them stays testable
 * under Node.
 */

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replaceAll('=', '')
}

/** 32 random bytes, base64url: used for both `state` and the PKCE verifier. */
export function randomToken(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return base64Url(bytes)
}

/** The S256 challenge for a PKCE verifier. */
export async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

/** Query parameters of the callback request path the loopback listener returns. */
export function parseCallbackParams(path: string): Record<string, string> {
  const query = path.split('?')[1] ?? ''
  const params: Record<string, string> = {}
  for (const [key, value] of new URLSearchParams(query)) params[key] = value
  return params
}
