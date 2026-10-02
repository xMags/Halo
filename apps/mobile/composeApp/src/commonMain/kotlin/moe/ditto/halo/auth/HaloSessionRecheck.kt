package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/** What the Halo server said about an add-on session after a 401. */
enum class SessionRecheck {
    /** The server still accepts the session; the 401 came from something else. */
    Alive,

    /** The server refused the session; it is over. */
    Rejected,
}

/**
 * Asks the Halo server whether it still accepts a bearer token. Add-on sessions
 * have no refresh of their own, so this stands in for one after a 401.
 */
fun interface HaloSessionRecheck {
    /**
     * Throws on a transport failure or any status other than success or 401
     * (a 503 included), which must leave the session intact.
     */
    suspend fun recheck(serverUrl: String, token: String): SessionRecheck
}

/** Tests and platforms without the wire: every check fails, which keeps the session. */
object NoHaloSessionRecheck : HaloSessionRecheck {
    override suspend fun recheck(serverUrl: String, token: String): SessionRecheck =
        throw IllegalStateException("Session checks are not available here")
}

class KtorHaloSessionRecheck(
    private val httpClient: HttpClient,
) : HaloSessionRecheck {
    override suspend fun recheck(serverUrl: String, token: String): SessionRecheck {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return when {
            response.status.isSuccess() -> SessionRecheck.Alive
            response.status == HttpStatusCode.Unauthorized -> SessionRecheck.Rejected
            else -> throw IllegalStateException("Halo could not confirm the session (HTTP ${response.status.value})")
        }
    }
}
