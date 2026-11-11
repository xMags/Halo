package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestHeadersTest {
    @Test
    fun usableHeadersAreKeptAsTheyAreCommasIncluded() {
        val headers = mapOf("Referer" to "https://site.test/", "Cookie" to "a=1, b=2")

        assertEquals(headers, playbackHeaders(headers))
        assertEquals(listOf("Referer: https://site.test/", "Cookie: a=1, b=2"), headerLines(headers))
    }

    @Test
    fun headersThatCouldBreakOutOfTheirLineOrAreNotPlainNamesAreDropped() {
        val unsafe = mapOf("X-A" to "ok\r\nInjected: 1", "Bad Name" to "x", "X-B:" to "x")

        assertEquals(emptyMap(), playbackHeaders(unsafe))
    }

    @Test
    fun headersThePlayerAndTheConnectionOwnAreNeverTakenFromAnAddon() {
        val headers = mapOf("Range" to "bytes=0-1", "HOST" to "evil.test", "Referer" to "https://a.test/")

        assertEquals(mapOf("Referer" to "https://a.test/"), playbackHeaders(headers))
    }

    @Test
    fun headersSurviveTheTripThroughARoute() {
        val headers = mapOf("Referer" to "https://site.test/", "Authorization" to "Bearer abc")

        assertEquals(headers, decodeRequestHeaders(encodeRequestHeaders(headers)))
        assertNull(encodeRequestHeaders(emptyMap()))
        assertNull(encodeRequestHeaders(null))
        assertEquals(emptyMap(), decodeRequestHeaders(null))
        assertEquals(emptyMap(), decodeRequestHeaders("{not json"))
    }
}
