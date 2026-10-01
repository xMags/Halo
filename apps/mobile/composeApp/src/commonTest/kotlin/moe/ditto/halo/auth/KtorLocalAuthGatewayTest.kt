package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorLocalAuthGatewayTest {

    @Test
    fun loginPostsJsonCredentialsAndParsesTheIssuedToken() = runTest {
        lateinit var seen: HttpRequestData
        val engine = MockEngine { request ->
            seen = request
            respond(
                content = """{"token":"jwt-1","expiresAt":1737480000000}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val gateway = KtorLocalAuthGateway(HttpClient(engine))

        val issued = gateway.login("https://halo.local", "testuser", "hunter22")

        assertEquals(IssuedToken("jwt-1", 1737480000000), issued)
        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("https://halo.local/auth/login", seen.url.toString())
        assertEquals(ContentType.Application.Json, seen.body.contentType)
        val body = assertIs<TextContent>(seen.body).text
        assertTrue(body.contains(""""username":"testuser""""), body)
        assertTrue(body.contains(""""password":"hunter22""""), body)
    }

    @Test
    fun serverRejectionSurfacesTheServerErrorMessageAndStatus() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val gateway = KtorLocalAuthGateway(HttpClient(engine))

        val error = assertFailsWith<LocalAuthException> {
            gateway.login("https://halo.local", "testuser", "wrong")
        }
        assertEquals(401, error.status)
        assertEquals("invalid credentials", error.message)
    }

    @Test
    fun nonJsonErrorBodyFallsBackToTheHttpStatus() = runTest {
        val engine = MockEngine {
            respond(content = "Bad Gateway", status = HttpStatusCode.BadGateway)
        }
        val gateway = KtorLocalAuthGateway(HttpClient(engine))

        val error = assertFailsWith<LocalAuthException> {
            gateway.login("https://halo.local", "testuser", "hunter22")
        }
        assertEquals(502, error.status)
        assertEquals("HTTP 502", error.message)
    }

    @Test
    fun refreshAuthenticatesItselfWithTheCurrentToken() = runTest {
        lateinit var seen: HttpRequestData
        val engine = MockEngine { request ->
            seen = request
            respond(
                content = """{"token":"jwt-2","expiresAt":1737480000001}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val gateway = KtorLocalAuthGateway(HttpClient(engine))

        val issued = gateway.refresh("https://halo.local", "jwt-1")

        assertEquals(IssuedToken("jwt-2", 1737480000001), issued)
        assertEquals("https://halo.local/auth/refresh", seen.url.toString())
        assertEquals("Bearer jwt-1", seen.headers[HttpHeaders.Authorization])
    }

    @Test
    fun malformedSuccessBodyIsAnErrorButNeverADefinitiveRejection() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"unexpected":"shape"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val gateway = KtorLocalAuthGateway(HttpClient(engine))

        assertFailsWith<IllegalStateException> {
            gateway.refresh("https://halo.local", "jwt-1")
        }
    }
}
