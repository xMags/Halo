package moe.ditto.halo.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import moe.ditto.halo.auth.TokenProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BaseUrl = "https://halo.example"

private class FakeTokens(
    var current: String? = "access-1",
    var refreshed: String? = "access-2",
) : TokenProvider {
    var accessCalls = 0
    var refreshCalls = 0
    var transportFailureOnRefresh = false

    override suspend fun accessToken(): String? {
        accessCalls++
        return current
    }

    override suspend fun refreshAccessToken(): String? {
        refreshCalls++
        if (transportFailureOnRefresh) throw IOException("network down")
        current = refreshed
        return refreshed
    }
}

private fun jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    Triple(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

class HaloClientTest {
    private val recorded = mutableListOf<HttpRequestData>()

    private fun client(
        tokens: TokenProvider = FakeTokens(),
        onUnauthorized: suspend () -> Unit = {},
        handler: suspend (HttpRequestData) -> Triple<String, HttpStatusCode, io.ktor.http.Headers>,
    ): HaloClient {
        val engine = MockEngine { request ->
            recorded += request
            val (body, status, headers) = handler(request)
            respond(content = body, status = status, headers = headers)
        }
        return HaloClient(BaseUrl, tokens, HttpClient(engine), onUnauthorized)
    }

    @Test
    fun sendsBearerTokenAndDecodesResponse() = runTest {
        val client = client { jsonResponse("""{"id":"u1","username":"kenneth","isAdmin":true,"createdAt":17}""") }

        val me = client.getMe()

        assertEquals(Me("u1", "kenneth", isAdmin = true, createdAt = 17), me)
        assertEquals("Bearer access-1", recorded.single().headers[HttpHeaders.Authorization])
        assertEquals("$BaseUrl/auth/me", recorded.single().url.toString())
    }

    @Test
    fun externalSubtitleUsesTheAuthenticatedAddonProxy() = runTest {
        val client = client { Triple("caption", HttpStatusCode.OK, io.ktor.http.Headers.Empty) }

        assertEquals("caption", client.getAddonProxyResponse("https://subs.example/a.srt").bodyAsText())

        assertEquals(
            "$BaseUrl/addon-proxy?url=https%3A%2F%2Fsubs.example%2Fa.srt",
            recorded.single().url.toString(),
        )
        assertEquals("Bearer access-1", recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun anExistingHaloProxyUrlIsNotWrappedAgain() = runTest {
        val client = client { Triple("caption", HttpStatusCode.OK, io.ktor.http.Headers.Empty) }
        val proxied = "$BaseUrl/addon-proxy?url=https%3A%2F%2Fsubs%2Eexample%2Fa%2Esrt"

        client.getAddonProxyResponse(proxied).bodyAsText()

        assertEquals(proxied, recorded.single().url.toString())
    }

    @Test
    fun trailingSlashOnServerUrlDoesNotDoubleUp() = runTest {
        val engine = MockEngine { respond("""{"global":[],"user":[]}""") }
        val client = HaloClient("$BaseUrl/", FakeTokens(), HttpClient(engine))

        client.getAddons()

        assertEquals("$BaseUrl/addons", engine.requestHistory.single().url.toString())
    }

    @Test
    fun refreshesOnceAndRetriesAfterUnauthorized() = runTest {
        val tokens = FakeTokens()
        var attempts = 0
        val client = client(tokens) {
            attempts++
            if (attempts == 1) {
                jsonResponse("""{"error":"expired"}""", HttpStatusCode.Unauthorized)
            } else {
                jsonResponse("""{"id":"u1","username":"kenneth","isAdmin":false,"createdAt":1}""")
            }
        }

        val me = client.getMe()

        assertEquals("kenneth", me.username)
        assertEquals(1, tokens.refreshCalls)
        assertEquals(2, recorded.size)
        assertEquals("Bearer access-1", recorded[0].headers[HttpHeaders.Authorization])
        assertEquals("Bearer access-2", recorded[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun deadSessionSurfacesUnauthorizedWithoutASecondAttempt() = runTest {
        val tokens = FakeTokens(refreshed = null)
        var unauthorizedCalls = 0
        val client = client(tokens, onUnauthorized = { unauthorizedCalls += 1 }) {
            jsonResponse("""{"error":"nope"}""", HttpStatusCode.Unauthorized)
        }

        assertFailsWith<SessionRejectedException> { client.getMe() }

        assertEquals(1, unauthorizedCalls)
        assertEquals(1, tokens.refreshCalls)
        assertEquals(1, recorded.size)
    }

    @Test
    fun retriedUnauthorizedStillFailsAsUnauthorized() = runTest {
        val tokens = FakeTokens()
        var unauthorizedCalls = 0
        val client = client(tokens, onUnauthorized = { unauthorizedCalls += 1 }) {
            jsonResponse("""{"error":"still no"}""", HttpStatusCode.Unauthorized)
        }

        assertFailsWith<SessionRejectedException> { client.getMe() }

        assertEquals(1, unauthorizedCalls)
        assertEquals(1, tokens.refreshCalls)
        assertEquals(2, recorded.size)
    }

    @Test
    fun transportFailureDuringRefreshPropagatesInsteadOfBecomingUnauthorized() = runTest {
        // A network error must never be reported as a dead session.
        val tokens = FakeTokens().apply { transportFailureOnRefresh = true }
        var unauthorizedCalls = 0
        val client = client(tokens, onUnauthorized = { unauthorizedCalls += 1 }) {
            jsonResponse("{}", HttpStatusCode.Unauthorized)
        }

        assertFailsWith<IOException> { client.getMe() }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun transportFailureOnTheRequestPropagates() = runTest {
        val engine = MockEngine { throw IOException("connection reset") }
        var unauthorizedCalls = 0
        val client = HaloClient(BaseUrl, FakeTokens(), HttpClient(engine)) { unauthorizedCalls += 1 }

        assertFailsWith<IOException> { client.getLibrary() }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun surfacesServerErrorMessage() = runTest {
        val client = client { jsonResponse("""{"error":"addon not found"}""", HttpStatusCode.NotFound) }

        val error = assertFailsWith<HaloApiException> { client.getMeta("movie", "tt1") }

        assertEquals(404, error.status)
        assertEquals("addon not found", error.message)
    }

    @Test
    fun fallsBackToStatusWhenErrorBodyIsNotJson() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway, "<html>gateway</html>") }
        var unauthorizedCalls = 0
        val client = HaloClient(BaseUrl, FakeTokens(), HttpClient(engine)) { unauthorizedCalls += 1 }

        val error = assertFailsWith<HaloApiException> { client.getSettings() }

        assertEquals(502, error.status)
        assertContains(error.message ?: "", "502")
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun malformedSuccessDoesNotRejectTheSession() = runTest {
        var unauthorizedCalls = 0
        val client = client(onUnauthorized = { unauthorizedCalls += 1 }) { jsonResponse("not-json") }

        assertFailsWith<MalformedResponseException> { client.getStreams("movie", "tt1") }

        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun signsRequestsWithoutATokenWhenSignedOut() = runTest {
        val client = client(FakeTokens(current = null)) { jsonResponse("[]") }

        client.getLibrary()

        assertNull(recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun buildsCatalogQueryIncludingExtras() = runTest {
        val client = client { jsonResponse("""{"metas":[]}""") }

        client.getCatalog("addon-1", "movie", "top", mapOf("search" to "the matrix"))

        val url = recorded.single().url
        assertEquals("/catalog", url.encodedPath)
        assertEquals("addon-1", url.parameters["addon"])
        assertEquals("movie", url.parameters["type"])
        assertEquals("top", url.parameters["id"])
        assertEquals("the matrix", url.parameters["search"])
    }

    @Test
    fun omitsOptionalSubtitleExtrasWhenAbsent() = runTest {
        val client = client { jsonResponse("""{"results":[],"errors":[],"hashMatched":false}""") }

        client.getSubtitles("series", "tt0944947:1:2", videoHash = "8e245d9679d31e12", videoSize = 733589504L)

        val url = recorded.single().url
        assertEquals("8e245d9679d31e12", url.parameters["videoHash"])
        assertEquals("733589504", url.parameters["videoSize"])
        assertNull(url.parameters["filename"])
    }

    @Test
    fun omitsNextEpisodeHintsWhenAbsent() = runTest {
        val client = client { jsonResponse("""{"video":null,"stream":null}""") }

        val result = client.getNextEpisode(type = "series", metaId = "tt0944947", videoId = "tt0944947:1:2")

        assertNull(result.video)
        assertNull(result.stream)
        val url = recorded.single().url
        assertNull(url.parameters["addon"])
        assertNull(url.parameters["bingeGroup"])
    }

    @Test
    fun encodesAddonIdInThePath() = runTest {
        val client = client { jsonResponse("""{"ok":true}""") }

        client.patchAddon("addon/with space", hideCatalogs = true)

        val request = recorded.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertContains(request.url.toString(), "addon%2Fwith%20space")
    }

    @Test
    fun sendsAddonListAsPlainTransportUrls() = runTest {
        val client = client { jsonResponse("[]") }

        client.putAddons(listOf("https://v3-cinemeta.strem.io/manifest.json"))

        assertEquals(
            """["https://v3-cinemeta.strem.io/manifest.json"]""",
            recorded.single().bodyText(),
        )
    }

    @Test
    fun sendsSettingsWithUnknownKeysIntact() = runTest {
        val client = client { jsonResponse("""{"value":{},"updatedAt":5}""") }
        val stored = HaloJson.decodeFromString<SettingsPayload>(
            """{"value":{"desktopOnly":"keep"},"updatedAt":1}""",
        )

        client.putSettings(stored.value.withPlaybackRate(1.25), updatedAt = 5)

        val body = recorded.single().bodyText()
        assertContains(body, "\"desktopOnly\":\"keep\"")
        assertContains(body, "\"playbackRate\":1.25")
        assertContains(body, "\"updatedAt\":5")
    }

    @Test
    fun omitsAbsentOptionalFieldsFromWrittenRows() = runTest {
        // The server rejects a non-URL poster, so an absent one must be left
        // out entirely rather than written as null or an empty string.
        val client = client { jsonResponse("[]") }

        client.putLibrary(
            listOf(LibraryItem(id = "movie:tt1", type = "movie", name = "Movie", addedAt = 1, updatedAt = 2)),
        )

        val body = recorded.single().bodyText()
        assertTrue("poster" !in body, "absent poster must not be serialised: $body")
        assertTrue("removedAt" !in body, "absent tombstone must not be serialised: $body")
    }

    @Test
    fun proxyUrlEscapesTheWholeTarget() = runTest {
        val client = client { jsonResponse("{}") }

        val url = client.proxyUrl("https://cdn.example/a b?x=1&y=2")

        assertEquals("$BaseUrl/addon-proxy?url=https%3A%2F%2Fcdn.example%2Fa%20b%3Fx%3D1%26y%3D2", url)
    }

    @Test
    fun proxyUrlMatchesEncodeUriComponentExactly() = runTest {
        // Every Halo client must spell the same target identically, so the
        // unreserved set has to match JavaScript's rather than merely decode
        // to the same string.
        assertEquals("-_.!~*'()", "-_.!~*'()".encodeUriComponent())
        assertEquals("abcXYZ019", "abcXYZ019".encodeUriComponent())
        assertEquals("a%2Fb%3Fc%26d%3De%20f", "a/b?c&d=e f".encodeUriComponent())
        // Non-ASCII is encoded per UTF-8 byte, like encodeURIComponent.
        assertEquals("caf%C3%A9", "café".encodeUriComponent())
        assertEquals("%E6%97%A5", "日".encodeUriComponent())
    }
}

private fun HttpRequestData.bodyText(): String =
    (body as io.ktor.http.content.TextContent).text
