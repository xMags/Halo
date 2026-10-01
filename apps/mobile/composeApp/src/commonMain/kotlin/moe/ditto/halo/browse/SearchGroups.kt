package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaPreview

/**
 * Below this a query matches almost everything, so the fan-out costs one
 * request per installed catalog and returns noise.
 */
const val MinSearchTermLength = 2

/** One catalog that can answer a query, and the heading its results get. */
data class SearchTarget(
    val key: String,
    val addonId: String,
    val type: String,
    val catalogId: String,
    val title: String,
)

/** One catalog's answer to a query. Every responding catalog owns a row. */
data class SearchResultGroup(
    val key: String,
    val title: String,
    val metas: List<MetaPreview>,
)

/**
 * Saved titles matching [term], newest first.
 *
 * Answered from what the library cache already holds, so it costs no request and
 * lands before any addon can answer — which is why it leads the results: the one
 * thing the user has definitely seen before should not wait behind a third-party
 * catalog.
 *
 * Matching is a case-insensitive substring of the name, the same forgiveness a
 * catalog search gives. Tombstones are excluded: a removed title is not saved,
 * and offering it back under "My Library" would misreport what is in it.
 */
fun savedSearchMatches(library: List<LibraryItem>?, term: String): List<LibraryItem> {
    val trimmed = term.trim()
    if (trimmed.length < MinSearchTermLength) return emptyList()
    return library.orEmpty()
        .filterNot { it.isRemoved }
        .filter { it.name.contains(trimmed, ignoreCase = true) }
        .sortedByDescending { it.addedAt }
}

/**
 * Every catalog that advertises the `search` extra, in resolution order.
 *
 * Results are deliberately not merged into one list. A catalog is a curated
 * view, so "Popular – Movie" and "Top – Series" answering the same query are
 * two different statements about it, and flattening them would throw away which
 * addon vouched for what.
 */
fun searchTargets(addons: List<AddonEntry>): List<SearchTarget> = addons.flatMap { addon ->
    addon.manifest.catalogs
        .filter { it.supportsSearch }
        .map { catalog ->
            SearchTarget(
                key = "${addon.id}/${catalog.type}/${catalog.id}",
                addonId = addon.id,
                type = catalog.type,
                catalogId = catalog.id,
                title = "${catalog.name ?: addon.manifest.name} – ${itemTypeLabel(catalog.type)}",
            )
        }
}

/**
 * Pairs each target with what it returned, dropping the ones that have nothing
 * to say: a null entry is a catalog whose request failed, and an empty one had
 * no matches. Neither earns a heading.
 *
 * [results] must be positionally aligned with [targets].
 *
 * Duplicates are removed *within* a group but deliberately kept across groups.
 * Two addons knowing the same title is the normal case, and each row is that
 * addon's own answer — deduplicating globally would silently empty whichever
 * row happened to be resolved second.
 */
fun buildSearchGroups(
    targets: List<SearchTarget>,
    results: List<List<MetaPreview>?>,
): List<SearchResultGroup> {
    require(targets.size == results.size) {
        "Expected one result slot per target, got ${results.size} for ${targets.size}"
    }
    return targets.mapIndexedNotNull { index, target ->
        val metas = results[index]?.distinctBy { "${it.type}:${it.id}" }.orEmpty()
        if (metas.isEmpty()) null else SearchResultGroup(target.key, target.title, metas)
    }
}
