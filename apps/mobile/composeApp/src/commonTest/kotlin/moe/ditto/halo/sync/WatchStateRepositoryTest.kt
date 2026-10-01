package moe.ditto.halo.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.cache.QueryCache
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStateRepositoryTest {
    private fun repository(
        api: RecordingApi,
        clock: FakeClock,
        cache: QueryCache,
    ) = WatchStateRepository(api.client, cache, clock)

    @Test
    fun recordsProgressForAVideo() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repo = repository(api, clock, QueryCache(backgroundScope, clock))

        val state = repo.report(
            videoId = "tt0944947:1:2",
            itemId = "series:tt0944947",
            positionSec = 725.4,
            durationSec = 3180.9,
            name = "Game of Thrones",
            poster = "https://images.example/got.jpg",
        )

        assertNotNull(state)
        assertEquals("tt0944947:1:2", state.videoId)
        // Whole seconds: sub-second precision cannot change where playback
        // resumes, and keeps repeated reports from churning the row.
        assertEquals(725.0, state.positionSec)
        assertEquals(3180.0, state.durationSec)
        assertEquals(false, state.watched)
        assertEquals(clock.now, state.updatedAt)
    }

    @Test
    fun ignoresClipsTooShortToResume() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repo = repository(api, clock, QueryCache(backgroundScope, clock))

        val state = repo.report("v", "i", positionSec = 30.0, durationSec = 45.0)

        assertNull(state)
        assertEquals(0, api.writes.size)
    }

    @Test
    fun ignoresTheFirstFewSecondsOfPlayback() = runTest {
        // Opening the wrong episode and backing out should not put it in the
        // continue-watching row.
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repo = repository(api, clock, QueryCache(backgroundScope, clock))

        assertNull(repo.report("v", "i", positionSec = 3.0, durationSec = 3600.0))
        assertEquals(0, api.writes.size)
    }

    @Test
    fun marksAVideoWatchedNearTheEnd() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repo = repository(api, clock, cache)

        val justBefore = repo.report("v1", "i", positionSec = 890.0, durationSec = 1000.0)
        val atThreshold = repo.report("v2", "i", positionSec = 900.0, durationSec = 1000.0)

        assertEquals(false, justBefore?.watched)
        assertEquals(true, atThreshold?.watched)
    }

    @Test
    fun dropsDisplayFieldsTheServerWouldReject() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repo = repository(api, clock, QueryCache(backgroundScope, clock))

        val state = repo.report(
            videoId = "v",
            itemId = "i",
            positionSec = 100.0,
            durationSec = 1000.0,
            name = "  ",
            poster = "poster.jpg",
        )

        assertNotNull(state)
        assertNull(state.name)
        assertNull(state.poster)
        val body = api.bodyOf(0)
        assertTrue("poster" !in body && "\"name\"" !in body, "rejected fields must be omitted: $body")
    }

    @Test
    fun sendsOnlyTheChangedRow() = runTest {
        val stored = """[{"videoId":"other","itemId":"i","positionSec":10.0,"durationSec":100.0,
            "watched":false,"updatedAt":1}]""".trimIndent()
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repo = repository(api, clock, cache)
        repo.observe().first { it.value?.isNotEmpty() == true }

        repo.report("v", "i", positionSec = 100.0, durationSec = 1000.0)

        val body = api.bodyOf(0)
        assertContains(body, "\"videoId\":\"v\"")
        assertTrue("other" !in body, "an unrelated row must not be echoed back with our timestamp: $body")
    }

    @Test
    fun replacesTheEarlierSampleForTheSameVideo() = runTest {
        val api = echoingApi()
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repo = repository(api, clock, cache)

        repo.report("v", "i", positionSec = 100.0, durationSec = 1000.0)
        clock.now += 15_000
        repo.report("v", "i", positionSec = 200.0, durationSec = 1000.0)

        assertEquals(200.0, repo.progressFor("v")?.positionSec)
    }
}
