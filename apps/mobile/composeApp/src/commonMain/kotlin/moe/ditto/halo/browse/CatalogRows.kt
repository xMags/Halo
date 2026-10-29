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
    /** Rendered heading, e.g. "Popular Movies". */
    val title: String,
    /** The addon it comes from, as the mono line beside the heading, e.g. "CINEMETA". */
    val source: String,
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
 * Only movie and series catalogs make rows, as on the desktop app. Debrid
 * addons also publish catalogs of their own types (TorBox's "other" lists the
 * account's raw torrents and downloads), which are file listings rather than
 * titles to browse and never belong on Home.
 *
 * Addons whose catalogs the user hid need no handling here. The server strips
 * those from the manifest before it reaches this client, so they are already
 * absent rather than filtered.
 */
fun catalogRows(addons: List<AddonEntry>): List<CatalogRowSpec> = addons.flatMap { addon ->
    addon.manifest.catalogs
        .filter { catalog -> isHomeCatalogType(catalog.type) }
        .filter { catalog -> catalog.extra.none { it.isRequired } && catalog.extraRequired.isEmpty() }
        .map { catalog ->
            val labels = catalogShelfLabels(catalog.name, catalog.type, addon.manifest.name)
            CatalogRowSpec(
                key = "${addon.id}/${catalog.type}/${catalog.id}",
                addonId = addon.id,
                type = catalog.type,
                catalogId = catalog.id,
                title = labels.title,
                source = labels.source,
            )
        }
}

data class ShelfLabels(val title: String, val source: String)

/** Words that already say what kind a catalog holds, so the kind is not added twice. */
private val KindWords = mapOf(
    "movie" to Regex("\\b(movies?|films?|cinema)\\b", RegexOption.IGNORE_CASE),
    "series" to Regex("\\b(series|shows?|tv)\\b", RegexOption.IGNORE_CASE),
)

/**
 * Plural nouns a catalog name can end on, where the kind reads as a modifier
 * in front ("Latest Movie Releases") rather than tacked on behind ("Latest
 * Releases Movies").
 */
private val TrailingNouns = setOf(
    "additions", "arrivals", "favorites", "favourites", "gems", "hits", "picks", "premieres", "releases", "titles",
)

/** "TMDB - Latest", "TMDB: Latest", "TMDB | Latest": a prefix and the rest. */
private val NamePrefix = Regex("^(.+?)(?:\\s+[-–—|]\\s+|:\\s+)(.+)$")

/** A short all-caps token such as TMDB or IMDB. */
private val Acronym = Regex("^[A-Z0-9]{2,6}$")

/** "Addon" or "Add-on" ending an addon's name, which says nothing on a shelf. */
private val AddonSuffix = Regex("\\s*\\badd-?on$", RegexOption.IGNORE_CASE)

private val Spaces = Regex("\\s+")

/** The kind as a shelf title spells it, plural ("Popular Movies") and singular ("Latest Movie Releases"). */
private fun kindLabels(type: String): Pair<String, String> = when (type) {
    "movie" -> "Movies" to "Movie"
    "series" -> "Series" to "Series"
    else -> type.replaceFirstChar { it.uppercase() }.let { it to it }
}

/**
 * How a catalog shelf is labelled, the desktop app's `catalogShelfLabels`
 * (`apps/desktop/src/home/homeRows.ts`); keep the two in step. The catalog's
 * name with its kind ("Popular Movies"), and the addon it comes from as the
 * mono line beside it ("CINEMETA"). A name that already says its kind is kept
 * as it is, one ending on a plural noun takes the kind in front ("Latest Movie
 * Releases"), and a catalog with no name is titled by its kind. An addon's own
 * prefix on the name ("TMDB - Latest") moves to the source line, as its short
 * form when it is an acronym.
 */
fun catalogShelfLabels(catalogName: String?, type: String, addonName: String): ShelfLabels {
    val (plural, singular) = kindLabels(type)
    val (name, acronym) = splitAddonPrefix(catalogName?.trim().orEmpty(), addonName)
    val source = (acronym ?: addonName.replace(AddonSuffix, "").trim()).uppercase()
    if (name.isEmpty()) return ShelfLabels(plural, source)
    val saysKind = KindWords[type]?.containsMatchIn(name) ?: name.lowercase().contains(type.lowercase())
    if (saysKind) return ShelfLabels(name, source)
    val words = name.split(Spaces)
    val last = words.last()
    if (words.size > 1 && last.lowercase() in TrailingNouns) {
        return ShelfLabels((words.dropLast(1) + singular + last).joinToString(" "), source)
    }
    return ShelfLabels("$name $plural", source)
}

/**
 * The prefix goes when it is an acronym or the addon's own name or first word;
 * anything else could be part of the title and stays.
 */
private fun splitAddonPrefix(name: String, addonName: String): Pair<String, String?> {
    val match = NamePrefix.find(name) ?: return name to null
    val prefix = match.groupValues[1].trim()
    val rest = match.groupValues[2].trim()
    if (Acronym.matches(prefix)) return rest to prefix
    val addon = addonName.replace(AddonSuffix, "").trim().lowercase()
    val lower = prefix.lowercase()
    if (lower == addon || lower == addon.split(Spaces).first()) return rest to null
    return name to null
}

fun isHomeCatalogType(type: String): Boolean = type == "movie" || type == "series"

/** Singular label for one result set ("Movie"), used by search group headings. */
fun itemTypeLabel(type: String): String = type.replaceFirstChar { it.uppercase() }
