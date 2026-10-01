package moe.ditto.halo.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.auth.EpochClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class TestKey(override val id: String) : QueryKey

private class TestClock(var now: Long = 1_000L) : EpochClock {
    override fun nowMs(): Long = now
}

class QueryCacheTest {
    @Test
    fun fetchesAndPublishesTheValue() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())

        val state = cache.query(TestKey("a"), staleMs = 0) { "value" }.first { it.value != null }

        assertEquals("value", state.value)
        assertNull(state.error)
    }

    @Test
    fun servesAFreshValueWithoutRefetching() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        var calls = 0

        cache.query(key, staleMs = 60_000) { calls++; "first" }.first { it.value != null }
        val second = cache.query(key, staleMs = 60_000) { calls++; "second" }.first { it.value != null }

        assertEquals("first", second.value)
        assertEquals(1, calls)
    }

    @Test
    fun servesTheStaleValueBeforeTheFreshOne() = runTest {
        val clock = TestClock()
        val cache = QueryCache(backgroundScope, clock)
        val key = TestKey("a")
        cache.query(key, staleMs = 60_000) { "first" }.first { it.value != null }

        clock.now += 120_000
        val query = cache.query(key, staleMs = 60_000) { "second" }

        // The point of stale-while-revalidate: a revisit renders immediately
        // from cache rather than blanking while the request runs.
        assertEquals("first", query.first().value)
        assertEquals("second", query.first { it.value == "second" }.value)
    }

    @Test
    fun neverFetchedIsStaleEvenUnderAnUnboundedWindow() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        var calls = 0

        // Comparing age alone would read a never-fetched entry as fresh here
        // and the first read would hang forever.
        cache.query(key, staleMs = HaloKey.Forever) { calls++; "once" }.first { it.value != null }
        cache.query(key, staleMs = HaloKey.Forever) { calls++; "again" }.first { it.value != null }

        assertEquals(1, calls)
    }

    @Test
    fun concurrentReadersShareOneRequest() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        val gate = CompletableDeferred<String>()
        var calls = 0
        val fetch: suspend () -> String = { calls++; gate.await() }

        val first = async { cache.query(key, staleMs = 0, fetch = fetch).first { it.value != null } }
        testScheduler.runCurrent()
        val second = async { cache.query(key, staleMs = 0, fetch = fetch).first { it.value != null } }
        testScheduler.runCurrent()
        gate.complete("shared")

        assertEquals("shared", first.await().value)
        assertEquals("shared", second.await().value)
        assertEquals(1, calls)
    }

    @Test
    fun oneFailedRequestDoesNotTearDownTheCache() = runTest {
        // A failing async cancels its parent unless the parent supervises, so
        // without that the first failure would kill every other query.
        val cache = QueryCache(backgroundScope, TestClock())

        val failed = cache.query<String>(TestKey("a"), staleMs = 0) {
            throw IllegalStateException("boom")
        }.first { it.error != null }

        assertNotNull(failed.error)
        assertTrue(backgroundScope.isActive)

        val other = cache.query(TestKey("b"), staleMs = 0) { "unaffected" }.first { it.value != null }
        assertEquals("unaffected", other.value)

        val recovered = cache.query(TestKey("a"), staleMs = 0) { "recovered" }.first { it.value != null }
        assertEquals("recovered", recovered.value)
    }

    @Test
    fun aFailureKeepsTheLastGoodValueVisible() = runTest {
        val clock = TestClock()
        val cache = QueryCache(backgroundScope, clock)
        val key = TestKey("a")
        cache.query(key, staleMs = 60_000) { "good" }.first { it.value != null }

        clock.now += 120_000
        val state = cache.query<String>(key, staleMs = 60_000) {
            throw IllegalStateException("offline")
        }.first { it.error != null }

        assertEquals("good", state.value)
        assertNotNull(state.error)
    }

    @Test
    fun invalidatingAnObservedKeyRefetchesIt() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        var calls = 0
        val seen = mutableListOf<String?>()
        val observer = launch {
            cache.query(key, staleMs = HaloKey.Forever) { "v${++calls}" }.collect { seen += it.value }
        }
        testScheduler.runCurrent()
        assertEquals(1, calls)

        cache.invalidate(key)
        testScheduler.runCurrent()

        assertEquals(2, calls)
        assertTrue("v2" in seen)
        observer.cancel()
    }

    @Test
    fun invalidatingAnUnobservedKeyOnlyMarksItStale() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        var calls = 0
        cache.query(key, staleMs = HaloKey.Forever) { calls++; "v" }.first { it.value != null }

        cache.invalidate(key)
        testScheduler.runCurrent()

        // Nothing was watching, so no request was made...
        assertEquals(1, calls)
        // ...but the next reader gets fresh data rather than the stale entry.
        cache.query(key, staleMs = HaloKey.Forever) { calls++; "refetched" }.first { it.value == "refetched" }
        assertEquals(2, calls)
    }

    @Test
    fun aDirectWriteSupersedesAnInFlightFetch() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        val gate = CompletableDeferred<String>()
        val observer = launch { cache.query(key, staleMs = 0) { gate.await() }.collect { } }
        testScheduler.runCurrent()

        cache.set(key, "written")
        gate.complete("late arrival")
        testScheduler.runCurrent()

        assertEquals("written", cache.peek<String>(key))
        observer.cancel()
    }

    @Test
    fun aWriteSupersedesAFetchThatWasAlreadyRunning() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        val gate = CompletableDeferred<String>()
        val observer = launch { cache.query(key, staleMs = 0) { gate.await() }.collect { } }
        testScheduler.runCurrent()

        cache.mutate<String>(key, optimistic = { "optimistic" }, put = { "echo" })
        gate.complete("stale read")
        testScheduler.runCurrent()

        assertEquals("echo", cache.peek<String>(key))
        observer.cancel()
    }

    @Test
    fun aWriteIsVisibleBeforeItsRequestCompletes() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        val gate = CompletableDeferred<String>()

        val write = async {
            cache.mutate<String>(key, optimistic = { "optimistic" }, put = { gate.await() })
        }
        testScheduler.runCurrent()

        assertEquals("optimistic", cache.peek<String>(key))
        gate.complete("echo")
        assertEquals("echo", write.await())
        assertEquals("echo", cache.peek<String>(key))
    }

    @Test
    fun overlappingWritesBuildOnEachOtherAndTheLateEchoIsDropped() = runTest {
        // The failure this prevents: two rapid edits race, the later request
        // carries only its own field, and the earlier one's change is lost
        // under a newer timestamp. Each request must carry the merged state,
        // and a response that arrives after a newer write must not be applied.
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("settings")
        cache.set(key, emptyList<String>())
        val firstGate = CompletableDeferred<List<String>>()
        val secondGate = CompletableDeferred<List<String>>()
        val sent = mutableListOf<List<String>>()

        val first = async {
            cache.mutate<List<String>>(
                key,
                optimistic = { (it ?: emptyList()) + "audio" },
                put = { merged -> sent += merged; firstGate.await() },
            )
        }
        testScheduler.runCurrent()
        val second = async {
            cache.mutate<List<String>>(
                key,
                optimistic = { (it ?: emptyList()) + "subtitles" },
                put = { merged -> sent += merged; secondGate.await() },
            )
        }
        testScheduler.runCurrent()

        // The second request carries the first's change too.
        assertEquals(listOf(listOf("audio"), listOf("audio", "subtitles")), sent)

        // Responses come back out of order: the newer write lands first.
        secondGate.complete(listOf("audio", "subtitles"))
        testScheduler.runCurrent()
        firstGate.complete(listOf("audio"))
        second.await()
        first.await()
        testScheduler.runCurrent()

        // The late echo is a complete snapshot of older server state; applying
        // it would silently drop the subtitle preference.
        assertEquals(listOf("audio", "subtitles"), cache.peek<List<String>>(key))
    }

    @Test
    fun aResolverMayRefuseAnEchoOlderThanTheCachedValue() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("settings")
        cache.set(key, 50L)

        val result = cache.mutate<Long>(
            key,
            optimistic = { 100L },
            put = { 20L },
            resolve = { current, echo -> if (current != null && current > echo) current else echo },
        )

        assertEquals(20L, result)
        assertEquals(100L, cache.peek<Long>(key))
    }

    @Test
    fun aFailedWriteIsNotRolledBackButIsMarkedStale() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("a")
        cache.set(key, "server")

        assertFailsWith<IllegalStateException> {
            cache.mutate<String>(key, optimistic = { "optimistic" }, put = { throw IllegalStateException("offline") })
        }

        // Restoring the pre-write snapshot could discard a different write that
        // landed in the meantime, so the value stands and the entry is stale.
        assertEquals("optimistic", cache.peek<String>(key))
        val refreshed = cache.query(key, staleMs = HaloKey.Forever) { "server truth" }
            .first { it.value == "server truth" }
        assertEquals("server truth", refreshed.value)
    }

    @Test
    fun disabledQueriesObserveWithoutFetching() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())
        val key = TestKey("search")
        var calls = 0

        val state = cache.query(key, staleMs = 0, enabled = false) { calls++; "results" }.first()

        assertNull(state.value)
        assertEquals(0, calls)

        cache.query(key, staleMs = 0, enabled = true) { calls++; "results" }.first { it.value != null }
        assertEquals(1, calls)
    }

    @Test
    fun distinctKeysDoNotShareState() = runTest {
        val cache = QueryCache(backgroundScope, TestClock())

        val movie = HaloKey.Catalog(addonId = "a1", type = "movie", catalogId = "top")
        val series = HaloKey.Catalog(addonId = "a1", type = "series", catalogId = "top")
        cache.query(movie, movie.staleMs) { "movies" }.first { it.value != null }
        cache.query(series, series.staleMs) { "series" }.first { it.value != null }

        assertEquals("movies", cache.peek<String>(movie))
        assertEquals("series", cache.peek<String>(series))
    }
}
