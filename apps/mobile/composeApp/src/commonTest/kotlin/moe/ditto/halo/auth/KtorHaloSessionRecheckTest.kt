package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorHaloSessionRecheckTest {

    @Test
    fun theRecheckAsksTheHaloServerWithTheSession() = runTest {
        lateinit var seen: HttpRequestData
        val recheck = KtorHaloSessionRecheck(HttpClient(MockEngine { request ->
            seen = request
            respond("""{"id":"usr_1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        assertEquals(SessionRecheck.Alive, recheck.recheck("https://halo.example/", "session-1"))
        assertEquals(HttpMethod.Get, seen.method)
        assertEquals("https://halo.example/auth/me", seen.url.toString())
        assertEquals("Bearer session-1", seen.headers[HttpHeaders.Authorization])
    }

    @Test
    fun onlyTheServersRefusalEndsTheSession() = runTest {
        fun recheck(status: HttpStatusCode) = KtorHaloSessionRecheck(HttpClient(MockEngine { respondError(status) }))

        assertEquals(SessionRecheck.Rejected, recheck(HttpStatusCode.Unauthorized).recheck("https://halo.example", "s"))
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.InternalServerError, HttpStatusCode.Forbidden)) {
            assertFailsWith<IllegalStateException>(status.toString()) {
                recheck(status).recheck("https://halo.example", "s")
            }
        }
    }
}
