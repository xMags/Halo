package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

interface AuthConfigSource {
    suspend fun fetch(serverUrl: String): AuthConfig
}

data class OidcHostRequest(
    val serverUrl: String,
    val issuer: String,
    val clientId: String,
    val scopes: List<String>,
)

interface NativeHostRequests {
    fun requestOidc(request: OidcHostRequest)
}

class KtorAuthConfigSource(
    private val httpClient: HttpClient,
) : AuthConfigSource {
    override suspend fun fetch(serverUrl: String): AuthConfig {
        val response = httpClient.get("$serverUrl/auth/config")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Auth config request failed with HTTP ${response.status.value}")
        }
        return AuthConfigParser.parse(response.bodyAsText())
    }
}
