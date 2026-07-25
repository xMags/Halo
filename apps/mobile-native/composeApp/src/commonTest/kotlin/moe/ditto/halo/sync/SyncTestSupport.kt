package moe.ditto.halo.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.auth.TokenProvider
import moe.ditto.halo.storage.KeyValueStore

internal class FakeStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}

internal class FakeClock(var now: Long = 1_700_000_000_000) : EpochClock {
    override fun nowMs(): Long = now
}

private object StaticTokens : TokenProvider {
    override suspend fun accessToken(): String = "token"

    override suspend fun refreshAccessToken(): String = "token"
}

/**
 * A real client over a mock transport, so tests can assert what actually goes
 * on the wire — which row was sent, and whether a whole document came with it.
 */
internal class RecordingApi(private val reply: (HttpRequestData) -> String) {
    val requests = mutableListOf<HttpRequestData>()

    val client: HaloClient = HaloClient(
        baseUrl = "https://halo.test",
        tokens = StaticTokens,
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                respond(
                    content = reply(request),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ),
    )

    val writes: List<HttpRequestData>
        get() = requests.filter { it.body is TextContent }

    fun bodyOf(index: Int): String = (writes[index].body as TextContent).text
}

/**
 * Models the server closely enough for cache assertions: reads answer with
 * [initial], and a write answers with the rows it was sent — the endpoints
 * return the caller's full merged collection, so echoing the request is what a
 * server holding only those rows would say.
 */
internal fun echoingApi(initial: String = "[]") = RecordingApi { request ->
    (request.body as? TextContent)?.text ?: initial
}
