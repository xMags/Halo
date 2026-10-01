package moe.ditto.halo.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorAuthConfigSourceTest {
    @Test
    fun fetchesPublicAuthConfigFromSelectedServer() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://halo.example/auth/config", request.url.toString())
            respond(
                content = """{"mode":"local"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val source = KtorAuthConfigSource(HttpClient(engine))

        assertEquals(AuthConfig.Local, source.fetch("https://halo.example"))
    }

    @Test
    fun rejectsNonSuccessResponseBeforeParsing() = runTest {
        val engine = MockEngine {
            respond(content = "not found", status = HttpStatusCode.NotFound)
        }
        val source = KtorAuthConfigSource(HttpClient(engine))

        assertFailsWith<IllegalStateException> {
            source.fetch("https://halo.example")
        }
    }
}
