import { invoke } from '@tauri-apps/api/core'
import { fetch as nativeFetch } from '@tauri-apps/plugin-http'
import { openUrl } from '@tauri-apps/plugin-opener'
import { parseCallbackParams } from './pkce'
import { callbackFailure, loopbackWaitFailure } from './signInFailure'

/**
 * The browser half of an RFC 8252 sign-in: the Rust side listens on a fixed
 * localhost port, the default browser opens the sign-in page, and the
 * redirect lands on the listener. Shared by the OIDC sign-in and the sign-in
 * add-ons.
 */

/** Must match CALLBACK_PORT in src-tauri/src/oauth.rs and the redirect URI each provider registers. */
export const OAUTH_CALLBACK_PORT = 17871
export const REDIRECT_URI = `http://127.0.0.1:${OAUTH_CALLBACK_PORT}/callback`

/** Frees the listener; a pending sign-in then rejects as cancelled. */
export function cancelBrowserSignIn(): void {
  void nativeFetch(`http://127.0.0.1:${OAUTH_CALLBACK_PORT}/cancel`).catch(() => {})
}

/**
 * Opens `authUrl` in the default browser and resolves with the callback's
 * query parameters once the browser is redirected back. The listener is bound
 * before the browser opens, so the redirect can never race the bind.
 * `onOpened` hears the URL once the browser has it, so the sign-in screen can
 * offer to open it again. Rejects with a BrowserSignInError when the wait was
 * cancelled or timed out, or the provider declined.
 */
export async function signInInBrowser(
  authUrl: string,
  onOpened?: (authUrl: string) => void,
): Promise<Record<string, string>> {
  const callback = invoke<string>('oauth_wait_callback')
  try {
    await openUrl(authUrl)
  } catch (err) {
    // Unblock the Rust listener so the port frees immediately, then surface a
    // real Error (plugin rejections are plain strings).
    cancelBrowserSignIn()
    void callback.catch(() => {})
    throw err instanceof Error ? err : new Error(String(err))
  }
  onOpened?.(authUrl)

  let path: string
  try {
    path = await callback
  } catch (err) {
    throw loopbackWaitFailure(err)
  }
  const params = parseCallbackParams(path)
  const declined = callbackFailure(params)
  if (declined) throw declined
  return params
}
