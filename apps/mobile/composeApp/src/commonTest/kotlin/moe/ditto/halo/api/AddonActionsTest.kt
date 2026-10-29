package moe.ditto.halo.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonActionsTest {
    @Test
    fun mediaFusionsDeleteAllEntryIsNotATitle() {
        assertTrue(isActionEntry("dltorbox", "🗑️💩 Delete all files"))
        assertTrue(isActionEntry("dlrealdebrid", "Anything"))
    }

    @Test
    fun anActionNamedAnyOtherWayIsStillCaught() {
        listOf("🗑 Empty", "Clear all watched", "Remove all torrents", "Wipe the library", "Purge cache")
            .forEach { assertTrue(isActionEntry("x:1", it), it) }
    }

    @Test
    fun realTitlesStayWhateverTheirIdOrName() {
        listOf(
            "tt12735488" to "Kalki 2898-AD",
            "kitsu:1234" to "Delete",
            "dl:42" to "Download Days",
            "tt1" to "All Clear",
            "tt2" to "Deleted Scenes",
            "tt3" to "The Purge: Election Year",
            "tt4" to "Delete History",
        ).forEach { (id, name) -> assertFalse(isActionEntry(id, name), name) }
        val kept = CatalogResponse(
            metas = listOf(
                MetaPreview(id = "dltorbox", type = "movie", name = "🗑️💩 Delete all files"),
                MetaPreview(id = "tt1", type = "movie", name = "Minions & Monsters"),
            ),
        ).withoutActions()
        assertEquals(listOf("tt1"), kept.metas.map { it.id })
    }

    @Test
    fun aStreamThatWouldDeleteIsNeverOfferedHoweverItIsLabelled() {
        val delete = Stream(url = "https://mf.test/streaming_provider/s/delete_all_watchlist?provider=torbox", name = "TorBox")
        assertTrue(isActionStream(delete))
        assertTrue(isActionStream(Stream(url = "https://cdn.test/v.mkv", description = "🚨💀⚠ Delete all files in TorBox watchlist.")))
        assertFalse(isActionStream(Stream(url = "https://cdn.test/Delete.History.2024.1080p.mkv", name = "Delete History")))
        assertFalse(isActionStream(Stream(url = "https://cdn.test/Deleted.Scenes.mkv", title = "Deleted Scenes")))

        val film = Stream(url = "https://cdn.test/film.mkv", name = "Film")
        val source = AddonSource(id = "a", name = "MediaFusion")
        val result = StreamsResult(
            results = listOf(
                AddonStreams(addon = source, streams = listOf(film, delete)),
                AddonStreams(addon = source.copy(id = "b"), streams = listOf(delete)),
            ),
        ).withoutActions()
        assertEquals(listOf(listOf(film)), result.results.map { it.streams })
        assertNull(NextEpisodeResult(stream = delete).withoutActions().stream)
    }
}
