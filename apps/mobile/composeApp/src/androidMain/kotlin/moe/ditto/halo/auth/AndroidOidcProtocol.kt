package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object AndroidOidcStorageKeys {
    const val Session = "halo.oidcSession"
    const val PendingRequest = "halo.oidcPending"
}

@Serializable
internal data class AndroidOidcEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String? = null,
    val endSessionEndpoint: String? = null,
)

@Serializable
internal data class AndroidOidcSession(
    val serverUrl: String,
    val issuer: String,
    val clientId: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String? = null,
    val endSessionEndpoint: String? = null,
    val accessToken: String,
    val accessTokenExpiresAtMs: Long,
    val refreshToken: String,
    val idToken: String? = null,
)

@Serializable
internal data class AndroidPendingOidcRequest(
    val serverUrl: String,
    val issuer: String,
    val clientId: String,
    val scopes: List<String>,
    val state: String,
    val verifier: String,
    val endpoints: AndroidOidcEndpoints,
)

internal data class AndroidIssuedTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Double?,
)

internal sealed interface AndroidTokenResult {
    data class Success(val tokens: AndroidIssuedTokens) : AndroidTokenResult
    data object InvalidGrant : AndroidTokenResult
    data class Failure(val reason: String) : AndroidTokenResult
}

internal sealed interface AndroidCallbackResult {
    data object NotCallback : AndroidCallbackResult
    data object StateMismatch : AndroidCallbackResult
    data class AuthorizationError(val reason: String) : AndroidCallbackResult
    data object MissingCode : AndroidCallbackResult
    data class Code(val value: String) : AndroidCallbackResult
}

/**
 * Byte-level OIDC helpers for Android. These deliberately avoid an auth SDK:
 * Authentik advertises slash-terminated token endpoints, and those strings
 * must reach the HTTP client unchanged for POST redirects not to lose bodies.
 */
internal object AndroidOidcProtocol {
    const val RedirectUri = "halo://oauth/callback"
    const val LogoutRedirectUri = "halo://oauth/logout"
    const val ExpiryMarginMs = 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val secureRandom = SecureRandom()

