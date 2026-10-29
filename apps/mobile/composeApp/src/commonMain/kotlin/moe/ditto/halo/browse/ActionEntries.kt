package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaPreview

/*
 * Catalog entries that are buttons, not titles.
 *
 * Some addons put actions into their catalogs as fake items. MediaFusion's
 * watchlist rows open with "🗑️💩 Delete all files", id `dl<service>`
 * (`dltorbox`), whose one "stream" deletes every file in the debrid account.
 * A title row is no place for that, so such entries are dropped as catalogs
 * arrive, before any row, grid or search result can draw one.
 *
 * Two signs, either enough: MediaFusion's action id shape (`dl` and letters,
 * with no colon; real ids are IMDb `tt…` or `prefix:…`), and a name that reads
 * as a bulk action rather than a title.
 */
private val ActionId = Regex("^dl[a-z]+$")
private val ActionName = Regex("""\b(delete|remove|clear)\s+all\b""", RegexOption.IGNORE_CASE)

internal fun isActionEntry(id: String, name: String): Boolean =
    ActionId.matches(id) || ActionName.containsMatchIn(name)

internal fun List<MetaPreview>.withoutActionEntries(): List<MetaPreview> =
    filterNot { isActionEntry(it.id, it.name) }
