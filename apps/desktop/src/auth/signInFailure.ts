/**
 * How a failed sign-in reads on the sign-in screen, in the native app's
 * terms: the user backed out (back to the button), the provider declined,
 * the request expired, or Halo could not be reached. Pure, so the mapping is
 * testable under Node.
 */

export type BrowserFailureReason = 'cancelled' | 'declined' | 'expired'

/** A browser sign-in that ended at the user's or the provider's say. */
export class BrowserSignInError extends Error {
  constructor(
    readonly reason: BrowserFailureReason,
    message: string,
  ) {
    super(message)
    this.name = 'BrowserSignInError'
  }
}

export type SignInFailure =
  | { kind: 'cancelled' }
  | { kind: 'declined' }
  | { kind: 'expired' }
  /** The provider answered and said no (a disabled account, a bad request): its reason is shown. */
  | { kind: 'refused'; message: string }
  | { kind: 'unreachable'; detail: string }

/**
 * A provider that answered with a refusal (a disabled account, a bad request)
 * rather than failing to answer. Sign-in add-ons throw this, or a subclass,
 * so the screen shows the provider's reason.
 */
export class SignInRefusedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SignInRefusedError'
  }
}

/** The OIDC library's own refusal, recognised by name: it does not extend SignInRefusedError. */
const REFUSAL_ERRORS = new Set(['TokenEndpointError'])

function messageOf(err: unknown): string {
  if (err instanceof Error) return err.message
  return typeof err === 'string' ? err : 'Unknown error'
}

/**
 * The loopback listener's rejection, as the Rust side words it (oauth.rs):
 * a /cancel request or its five-minute timeout. Anything else is a real
 * failure and keeps its message.
 */
export function loopbackWaitFailure(err: unknown): Error {
  const message = messageOf(err)
  if (/cancelled/i.test(message)) return new BrowserSignInError('cancelled', message)
  if (/timed out/i.test(message)) return new BrowserSignInError('expired', message)
  return err instanceof Error ? err : new Error(message)
}

/** The callback's `error` parameter: only a refusal counts as declined. */
export function callbackFailure(params: Record<string, string>): BrowserSignInError | null {
  if (params.error !== 'access_denied') return null
  return new BrowserSignInError('declined', params.error_description || 'Sign-in was declined')
}

/**
 * Any failed browser sign-in (OIDC or a sign-in add-on). A code that reached the token
 * exchange too late (`invalid_grant`) is an expired request, and a provider's
 * own refusal keeps its reason. Everything else reads as "can't connect",
 * with the error kept as the technical detail, as the native app does.
 */
export function classifySignInFailure(err: unknown): SignInFailure {
  if (err instanceof BrowserSignInError) return { kind: err.reason }
  const shape = err as { code?: unknown; name?: unknown } | null
  if (shape?.code === 'invalid_grant') return { kind: 'expired' }
  if (err instanceof SignInRefusedError || (typeof shape?.name === 'string' && REFUSAL_ERRORS.has(shape.name))) {
    return { kind: 'refused', message: messageOf(err) }
  }
  return { kind: 'unreachable', detail: messageOf(err) }
}

/** The local-account form's error line, in the native app's words. */
export function localSignInMessage(err: unknown): string {
  const status = (err as { status?: unknown } | null)?.status
  if (status === 401) return 'The username or password is incorrect.'
  if (status === 429) return 'Too many attempts. Try again later.'
  return "Can't connect to Halo. Try again."
}
