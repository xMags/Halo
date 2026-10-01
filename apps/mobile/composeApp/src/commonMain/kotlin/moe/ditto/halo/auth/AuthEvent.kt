package moe.ditto.halo.auth

/**
 * Auth-relevant outcomes pushed from the native host back into common code.
 * Mirrors the player-event pattern: the host drives the async browser flow /
 * token lifecycle and reports results here. [SessionController.onAuthEvent]
 * folds the session-shaped ones into app state; [LoginPresenter.onAuthEvent]
 * folds the sign-in-shaped ones into the login state machine.
 *
 * [OidcSucceeded.tokenProof] is harness evidence only — the fixture's
 * access-token string, re-surfaced on the debug gate so automation can prove
 * the full discovery → authorize → `/token/` → persist round-trip. Production
 * UI never renders a token.
 */
sealed interface AuthEvent {
    data class OidcSucceeded(val serverUrl: String, val tokenProof: String) : AuthEvent

    data class OidcFailed(val reason: String) : AuthEvent

    /**
     * The native host's refresh was definitively rejected (`invalid_grant`)
     * and the persisted session is already cleared. The only event that may
     * end an OIDC session — transport failures never emit it.
     */
    data object OidcSessionInvalidated : AuthEvent
}
