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
    val name = foldedText(meta.name).filter { it.isLetterOrDigit() }
    // The surest identity when it is there: Cinemeta's id is the IMDb id, and
    // the TMDB addon sends it beside its own.
    val imdbId = meta.id.takeIf(ImdbId::matches) ?: meta.imdbId?.takeIf(ImdbId::matches)
    return listOfNotNull(
        titleKey(meta),
        imdbId?.let { "${meta.type}|imdb|$it" },
        if (year != null && name.isNotEmpty()) "${meta.type}|$name|$year" else null,
    )
}

private val ImdbId = Regex("""^tt\d+$""")