    fun randomUrlSafe(byteCount: Int = 32): String {
        require(byteCount > 0)
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun discoveryUrl(issuer: String): String {
        val normalized = issuer.trimEnd('/')
        requireHttpsOrLocalHttp(normalized, "issuer")
        return "$normalized/.well-known/openid-configuration"
    }

    fun parseDiscovery(payload: String, expectedIssuer: String): AndroidOidcEndpoints {
        val body = parseObject(payload, "Discovery response was not valid JSON")
        val discoveredIssuer = body.requiredString("issuer", "Discovery document is missing issuer")
        require(discoveredIssuer == expectedIssuer) {
            "Discovery issuer did not match the configured issuer"
        }
        return AndroidOidcEndpoints(
            authorizationEndpoint = body.requiredEndpoint("authorization_endpoint"),
            tokenEndpoint = body.requiredEndpoint("token_endpoint"),
            revocationEndpoint = body.optionalEndpoint("revocation_endpoint"),
            endSessionEndpoint = body.optionalEndpoint("end_session_endpoint"),
        )
    }

    fun authorizationUrl(
        request: OidcHostRequest,
        endpoints: AndroidOidcEndpoints,
        state: String,
        verifier: String,
    ): String = appendQuery(
        endpoints.authorizationEndpoint,
        listOf(
            "response_type" to "code",
            "client_id" to request.clientId,
            "redirect_uri" to RedirectUri,
            "scope" to request.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to codeChallenge(verifier),
            "code_challenge_method" to "S256",
        ),
    )

    fun parseCallback(url: String, expectedState: String): AndroidCallbackResult {
        val uri = runCatching { URI(url) }.getOrNull() ?: return AndroidCallbackResult.NotCallback
        if (uri.scheme != "halo" || uri.host != "oauth" || uri.path != "/callback") {
            return AndroidCallbackResult.NotCallback
        }

        val parameters = parseQuery(uri.rawQuery.orEmpty())
        val states = parameters["state"].orEmpty()
        if (states.size != 1 || states.single() != expectedState) {
            return AndroidCallbackResult.StateMismatch
        }

        val errors = parameters["error"].orEmpty()
        if (errors.isNotEmpty()) {
            val description = parameters["error_description"]?.singleOrNull()
            return AndroidCallbackResult.AuthorizationError(
                description?.takeIf(String::isNotBlank) ?: "Authorization error: ${errors.first()}",
            )
        }

        val codes = parameters["code"].orEmpty()
        if (codes.size != 1 || codes.single().isBlank()) return AndroidCallbackResult.MissingCode
        return AndroidCallbackResult.Code(codes.single())
    }

    fun isLogoutCallback(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "halo" && uri.host == "oauth" && uri.path == "/logout"
    }

    fun tokenForm(
        grantType: String,
        clientId: String,
        values: List<Pair<String, String>>,
    ): String = formEncode(listOf("grant_type" to grantType, "client_id" to clientId) + values)

    fun revokeForm(clientId: String, refreshToken: String): String = formEncode(
        listOf(
            "client_id" to clientId,
            "token" to refreshToken,
            "token_type_hint" to "refresh_token",
        ),
    )

    fun parseTokenResponse(statusCode: Int, payload: String): AndroidTokenResult {
        val body = try {
            parseObject(payload, "Token response was not valid JSON (HTTP $statusCode)")
        } catch (error: IllegalArgumentException) {
            return AndroidTokenResult.Failure(error.message ?: "Token response was not valid JSON")
        }

        val oauthError = body["error"]?.jsonPrimitive?.contentOrNull
        if (oauthError == "invalid_grant") return AndroidTokenResult.InvalidGrant
        if (oauthError != null || statusCode !in 200..299) {
            val description = body["error_description"]?.jsonPrimitive?.contentOrNull
            return AndroidTokenResult.Failure(
                description?.takeIf(String::isNotBlank)
                    ?: oauthError?.let { "Token endpoint rejected the request: $it" }
                    ?: "Token endpoint returned HTTP $statusCode",
            )
        }

        val accessToken = body["access_token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return AndroidTokenResult.Failure("Token response did not include access_token")
        return AndroidTokenResult.Success(
            AndroidIssuedTokens(
                accessToken = accessToken,
                refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank),
                idToken = body["id_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank),
                expiresInSeconds = body["expires_in"]?.jsonPrimitive?.doubleOrNull,
            ),
        )
    }

    fun expiresAtMs(nowMs: Long, expiresInSeconds: Double?): Long {
        val lifetimeMs = ((expiresInSeconds ?: 0.0).coerceAtLeast(0.0) * 1_000.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
        return if (Long.MAX_VALUE - nowMs < lifetimeMs) Long.MAX_VALUE else nowMs + lifetimeMs
    }

    fun encodeSession(session: AndroidOidcSession): String =
        json.encodeToString(AndroidOidcSession.serializer(), session)

    fun decodeSession(payload: String): AndroidOidcSession =
        json.decodeFromString(AndroidOidcSession.serializer(), payload)

    fun encodePending(request: AndroidPendingOidcRequest): String =
        json.encodeToString(AndroidPendingOidcRequest.serializer(), request)

    fun decodePending(payload: String): AndroidPendingOidcRequest =
        json.decodeFromString(AndroidPendingOidcRequest.serializer(), payload)

    fun appendQuery(endpoint: String, values: List<Pair<String, String>>): String {
        val uri = URI(endpoint)
        require(uri.isAbsolute && uri.fragment == null) { "Invalid OIDC endpoint" }
        requireHttpsOrLocalHttp(endpoint, "OIDC endpoint")
        val separator = if (uri.rawQuery.isNullOrEmpty()) "?" else "&"
        return endpoint + separator + formEncode(values)
    }

    private fun formEncode(values: List<Pair<String, String>>): String = values.joinToString("&") {
        "${percentEncode(it.first)}=${percentEncode(it.second)}"
    }

    private fun percentEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~", ignoreCase = true)

    private fun parseQuery(rawQuery: String): Map<String, List<String>> {
        if (rawQuery.isEmpty()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val separator = pair.indexOf('=')
            val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            decodeQueryPart(rawKey) to decodeQueryPart(rawValue)
        }.groupBy({ it.first }, { it.second })
    }

    private fun decodeQueryPart(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun parseObject(payload: String, message: String): JsonObject = try {
        json.parseToJsonElement(payload) as? JsonObject ?: throw IllegalArgumentException(message)
    } catch (_: SerializationException) {
        throw IllegalArgumentException(message)
    }

    private fun JsonObject.requiredString(key: String, message: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(message)

    private fun JsonObject.requiredEndpoint(key: String): String =
        requiredString(key, "Discovery document is missing $key").also {
            requireHttpsOrLocalHttp(it, key)
        }

    private fun JsonObject.optionalEndpoint(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.also {
            requireHttpsOrLocalHttp(it, key)
        }

    private fun requireHttpsOrLocalHttp(value: String, label: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.isAbsolute == true && uri.host != null) { "Invalid $label URL" }
        val isLocalHttp = uri.scheme == "http" &&
            (uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "10.0.2.2")
        require(uri.scheme == "https" || isLocalHttp) { "$label must use HTTPS" }
    }
}

internal interface AndroidOidcWire {
    suspend fun discover(issuer: String): AndroidOidcEndpoints
    suspend fun postToken(endpoint: String, formBody: String): AndroidTokenResult
    suspend fun revoke(endpoint: String, formBody: String)
}

internal class KtorAndroidOidcWire(
    private val httpClient: HttpClient,
) : AndroidOidcWire {
    override suspend fun discover(issuer: String): AndroidOidcEndpoints {
        val response = httpClient.get(AndroidOidcProtocol.discoveryUrl(issuer))
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Discovery returned HTTP ${response.status.value}")
        }
        return AndroidOidcProtocol.parseDiscovery(response.bodyAsText(), issuer)
    }

    override suspend fun postToken(endpoint: String, formBody: String): AndroidTokenResult {
        val response = httpClient.post(endpoint) {
            header("Content-Type", ContentType.Application.FormUrlEncoded.toString())
            accept(ContentType.Application.Json)
            setBody(formBody)
        }
        return AndroidOidcProtocol.parseTokenResponse(response.status.value, response.bodyAsText())
    }

    override suspend fun revoke(endpoint: String, formBody: String) {
        httpClient.post(endpoint) {
            header("Content-Type", ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody)
        }
    }
}
