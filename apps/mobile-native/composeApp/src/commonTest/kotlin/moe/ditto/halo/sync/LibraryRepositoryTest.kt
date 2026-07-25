package moe.ditto.halo.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.cache.QueryCache
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {
    private val meta = MetaPreview(
        id = "tt0111161",
        type = "movie",
        name = "The Shawshank Redemption",
        poster = "https://images.example/poster.jpg",
    )

    @Test
    fun savesATitleUnderItsTypeAndMetaId() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        val item = repository.add(meta)

        assertEquals("movie:tt0111161", item.id)
        assertEquals("The Shawshank Redemption", item.name)
        assertEquals("https://images.example/poster.jpg", item.poster)
        assertEquals(clock.now, item.addedAt)
        assertEquals(clock.now, item.updatedAt)
        assertNull(item.removedAt)
    }

    @Test
    fun sendsOnlyTheChangedRow() = runTest {
        // Per-row merge server-side means a write need not carry the whole
        // collection, and must not: another device's newer row would be sent
        // back with this device's older timestamp.
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        repository.add(meta)

        val body = api.bodyOf(0)
        assertContains(body, "movie:tt0111161")
        assertTrue(body.startsWith("[") && body.endsWith("]"))
        assertEquals(1, api.writes.size)
    }

    @Test
    fun dropsAPosterTheServerWouldReject() = runTest {
        // A relative path fails the server's URL check, and one bad field
        // rejects the whole batch — so the row goes without its image.
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        val item = repository.add(meta.copy(poster = "/images/poster.jpg"))

        assertNull(item.poster)
        assertTrue("poster" !in api.bodyOf(0), "a rejected poster must be omitted, not sent as null")
    }

    @Test
    fun keepsTheTitleWhenAnAddonOmitsAName() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        val item = repository.add(meta.copy(name = "   "))

        // The server requires a name; losing the label beats losing the title.
        assertEquals("tt0111161", item.name)
    }

    @Test
    fun truncatesAnOverlongName() = runTest {
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        val item = repository.add(meta.copy(name = "x".repeat(600)))

        assertEquals(SyncFields.MaxNameLength, item.name.length)
    }

    @Test
    fun theSavedTitleAppearsInTheLibrary() = runTest {
        val api = echoingApi()
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repository = LibraryRepository(api.client, cache, clock)

        repository.add(meta)

        val active = repository.observeActive().first { it.value?.isNotEmpty() == true }
        assertEquals(listOf("movie:tt0111161"), active.value?.map { it.id })
    }

    @Test
    fun removingKeepsARowSoOtherDevicesLearnOfIt() = runTest {
        val stored = """[{"id":"movie:tt0111161","type":"movie","name":"The Shawshank Redemption",
            "poster":"https://images.example/poster.jpg","addedAt":1000,"updatedAt":1000}]""".trimIndent()
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val cache = QueryCache(backgroundScope, clock)
        val repository = LibraryRepository(api.client, cache, clock)
        repository.observe().first { it.value?.isNotEmpty() == true }

        val tombstone = repository.remove("movie:tt0111161")

        assertNotNull(tombstone)
        assertEquals(clock.now, tombstone.removedAt)
        // The original fields ride along: a device that has never seen this
        // title still needs a complete row to store.
        assertEquals("The Shawshank Redemption", tombstone.name)
        assertEquals(1000L, tombstone.addedAt)
    }

    @Test
    fun tombstonesAreHiddenFromTheLibraryButKeptInSync() = runTest {
        val stored = """[{"id":"movie:tt1","type":"movie","name":"Kept","addedAt":1,"updatedAt":1},
            {"id":"movie:tt2","type":"movie","name":"Removed","addedAt":1,"removedAt":2,"updatedAt":2}]""".trimIndent()
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        val all = repository.observe().first { it.value?.isNotEmpty() == true }
        val active = repository.observeActive().first { it.value?.isNotEmpty() == true }

        assertEquals(2, all.value?.size)
        assertEquals(listOf("Kept"), active.value?.map { it.name })
    }

    @Test
    fun removingAnUnknownTitleWritesNothing() = runTest {
        // Inventing a tombstone would push a fabricated name and added date to
        // every other device.
        val api = RecordingApi { """[]""" }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)

        assertNull(repository.remove("movie:never-seen"))
        assertEquals(0, api.writes.size)
    }

    @Test
    fun addingAgainClearsTheTombstoneAndRestartsTheAddedDate() = runTest {
        val stored = """[{"id":"movie:tt0111161","type":"movie","name":"The Shawshank Redemption",
            "addedAt":1000,"removedAt":2000,"updatedAt":2000}]""".trimIndent()
        val api = RecordingApi { stored }
        val clock = FakeClock()
        val repository = LibraryRepository(api.client, QueryCache(backgroundScope, clock), clock)
        repository.observe().first { it.value?.isNotEmpty() == true }

        val item = repository.add(meta)

        assertNull(item.removedAt)
        assertEquals(clock.now, item.addedAt)
        assertTrue("removedAt" !in api.bodyOf(0), "the tombstone must be cleared, not resent")
    }
}
