package moe.ditto.halo.auth

/**
 * Outcome of a native OIDC sign-in, pushed from the Swift host back into common
 * code. Mirrors the player-event pattern: Swift drives the async
 * `ASWebAuthenticationSession` + token exchange and reports the terminal result
 * here, which [LoginPresenter.onAuthEvent] folds into the login state machine.
 *
 * This event proves the flow, not a session — [OidcSucceeded.tokenProof] is the
 * fixture's non-authoritative access-token string, surfaced only as evidence
 * that the full discovery → authorize → `/token/` round-trip completed. A
 * production client would never render a token.
 */
sealed interface AuthEvent {
    data class OidcSucceeded(val tokenProof: String) : AuthEvent

    data class OidcFailed(val reason: String) : AuthEvent
}
