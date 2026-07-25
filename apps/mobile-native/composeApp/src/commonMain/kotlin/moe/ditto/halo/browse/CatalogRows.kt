package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry

/**
 * One browsable row on Home: a catalog that can be rendered without asking the
 * user anything first.
 */
data class CatalogRowSpec(
    /** Stable across addon reinstalls of the same catalog; safe as a list key. */
    val key: String,
    val addonId: String,
    val type: String,
    val catalogId: String,
    /** Rendered heading, e.g. "Popular · Movies". */
    val title: String,
)

/**
 * Home's rows, in resolution order.
 *
 * Only catalogs that need no input qualify. One gated on a search term or a
 * required genre has nothing to show until it is asked a question, and Home
 * asks none — it would render an empty heading forever. Both spellings of that
 * gate are checked because addons use either: the `extra` array's `isRequired`
 * flag, and the older parallel `extraRequired` list.
 *
 * Addons whose catalogs the user hid need no handling here. The server strips
 * those from the manifest before it reaches this client, so they are already
 * absent rather than filtered.
 */
fun catalogRows(addons: List<AddonEntry>): List<CatalogRowSpec> = addons.flatMap { addon ->
    addon.manifest.catalogs
        .filter { catalog -> catalog.extra.none { it.isRequired } && catalog.extraRequired.isEmpty() }
        .map { catalog ->
            CatalogRowSpec(
                key = "${addon.id}/${catalog.type}/${catalog.id}",
                addonId = addon.id,
                type = catalog.type,
                catalogId = catalog.id,
                // A catalog that did not name itself borrows its addon's name;
                // the type suffix is what keeps one addon's several catalogs
                // apart when they all fall back to it.
                title = "${catalog.name ?: addon.manifest.name} · ${collectionTypeLabel(catalog.type)}",
            )
        }
}

/**
 * Plural label for a whole shelf ("Movies"). An unrecognised type is shown as
 * the addon spelled it rather than dropped — the protocol lets addons invent
 * types, and a row titled with a raw type still reads better than no row.
 */
fun collectionTypeLabel(type: String): String = when (type) {
    "movie" -> "Movies"
    "series" -> "Series"
    else -> type
}

/** Singular label for one result set ("Movie"), used by search group headings. */
fun itemTypeLabel(type: String): String = type.replaceFirstChar { it.uppercase() }
