package moe.ditto.halo.browse

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop app's `tests/historyRows.test.ts` cases, so both apps list history alike. */
class HistoryRowsTest {

    @Test
    fun everyPlayedVideoIsItsOwnRowNewestFirstFinishedOrNot() {
        val history = buildHistory(
            listOf(
                row("series:a", "a:1:1", 10, name = "Show", watched = true),
                row("series:a", "a:1:2", 30, name = "Show"),
                row("movie:b", "b", 20, name = "Film", positionSec = 2990.0),
            ),
            emptyList(),
            null,
        )
        assertEquals(
            listOf(Triple("a:1:2", "a", false), Triple("b", "b", true), Triple("a:1:1", "a", true)),
            history.map { Triple(it.videoId, it.metaId, it.finished) },
        )
    }

    @Test
    fun theFilterAppliesAndAnythingThatIsNotASeriesCountsAsAMovie() {
        val states = listOf(
            row("series:a", "a:1:1", 3),
            row("movie:b", "b", 2),
            row("anime:kitsu:1", "kitsu:1:1", 1),
        )
        assertEquals(listOf("movie:b", "anime:kitsu:1"), buildHistory(states, emptyList(), "movie").map { it.itemId })
        assertEquals(listOf("series:a"), buildHistory(states, emptyList(), "series").map { it.itemId })
        assertEquals("kitsu:1", buildHistory(states, emptyList(), null).last().metaId)
    }

    @Test
    fun theLibraryOnlyLendsANameAndPosterAndUnnamedRowsAreLeftOut() {
        val library = listOf(
            LibraryItem(id = "movie:b", type = "movie", name = "Saved name", poster = "https://p.test/b.jpg", addedAt = 1, updatedAt = 1),
        )
        val history = buildHistory(listOf(row("movie:b", "b", 2, name = null), row("movie:c", "c", 1, name = null)), library, null)
        assertEquals(listOf("Saved name" to "https://p.test/b.jpg"), history.map { it.name to it.poster })
    }

    @Test
    fun rowsGroupIntoTodayYesterdayEarlierThisWeekAndMonths() {
        val zone = TimeZone.of("Asia/Kolkata")
        fun at(month: Int, day: Int, hour: Int = 12) = LocalDateTime(2026, month, day, hour, 0).toInstant(zone).toEpochMilliseconds()
        val now = at(10, 29, 15)
        val entries = listOf(at(10, 29), at(10, 29, 0), at(10, 28, 23), at(10, 24), at(10, 20), at(10, 2), at(9, 30))
            .mapIndexed { index, updatedAt -> entry(index, updatedAt) }
        assertEquals(
            listOf("Today" to 2, "Yesterday" to 1, "Earlier this week" to 1, "October 2026" to 2, "September 2026" to 1),
            groupHistory(entries, now, zone).map { it.label to it.entries.size },
        )
        assertEquals("23:00", historyTime(at(10, 28, 23), now, zone))
        assertEquals("Saturday", historyTime(at(10, 24), now, zone))
        assertEquals("20 Oct", historyTime(at(10, 20), now, zone))
    }

    @Test
    fun aVideoIdNamesItsEpisode() {
        assertEquals("S01E02", historyVideoTag("tt1:1:2", "tt1"))
        assertEquals("S03E10", historyVideoTag("kitsu:1:3:10", "kitsu:1"))
        assertEquals("MOVIE", historyVideoTag("tt9", "tt9"))
        assertEquals("SERIES", historyVideoTag("kitsu:1:5", "kitsu:1"))
    }

    private fun row(
        itemId: String,
        videoId: String,
        updatedAt: Long,
        name: String? = "Title",
        watched: Boolean = false,
        positionSec: Double = 600.0,
    ) = WatchState(
        videoId = videoId,
        itemId = itemId,
        positionSec = positionSec,
        durationSec = 3000.0,
        watched = watched,
        name = name,
        updatedAt = updatedAt,
    )

    private fun entry(index: Int, updatedAt: Long) = HistoryEntry(
        itemId = "movie:$index",
        type = "movie",
        metaId = "$index",
        videoId = "$index",
        name = "x",
        poster = null,
        positionSec = 0.0,
        durationSec = 0.0,
        finished = false,
        updatedAt = updatedAt,
    )
}
