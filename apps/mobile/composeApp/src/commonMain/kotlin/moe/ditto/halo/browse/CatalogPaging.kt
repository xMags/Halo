package moe.ditto.halo.browse

import moe.ditto.halo.api.CatalogResponse
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaCard

/** The extra an addon reads as "the entries I have already sent you". */
const val SkipExtra = "skip"

/**
 * Whether a catalog answers past its first page. Both spellings count, as for
 * search: the `extra` array, and the older `extraSupported` list. A catalog
 * that declares neither may ignore `skip` or reject it, so it is never paged.
 */
fun catalogSupportsSkip(catalog: ManifestCatalog): Boolean =
    catalog.extra.any { it.name == SkipExtra } || catalog.extraSupported.contains(SkipExtra)

/** The catalog a "See all" grid asks for further pages, with the extras its row was asked with. */
data class CatalogPageSource(
    val addonId: String,
    val type: String,
    val catalogId: String,
    /** A language row's language and the like, sent again with every page. */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * A paged catalog's titles so far, and where the next page starts.
 *
 * [received] counts what the addon sent, not what is shown: Halo drops action
 * entries and repeats, but the addon's `skip` is an offset into its own list,
 * so counting only the kept titles would ask for entries already received.
 *
 * Paging ends at an empty page, and at a page that adds no title not already
 * shown. The second is what keeps an addon that ignores `skip` and answers
 * every request with its first page from being asked forever.
 */
data class CatalogPaging(
    val items: List<MetaCard>,
    val received: Int,
    val exhausted: Boolean,
) {
    /** The extras for the next page: the row's own, plus the offset. */
    fun nextExtra(base: Map<String, String>): Map<String, String> = base + (SkipExtra to received.toString())

    /** This state with [page] appended, or exhausted when it brought nothing new. */
    fun append(page: CatalogResponse): CatalogPaging {
        if (exhausted) return this
        val fresh = newTitles(items, page.metas)
        if (page.receivedCount == 0 || fresh.isEmpty()) return copy(exhausted = true)
        return CatalogPaging(items = items + fresh, received = received + page.receivedCount, exhausted = false)
    }

    companion object {
        /**
         * The first page as its row received it. [pageable] false (a catalog
         * without `skip`) starts exhausted, which leaves the grid single-page.
         */
        fun start(first: List<MetaCard>, received: Int, pageable: Boolean): CatalogPaging =
            CatalogPaging(
                items = newTitles(emptyList(), first),
                received = received,
                exhausted = !pageable || received == 0,
            )
    }
}

/** The titles of [page] not in [shown] nor earlier in [page] itself, by type and id, in order. */
private fun newTitles(shown: List<MetaCard>, page: List<MetaCard>): List<MetaCard> {
    val seen = shown.mapTo(HashSet<String>()) { titleKey(it) }
    return page.filter { seen.add(titleKey(it)) }
}
