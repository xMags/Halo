package moe.ditto.halo.browse

import moe.ditto.halo.api.MetaCard

/** `type:id`, the identity two addons share for one title. */
fun titleKey(meta: MetaCard): String = "${meta.type}:${meta.id}"

/**
 * Several ranked lists as one, for the layouts that merge search results (a
 * landscape tablet, as on the desktop): every list's first title, then every
 * list's second, and so on, each title once. Taking turns keeps each addon's
 * own ranking without letting the first addon's long tail bury the others'
 * best. Titles in [exclude] are left out, being shown elsewhere on the screen.
 */
fun <T : MetaCard> interleaveTitles(lists: List<List<T>>, exclude: Set<String> = emptySet()): List<T> {
    val seen = exclude.toMutableSet()
    val merged = mutableListOf<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (rank in 0 until longest) {
        for (list in lists) {
            val meta = list.getOrNull(rank) ?: continue
            if (seen.add(titleKey(meta))) merged += meta
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
