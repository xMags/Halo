package moe.ditto.halo.auth

/**
 * Common face of the platform-native OIDC session owner.
 *
 * The entire OIDC wire lives on the native side (on iOS the Swift
 * `OidcAuthHost`: Keychain persistence, single-flight rotating refresh,
 * revoke) because the auth thesis is byte-exact control of the token
 * requests — Kotlin never sees a refresh token or parses OIDC JSON. Common
 * code only needs the three session-shaped questions below; the
 * definitive-rejection sign-out arrives separately as
 * [AuthEvent.OidcSessionInvalidated] through the auth event bridge, so state
 * flips even when the failing fetch happened deep inside an API call.
 */
interface OidcSessionPort {
    /**
     * Server URL of a persisted session, or null when signed out. A pure
     * device-storage read — restore must work offline, like the local arm;
     * token validity is tested lazily by the first [accessToken] call.
     */
    fun restoreSession(): String?

    /**
     * Bearer token for the next request, refreshed by the native side beneath
     * its expiry margin ([forceRefresh] = false) or unconditionally (the
     * post-401 path, [forceRefresh] = true). Null means no session — or a
     * definitive rejection that already cleared it, in which case the
     * invalidation event follows. Throws on transport failure so callers
     * surface a network error instead of misreading it as signed-out.
     */
    suspend fun accessToken(forceRefresh: Boolean): String?

    /**
     * Clears the persisted session; token revocation is best-effort.
     * [endIdpSession] additionally runs the RP-initiated browser logout that
     * ends the IdP's SSO cookie session (revocation alone leaves it alive,
     * and the next sign-in would silently re-login) — true only for the
     * user-facing sign-out, never for the automation reset hatch.
     */
    suspend fun signOut(endIdpSession: Boolean)
}

/** Platforms without a native OIDC host (Android until its M7 pass; tests). */
object NoOidcSessionPort : OidcSessionPort {
    override fun restoreSession(): String? = null

    override suspend fun accessToken(forceRefresh: Boolean): String? = null

    override suspend fun signOut(endIdpSession: Boolean) = Unit
}
