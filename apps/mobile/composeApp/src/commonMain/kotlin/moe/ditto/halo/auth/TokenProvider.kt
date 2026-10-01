package moe.ditto.halo.auth

/**
 * What the API client asks of the auth subsystem, regardless of mode.
 *
 * Contract shared with the server's behavior: protected endpoints answer any
 * auth failure with an undifferentiated 401, so "expired" versus "dead" is
 * only distinguishable by attempting a refresh. The API client therefore
 * calls [accessToken] before each request, and on a 401 calls
 * [refreshAccessToken] once — retrying with a fresh token, or treating null
 * as the end of the session.
 */
interface TokenProvider {
    /**
     * Bearer token for the next request, refreshed beneath the expiry margin.
     * Null means no session. Throws on transport failure so callers surface a
     * network error instead of misreading it as signed-out.
     */
    suspend fun accessToken(): String?

    /**
     * Forces a refresh (the post-401 path). Null means the server definitively
     * rejected the session — it has already been cleared. Transport failures
     * throw and leave the session intact: only a definitive rejection may end
     * a session.
     */
    suspend fun refreshAccessToken(): String?
}
