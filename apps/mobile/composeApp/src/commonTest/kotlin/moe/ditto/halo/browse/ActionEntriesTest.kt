package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.ditto.halo.api.MetaPreview

class ActionEntriesTest {
    @Test
    fun mediaFusionsDeleteAllEntryIsNotATitle() {
        assertTrue(isActionEntry("dltorbox", "🗑️💩 Delete all files"))
        assertTrue(isActionEntry("dlrealdebrid", "Something"))
        assertTrue(isActionEntry("other", "Clear all watched"))
    }

    @Test
    fun realTitlesStayWhateverTheirId() {
        listOf(
            "tt12735488" to "Kalki 2898-AD",
            "kitsu:1234" to "Delete",
            "dl:42" to "Download Days",
            "tt1" to "All Clear",
            "tt2" to "Deleted Scenes",
        ).forEach { (id, name) -> assertFalse(isActionEntry(id, name), name) }
        val kept = listOf(
            MetaPreview(id = "dltorbox", type = "movie", name = "🗑️💩 Delete all files"),
            MetaPreview(id = "tt1", type = "movie", name = "Minions & Monsters"),
        ).withoutActionEntries()
        assertEquals(listOf("tt1"), kept.map { it.id })
    }
}
