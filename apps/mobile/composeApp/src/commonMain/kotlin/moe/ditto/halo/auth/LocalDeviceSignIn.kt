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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Phone sign-in for a local-mode Halo server (`/auth/device/...`): the TV
 * gets a code, the viewer approves it on the server's own page at
 * `/auth/device` with their username and password, and the session the TV
 * receives is the one a password sign-in would give.
 *
 * The approval address is built from the server address this app already
 * uses, not taken from the server's answer: a server behind a proxy may not
 * know the address it is reached at.
 */
class KtorLocalDeviceSignIn(
    private val httpClient: HttpClient,
) : DeviceSignIn {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(serverUrl: String, config: JsonObject): DeviceChallenge {
        val base = serverUrl.trimEnd('/')
        val response = httpClient.post("$base/auth/device/start") {
            header(HttpHeaders.Accept, "application/json")
        }
        val fields = parse(response)
        if (!response.status.isSuccess()) {
            when (response.status.value) {
                429 -> throw DeviceSignInException("Too many sign-ins from this network. Try again in a few minutes.")
                503 -> throw DeviceSignInException("The server is busy with other sign-ins. Try again in a minute.")
                404 -> throw DeviceSignInException("This server cannot sign in a TV with a phone.")
                else -> throw IllegalStateException("HTTP ${response.status.value}")
            }
        }
        fun field(key: String) = fields?.string(key)?.takeIf(String::isNotBlank)
            ?: throw DeviceSignInException("The server answered without a sign-in code. Try again.")
        val userCode = field("user_code")
        val page = "$base/auth/device"
        return DeviceChallenge(
            deviceCode = field("device_code"),
            userCode = userCode,
            verificationUrl = page,
            verificationUrlComplete = "$page?code=${userCode.replace("-", "")}",
            expiresInSeconds = field("expires_in").toIntOrNull() ?: DefaultExpiresInSeconds,
            intervalSeconds = field("interval").toIntOrNull() ?: DefaultIntervalSeconds,
        )
    }

    override suspend fun poll(serverUrl: String, challenge: DeviceChallenge): DevicePollResult {
        val response = httpClient.post("${serverUrl.trimEnd('/')}/auth/device/token") {
            header(HttpHeaders.Accept, "application/json")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("device_code", challenge.deviceCode) }.toString())
        }
        val fields = parse(response)
        if (response.status.isSuccess()) {
            val issued = try {
                json.decodeFromJsonElement(IssuedToken.serializer(), fields ?: JsonObject(emptyMap()))
            } catch (_: SerializationException) {
                throw DeviceSignInException("The server did not return a session. Get a new code.")
            }
            if (issued.token.isBlank()) throw DeviceSignInException("The server did not return a session. Get a new code.")
            return DevicePollResult.Approved(DeviceSession.Local(issued.token, issued.expiresAt))
        }
        // A server error or a busy moment: worth asking again.
        if (response.status.value >= 500 || response.status.value == 429) throw IllegalStateException("HTTP ${response.status.value}")
        return when (fields?.string("error")) {
            "authorization_pending" -> DevicePollResult.Pending
            "slow_down" -> DevicePollResult.SlowDown
            "access_denied" -> DevicePollResult.Denied
            // Unknown to the server: expired and swept, already used, or a restart.
            "expired_token", "invalid_grant" -> DevicePollResult.Expired
            else -> throw DeviceSignInException("The server refused the sign-in (HTTP ${response.status.value}).")
        }
    }

    private suspend fun parse(response: HttpResponse): JsonObject? = try {
        json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private companion object {
        const val DefaultExpiresInSeconds = 600
        const val DefaultIntervalSeconds = 5
    }
}
