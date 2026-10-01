package moe.ditto.halo.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The hash itself is covered by [VideoHashTest]; what matters here is how many
 * times the source host is asked, and by which route.
 *
 * The source is usually the same rate-limited resolver the engine is streaming
 * through, and every request spent here is one playback does not get.
 */
class StreamVideoHasherTest {

    @Test
    fun aDeclaredSizeCostsTwoRequestsInsteadOfFour() = runTest {
        val calls = mutableListOf<String>()
        val hasher = StreamVideoHasher(client(calls))

        val fingerprint = hasher.fingerprint(Url, knownSizeBytes = FileBytes)

        assertEquals(FileBytes, fingerprint?.sizeBytes)
        // The two chunk reads the hash is defined over, and nothing else: no
        // HEAD and no probe, because the caller already knew the size. They are
        // issued together, so the set is asserted rather than the order.
        assertEquals(
            setOf("GET bytes=0-65535", "GET bytes=${FileBytes - 65536}-${FileBytes - 1}"),
            calls.toSet(),
        )
    }

    @Test
    fun withoutADeclaredSizeItStillDiscoversOneOverHttp() = runTest {
        val calls = mutableListOf<String>()
        val hasher = StreamVideoHasher(client(calls))

        val fingerprint = hasher.fingerprint(Url)

        assertEquals(FileBytes, fingerprint?.sizeBytes)
        // HEAD first, then the two chunks. The fallback path is unchanged: a
        // host that answers HEAD usefully still costs one extra request.
        assertEquals(3, calls.size)
        assertEquals("HEAD", calls.first())
    }

    @Test
    fun aDeclaredSizeAndTheDiscoveredOneAgree() = runTest {
        val declared = StreamVideoHasher(client(mutableListOf()))
            .fingerprint(Url, knownSizeBytes = FileBytes)
        val discovered = StreamVideoHasher(client(mutableListOf())).fingerprint(Url)

        assertEquals(discovered, declared)
    }

    @Test
    fun anImpossibleDeclaredSizeIsIgnoredRatherThanTrusted() = runTest {
        val calls = mutableListOf<String>()
        val hasher = StreamVideoHasher(client(calls))

        // Zero is not a size, so it must not short-circuit discovery.
        val fingerprint = hasher.fingerprint(Url, knownSizeBytes = 0)

        assertEquals(FileBytes, fingerprint?.sizeBytes)
        assertEquals("HEAD", calls.first())
    }

    @Test
    fun aFileTooShortToHashHasNoFingerprintAndIsNotRead() = runTest {
        val calls = mutableListOf<String>()
        val hasher = StreamVideoHasher(client(calls))

        assertNull(hasher.fingerprint(Url, knownSizeBytes = 1_024))
        // Nothing is fetched: folding the same bytes in twice would not be the
        // same function, so there is no hash to go and get.
        assertEquals(0, calls.size)
    }

    private fun client(calls: MutableList<String>) = HttpClient(
        MockEngine { request ->
            if (request.method == HttpMethod.Head) {
                calls += "HEAD"
                return@MockEngine respond(
                    content = ByteArray(0),
                    headers = headersOf(HttpHeaders.ContentLength, FileBytes.toString()),
                )
            }
            val range = request.headers[HttpHeaders.Range] ?: return@MockEngine respondError(
                HttpStatusCode.BadRequest,
            )
            calls += "GET $range"
            val spec = range.removePrefix("bytes=").split('-')
            val from = spec[0].toLong()
            val to = spec[1].toLong()
            respond(
                content = ByteArray((to - from + 1).toInt()) { (from + it).toByte() },
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentRange,
                    "bytes $from-$to/$FileBytes",
                ),
            )
        },
    )

    private companion object {
        const val Url = "https://resolver.test/episode.mkv"

        /** Comfortably above the two-chunk minimum the hash requires. */
        const val FileBytes = 5_000_000L
    }
}
