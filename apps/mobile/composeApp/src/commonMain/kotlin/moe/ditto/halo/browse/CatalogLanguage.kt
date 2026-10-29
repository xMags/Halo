package moe.ditto.halo.browse

import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.ui.LanguageOptions

/*
 * Home's language rows: catalogs such as TMDB's "Language" row, which list
 * titles in one language picked from an optional genre filter. The desktop
 * app's `src/home/catalogLanguage.ts`; keep the two in step, since the chosen
 * language is one synced setting both apps read.
 */

/** Language names Halo already knows, lower-cased, for telling a language list from a genre list. */
private val KnownLanguages: Set<String> = LanguageOptions.mapTo(mutableSetOf()) { it.label.lowercase() }

/** How many known languages an option list must hold to count as a language choice. */
private const val MinKnown = 5

/**
 * The languages a catalog can be asked for, when it is a language catalog: an
 * optional `genre` extra whose options are language names. Null for every
 * other catalog, such as Cinemeta's genre lists or TMDB's years, whose options
 * name no languages.
 */
fun languageChoices(catalog: ManifestCatalog): List<String>? {
    val genre = catalog.extra.firstOrNull { it.name == "genre" } ?: return null
    if (genre.isRequired) return null
    val known = genre.options.count { it.trim().lowercase() in KnownLanguages }
    return genre.options.takeIf { known >= MinKnown }
}

/**
 * What a Home row is asked with: the chosen language for a language row that
 * offers it, nothing otherwise, so the row falls back to its own default.
 */
fun catalogExtra(choices: List<String>?, preferred: String?): Map<String, String> =
    if (choices != null && preferred != null && preferred in choices) mapOf("genre" to preferred) else emptyMap()

/**
 * The languages Settings offers: the first language row's own list among
 * [addons], or null when none has one, which leaves the setting out. An addon
 * whose catalogs are hidden comes with them stripped, so it never counts.
 */
fun homeLanguageChoices(addons: List<AddonEntry>): List<String>? =
    addons.asSequence()
        .flatMap { it.manifest.catalogs.asSequence() }
        .mapNotNull(::languageChoices)
        .firstOrNull { it.isNotEmpty() }
