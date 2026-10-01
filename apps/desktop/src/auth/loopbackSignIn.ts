import { invoke } from '@tauri-apps/api/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { openUrl } from '@tauri-apps/plugin-opener'
import { parseCallbackParams } from './pkce'

/**
 * The browser half of an RFC 8252 sign-in: the Rust side listens on a fixed
 * localhost port, the default browser opens the sign-in page, and the
 * redirect lands on the listener. Shared by every browser sign-in.
 */

/** Must match CALLBACK_PORT in src-tauri/src/oauth.rs and the redirect URI each provider registers. */
export const OAUTH_CALLBACK_PORT = 17871
export const REDIRECT_URI = `http://127.0.0.1:${OAUTH_CALLBACK_PORT}/callback`

/**
 * Opens `authUrl` in the default browser and resolves with the callback's
 * query parameters once the browser is redirected back. The listener is bound
 * before the browser opens, so the redirect can never race the bind.
 */
export async function signInInBrowser(authUrl: string): Promise<Record<string, string>> {
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
  return parseCallbackParams(await callback)
}
