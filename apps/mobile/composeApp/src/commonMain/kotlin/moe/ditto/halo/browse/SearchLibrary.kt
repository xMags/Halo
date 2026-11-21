package moe.ditto.halo.browse

import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.MetaPreview

/*
 * What only the app has beside the shared search rules (SearchBoard.kt): its
 * library, matched against the query. The desktop does the same in
 * `search/searchResults.ts`.
 */

private val Whitespace = Regex("""${ScriptSpaceClass}+""")

/**
 * The library entries the query names (a title matches when its folded name
 * holds every folded word of the query), newest first, as titles. They are
 * what the viewer most likely means, and no addon has to answer for them.
 * Removed entries are not in the library, and an entry whose id names no
 * title is left out.
 */
fun libraryMatches(library: List<LibraryItem>?, query: String): List<MetaPreview> {
    val words = foldedText(query).split(Whitespace).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return library.orEmpty()
        .filterNot { it.isRemoved }
        .filter { item -> foldedText(item.name).let { name -> words.all { it in name } } }
        .sortedByDescending { it.addedAt }
        .mapNotNull { item ->
            // Library ids are `<type>:<meta id>`; one that is not names no title.
            val metaId = item.id.removePrefix("${item.type}:").takeIf { it != item.id && it.isNotEmpty() } ?: return@mapNotNull null
            MetaPreview(id = metaId, type = item.type, name = item.name, poster = item.poster)
        }
}
