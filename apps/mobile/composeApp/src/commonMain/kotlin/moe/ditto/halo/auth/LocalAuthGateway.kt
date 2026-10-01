package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A local-mode session token as the server issues it: the bearer JWT plus its
 * expiry in epoch milliseconds. The client never inspects the JWT; expiry
 * scheduling uses only [expiresAt].
 */
@Serializable
data class IssuedToken(
    val token: String,
    val expiresAt: Long,
)

/**
 * A server-shaped auth failure: the endpoint answered with a non-success
 * status. Distinct from transport failures (which surface as the HTTP
 * client's own exceptions) because the two have opposite session
 * consequences — a 401 here is a definitive rejection that ends the session,
 * while a network error must never sign the device out.
 */
class LocalAuthException(
    val status: Int,
    message: String,
) : Exception(message)

/** Wire client for the API's local-mode auth endpoints. */
interface LocalAuthGateway {
    suspend fun login(serverUrl: String, username: String, password: String): IssuedToken

    /** The current token authenticates its own slide; the server answers with a fresh one. */
    suspend fun refresh(serverUrl: String, token: String): IssuedToken
}

class KtorLocalAuthGateway(
    private val httpClient: HttpClient,
) : LocalAuthGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun login(serverUrl: String, username: String, password: String): IssuedToken {
        val response = httpClient.post("${serverUrl.trimEnd('/')}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password)))
        }
        return parseIssuedToken(response)
    }

    override suspend fun refresh(serverUrl: String, token: String): IssuedToken {
        val response = httpClient.post("${serverUrl.trimEnd('/')}/auth/refresh") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return parseIssuedToken(response)
    }

    private suspend fun parseIssuedToken(response: HttpResponse): IssuedToken {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LocalAuthException(response.status.value, extractErrorMessage(body) ?: "HTTP ${response.status.value}")
        }
        val issued = try {
            json.decodeFromString(IssuedToken.serializer(), body)
        } catch (error: SerializationException) {
            throw IllegalStateException("Malformed token response", error)
        }
        if (issued.token.isBlank()) throw IllegalStateException("Malformed token response")
        return issued
    }

    /** Error bodies are `{"error": "..."}`; anything else falls back to the HTTP status. */
    private fun extractErrorMessage(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Serializable
    private data class LoginRequest(val username: String, val password: String)
}
