package moe.ditto.halo.sync

import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop app's `tests/watchMarks.test.ts` rules, so both apps write the same rows. */
class WatchMarksTest {
    private val target = WatchMarkTarget(videoId = "tt1:1:2", itemId = "series:tt1", name = "Show")

    @Test
    fun watchedKeepsAMeasuredLengthElseTheRuntimeAndIsStrictlyNewer() {
        val existing = state(durationSec = 1200.0, updatedAt = 500)
        val measured = watchedRow(target, existing, runtimeSec = 3000.0, now = 100)
        assertEquals(1200.0, measured.positionSec)
        assertEquals(1200.0, measured.durationSec)
        assertEquals(true, measured.watched)
        assertEquals(501, measured.updatedAt)

        assertEquals(3000.0, watchedRow(target, null, runtimeSec = 3000.0, now = 100).durationSec)
        assertEquals(0.0, watchedRow(target, null, runtimeSec = null, now = 100).durationSec)
    }

    @Test
    fun unwatchedClearsProgressButKeepsTheDisplayFields() {
        val row = unwatchedRow(target.copy(name = ""), state(poster = "https://p.test/a.jpg"), now = 900)
        assertEquals(0.0, row.positionSec)
        assertEquals(0.0, row.durationSec)
        assertEquals(false, row.watched)
        assertEquals("Old name", row.name)
        assertEquals("https://p.test/a.jpg", row.poster)
    }

    @Test
    fun severalMarksAreStampedInOrder() {
        val rows = watchedRowsInOrder(
            listOf(target, target.copy(videoId = "tt1:1:3")),
            emptyMap(),
            runtimeSec = null,
            now = 100,
        )
        assertEquals(listOf(100L, 101L), rows.map { it.updatedAt })
    }

    @Test
    fun aRemovalKeepsTheRowAsItWasStampedRemovedAndNewer() {
        val row = state(updatedAt = 100)
        assertEquals(listOf(row.copy(removedAt = 500, updatedAt = 500)), removedRows(listOf(row), now = 500))
        assertEquals(101L, removedRows(listOf(row), now = 50).single().removedAt)
    }

    @Test
    fun earlierEpisodesRunThroughEarlierSeasonsAndSkipSpecials() {
        val videos = listOf(video(1, 1), video(1, 2), video(2, 1), video(2, 2), video(0, 1), MetaVideo(id = "x"))
        assertEquals(listOf("s1e1", "s1e2", "s2e1"), earlierEpisodes(videos, video(2, 2)).map { it.id })
        assertEquals(emptyList(), earlierEpisodes(videos, video(0, 1)).map { it.id })
        assertEquals(emptyList(), earlierEpisodes(videos, MetaVideo(id = "x")))
    }

    @Test
    fun runtimesParseTheAddonSpellingsAndNothingElse() {
        assertEquals(48, runtimeMinutes("48 min"))
        assertEquals(166, runtimeMinutes("2 h 46 min"))
        assertEquals(120, runtimeMinutes("2h"))
        assertEquals(95, runtimeMinutes("95"))
        assertNull(runtimeMinutes("soon"))
        assertNull(runtimeMinutes(null))
    }

    private fun state(durationSec: Double = 0.0, poster: String? = null, updatedAt: Long = 1) = WatchState(
        videoId = target.videoId,
        itemId = target.itemId,
        positionSec = 90.0,
        durationSec = durationSec,
        watched = false,
        name = "Old name",
        poster = poster,
        updatedAt = updatedAt,
    )

    private fun video(season: Int, episode: Int) = MetaVideo(id = "s${season}e$episode", season = season, episode = episode)
}
