package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KtorLocalDeviceSignInTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val empty = JsonObject(emptyMap())

    private fun answering(status: HttpStatusCode, body: String, seen: MutableList<HttpRequestData> = mutableListOf()) =
        KtorLocalDeviceSignIn(HttpClient(MockEngine { request ->
            seen += request
            respond(body, status, jsonHeaders)
        }))

    @Test
    fun aLocalServerOffersPhoneSignInOnlyWhenItSaysSo() {
        val local = answering(HttpStatusCode.OK, "{}")
        assertNotNull(AuthConfigParser.parse("""{"mode":"local","deviceSignIn":true}""").deviceSignInOffer(local))
        assertNull(AuthConfigParser.parse("""{"mode":"local"}""").deviceSignInOffer(local))
        assertEquals(AuthConfig.Local(deviceSignIn = false), AuthConfigParser.parse("""{"mode":"local","deviceSignIn":"yes"}"""))
    }

    @Test
    fun theCodeIsApprovedAtThisServersOwnAddress() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val signIn = answering(
            HttpStatusCode.OK,
            """{"device_code":"secret","user_code":"BCDF-GHJK","verification_uri":"http://internal:8787/auth/device",""" +
                """"verification_uri_complete":"http://internal:8787/auth/device?code=BCDFGHJK","expires_in":600,"interval":5}""",
            seen,
        )

        val challenge = signIn.start("https://halo.example/", empty)

        assertEquals("https://halo.example/auth/device/start", seen.single().url.toString())
        assertEquals("secret", challenge.deviceCode)
        assertEquals("BCDF-GHJK", challenge.userCode)
        // Not the address the server thinks it has behind its proxy.
        assertEquals("https://halo.example/auth/device", challenge.verificationUrl)
        assertEquals("https://halo.example/auth/device?code=BCDFGHJK", challenge.verificationUrlComplete)
    }

    @Test
    fun startingRefusalsAreForTheViewer() = runTest {
        assertFailsWith<DeviceSignInException> { answering(HttpStatusCode.TooManyRequests, """{"error":"rate_limited"}""").start("https://h", empty) }
        assertFailsWith<DeviceSignInException> { answering(HttpStatusCode.ServiceUnavailable, """{"error":"temporarily_unavailable"}""").start("https://h", empty) }
        assertFailsWith<DeviceSignInException> { answering(HttpStatusCode.NotFound, "").start("https://h", empty) }
        // A server error is not a refusal.
        val failure = assertFailsWith<IllegalStateException> { answering(HttpStatusCode.InternalServerError, "").start("https://h", empty) }
        assertIs<IllegalStateException>(failure)
    }

    @Test
    fun pollsSendTheSecretAndMapEveryAnswer() = runTest {
        val challenge = DeviceChallenge("secret", "BCDF-GHJK", "https://h/auth/device", null, 600, 5)
        val seen = mutableListOf<HttpRequestData>()
        answering(HttpStatusCode.BadRequest, """{"error":"authorization_pending"}""", seen).poll("https://h/", challenge)
        assertEquals("https://h/auth/device/token", seen.single().url.toString())
        val body = Json.parseToJsonElement(assertIs<TextContent>(seen.single().body).text).jsonObject
        assertEquals("secret", body["device_code"]?.jsonPrimitive?.content)

        suspend fun answer(status: HttpStatusCode, body: String) = answering(status, body).poll("https://h", challenge)
        val bad = HttpStatusCode.BadRequest
        assertEquals(DevicePollResult.Pending, answer(bad, """{"error":"authorization_pending"}"""))
        assertEquals(DevicePollResult.SlowDown, answer(bad, """{"error":"slow_down"}"""))
        assertEquals(DevicePollResult.Denied, answer(bad, """{"error":"access_denied"}"""))
        assertEquals(DevicePollResult.Expired, answer(bad, """{"error":"expired_token"}"""))
        assertEquals(DevicePollResult.Expired, answer(bad, """{"error":"invalid_grant"}"""))
        assertEquals(
            DevicePollResult.Approved(DeviceSession.Local("session", 1_700_000_000_000)),
            answer(HttpStatusCode.OK, """{"token":"session","expiresAt":1700000000000}"""),
        )
        assertFailsWith<IllegalStateException> { answer(HttpStatusCode.BadGateway, "") }
        assertFailsWith<DeviceSignInException> { answer(bad, """{"error":"invalid_request"}""") }
    }
}
