package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaCard

/** `type:id`, the identity two addons share for one title when they use the same ids. */
fun titleKey(meta: MetaCard): String = "${meta.type}:${meta.id}"

/** The first four-digit year of a release line: `2021`, `2016–2025` and `2024-` all give their start. */
private val ReleaseYear = Regex("""\d{4}""")

/**
 * Every identity a title can be recognised by. Addons name the same title by
 * different ids (Cinemeta's IMDb `tt…`, TMDB's own numbers), so besides its id
 * a title is known by its kind, name and year together: "Dune" from 2021 is
 * one film whichever addon lists it. Without a year a name alone is not
 * trusted, since remakes share names. The desktop matches the same way.
 */
fun titleIdentities(meta: MetaCard): List<String> {
    val year = meta.releaseInfo?.let { ReleaseYear.find(it)?.value }
    val name = meta.name.lowercase().filter { it.isLetterOrDigit() }
    return if (year != null && name.isNotEmpty()) {
        listOf(titleKey(meta), "${meta.type}|$name|$year")
    } else {
        listOf(titleKey(meta))
    }
}

/**
 * Several ranked lists as one, for the layouts that merge search results (a
 * landscape tablet, as on the desktop): every list's first title, then every
 * list's second, and so on, each title once even when two addons name it by
 * different ids (see [titleIdentities]). Taking turns keeps each addon's own
 * ranking without letting the first addon's long tail bury the others' best;
 * of two copies of a title, the better-ranked one stays. Titles shown
 * elsewhere on the screen are passed in [exclude] and left out.
 */
fun <T : MetaCard> interleaveTitles(lists: List<List<T>>, exclude: List<MetaCard> = emptyList()): List<T> {
    val seen = exclude.flatMap(::titleIdentities).toMutableSet()
    val merged = mutableListOf<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (rank in 0 until longest) {
        for (list in lists) {
            val meta = list.getOrNull(rank) ?: continue
            val identities = titleIdentities(meta)
            if (identities.any { it in seen }) continue
            seen += identities
            merged += meta
        }
    }
    return merged
}

/**
 * Past searches that contain what is being typed, most recent first, to pick
 * one instead of typing it out. The term itself is not suggested back.
 */
fun suggestedSearches(history: List<String>, typed: String, limit: Int = 5): List<String> {
    val needle = typed.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return history
        .filter { term ->
            val candidate = term.lowercase()
            candidate != needle && candidate.contains(needle)
        }
        .take(limit)
}
