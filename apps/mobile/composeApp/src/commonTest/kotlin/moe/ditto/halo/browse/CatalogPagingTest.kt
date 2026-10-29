package moe.ditto.halo.browse

import moe.ditto.halo.api.CatalogExtra
import moe.ditto.halo.api.CatalogResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogPagingTest {

    @Test
    fun aCatalogPagesWhenEitherSpellingDeclaresSkip() {
        assertTrue(catalogSupportsSkip(catalog("movie", "top", extra = listOf(CatalogExtra(name = "skip")))))
        assertTrue(catalogSupportsSkip(catalog("movie", "top", extraSupported = listOf("search", "skip"))))
        assertFalse(catalogSupportsSkip(catalog("movie", "top", extra = listOf(CatalogExtra(name = "genre")))))
        assertFalse(catalogSupportsSkip(catalog("movie", "top")))
    }

    @Test
    fun homeRowsCarryWhetherTheirCatalogPages() {
        val rows = catalogRows(
            listOf(
                addon(
                    "cinemeta",
                    catalogs = listOf(
                        catalog("movie", "top", "Popular", extraSupported = listOf("skip")),
                        catalog("series", "top", "Popular"),
                    ),
                ),
            ),
        )

        assertEquals(listOf(true, false), rows.map { it.supportsSkip })
    }

    @Test
    fun theNextPageKeepsTheRowsExtrasAndSkipsWhatTheAddonSent() {
        val paging = CatalogPaging.start(listOf(meta("tt1"), meta("tt2")), received = 50, pageable = true)

        assertEquals(mapOf("genre" to "Action", "skip" to "50"), paging.nextExtra(mapOf("genre" to "Action")))
    }

    @Test
    fun theOffsetCountsWhatTheAddonSentNotWhatWasKept() {
        // The first page lost an action entry before it reached the row, and
        // the second repeats a title: the addon still sent 3 and then 2.
        val first = CatalogPaging.start(listOf(meta("tt1"), meta("tt2")), received = 3, pageable = true)
        val next = first.append(CatalogResponse(listOf(meta("tt2"), meta("tt3")), received = 2))

        assertEquals(listOf("tt1", "tt2", "tt3"), next.items.map { it.id })
        assertEquals(5, next.received)
        assertFalse(next.exhausted)
        assertEquals("5", next.nextExtra(emptyMap())["skip"])
    }

    @Test
    fun titlesAreTheSameOnlyWhenTypeAndIdMatch() {
        val first = CatalogPaging.start(listOf(meta("tt1", type = "movie")), received = 1, pageable = true)
        val next = first.append(CatalogResponse(listOf(meta("tt1", type = "series"), meta("tt1", type = "movie"))))

        assertEquals(listOf("movie", "series"), next.items.map { it.type })
    }

    @Test
    fun aRepeatWithinOnePageIsShownOnce() {
        val paging = CatalogPaging.start(listOf(meta("tt1"), meta("tt1"), meta("tt2")), received = 3, pageable = true)

        assertEquals(listOf("tt1", "tt2"), paging.items.map { it.id })
        assertEquals(3, paging.received)
    }

    @Test
    fun anEmptyPageEndsPaging() {
        val first = CatalogPaging.start(listOf(meta("tt1")), received = 1, pageable = true)
        val next = first.append(CatalogResponse(emptyList()))

        assertTrue(next.exhausted)
        assertEquals(listOf("tt1"), next.items.map { it.id })
    }

    @Test
    fun anAddonThatIgnoresSkipStopsAfterOneRepeatedPage() {
        // Answering every request with page one must not page forever.
        val page = listOf(meta("tt1"), meta("tt2"))
        val first = CatalogPaging.start(page, received = 2, pageable = true)
        val next = first.append(CatalogResponse(page))

        assertTrue(next.exhausted)
        assertEquals(2, next.items.size)
        assertEquals(next, next.append(CatalogResponse(listOf(meta("tt9")))))
    }

    @Test
    fun aPageOfOnlyDroppedEntriesEndsPaging() {
        // Nothing new to show, so there is nothing to prove the next page differs.
        val first = CatalogPaging.start(listOf(meta("tt1")), received = 1, pageable = true)

        assertTrue(first.append(CatalogResponse(emptyList(), received = 4)).exhausted)
    }

    @Test
    fun aCatalogWithoutSkipOrWithNothingNeverPages() {
        assertTrue(CatalogPaging.start(listOf(meta("tt1")), received = 1, pageable = false).exhausted)
        assertTrue(CatalogPaging.start(emptyList(), received = 0, pageable = true).exhausted)
    }
}
