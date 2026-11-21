package moe.ditto.halo.people

import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.isImdbId
import moe.ditto.halo.browse.isSearchedCatalogType
import moe.ditto.halo.browse.nameRelevance
import moe.ditto.halo.browse.parseSearchQuery
import moe.ditto.halo.browse.scriptTrim

/*
 * Which people Search shows beside a query's titles, how a person and their
 * credits are described, and how a person page lists their work. A port of
 * `@halo/core`'s `people/rows.ts`, held to `packages/core/test/vectors/people.json`.
 */

const val PeopleRowLimit = 3

/** The row admits a name holding every word typed: the whole name, its start, or a surname alone ("nolan"). */
private const val LoosestPersonTier = 3
private const val ExactNameTier = 0

/**
 * At most three people whose name holds every word of the query, best named
 * first, then most popular. When a title is named exactly what was typed,
 * only people named exactly that stay. Beside someone with a photo, a
 * namesake without one is nobody the typist meant.
 */
fun choosePeopleRow(people: List<PersonSummary>, query: String, bestTitleTier: Int?): List<PersonSummary> {
    val trimmed = query.scriptTrim()
    val candidates = people
        .map { it to nameRelevance(it.name, trimmed) }
        .filter { (_, tier) -> tier <= LoosestPersonTier }
        .filterNot { (_, tier) -> bestTitleTier == ExactNameTier && tier > ExactNameTier }
        .sortedWith(compareBy<Pair<PersonSummary, Int>> { it.second }.thenByDescending { it.first.popularity })
    val anyPhoto = candidates.any { it.first.photo != null }
    return candidates.map { it.first }.filter { !anyPhoto || it.photo != null }.take(PeopleRowLimit)
}

/** The best name relevance among [names], read as the ranking reads it (the full query or its name without a year); null for none. */
fun bestTitleTier(names: List<String>, query: String, currentYear: Int): Int? {
    val parts = parseSearchQuery(query.scriptTrim(), currentYear)
    return names.minOfOrNull { minOf(nameRelevance(it, parts.full), nameRelevance(it, parts.name)) }
}

/** The one person a name stands for: the exact name, the most popular of several. Null when nobody has exactly that name. */
fun exactNamePerson(people: List<PersonSummary>, name: String): PersonSummary? {
    val trimmed = name.scriptTrim()
    if (trimmed.isEmpty()) return null
    var chosen: PersonSummary? = null
    for (person in people) {
        if (nameRelevance(person.name, trimmed) != ExactNameTier) continue
        if (chosen == null || person.popularity > chosen.popularity) chosen = person
    }
    return chosen
}

/** A person's films and series as Search titles, in the order given; one without an IMDb id, a name or a searched kind is left out. */
fun creditTitles(credits: List<PersonCredit>): List<MetaPreview> = credits.mapNotNull { credit ->
    if (!isImdbId(credit.imdbId) || credit.name.isEmpty() || !isSearchedCatalogType(credit.type)) return@mapNotNull null
    MetaPreview(
        id = credit.imdbId,
        imdbId = credit.imdbId,
        type = credit.type,
        name = credit.name,
        poster = credit.poster,
        releaseInfo = credit.year,
    )
}

/** What a person is: "Actor", "Director", "Writer"; empty when unknown. */
fun knownForLabel(department: PersonDepartment?): String = when (department) {
    PersonDepartment.Acting -> "Actor"
    PersonDepartment.Directing -> "Director"
    PersonDepartment.Writing -> "Writer"
    null -> ""
}

/** The department itself, as a person page's kicker. */
fun departmentLabel(department: PersonDepartment?): String = department?.wire.orEmpty()

/** The line under a person's name: "Director · Dune, Arrival, Sicario", or whichever half is known. */
fun personRowLine(knownFor: PersonDepartment?, knownForTitles: List<String>): String {
    val label = knownForLabel(knownFor)
    val titles = knownForTitles.joinToString(", ")
    return if (label.isNotEmpty() && titles.isNotEmpty()) "$label · $titles" else label.ifEmpty { titles }
}

/** A person page's role chips: every credit, or only those in one role. */
enum class FilmographyFilter(val label: String, val role: PersonCreditRole?) {
    All("All", null),
    Acting("Acting", PersonCreditRole.Cast),
    Directing("Directing", PersonCreditRole.Director),
    Writing("Writing", PersonCreditRole.Writer),
}

fun hasRole(credit: PersonCredit, filter: FilmographyFilter): Boolean = filter.role == null || filter.role in credit.roles

/** All, then each role at least one credit has, in chip order (Acting, Directing, Writing). */
fun filmographyFilters(credits: List<PersonCredit>): List<FilmographyFilter> =
    FilmographyFilter.entries.filter { it == FilmographyFilter.All || credits.any { credit -> hasRole(credit, it) } }

private fun creditYear(credit: PersonCredit): Int = credit.year?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() ?: -1

/** The credits a filter keeps, newest first and those without a year last; within a year, the more popular first. */
fun filmography(credits: List<PersonCredit>, filter: FilmographyFilter): List<PersonCredit> =
    credits.filter { hasRole(it, filter) }.sortedWith(compareByDescending<PersonCredit> { creditYear(it) }.thenByDescending { it.popularity })

/** "Actor · Director · Writer", in that order, for the roles held. */
fun creditRoleLabel(roles: List<PersonCreditRole>): String = listOf(
    PersonCreditRole.Cast to "Actor",
    PersonCreditRole.Director to "Director",
    PersonCreditRole.Writer to "Writer",
).filter { it.first in roles }.joinToString(" · ") { it.second }

/** The line under a credit's poster: "2021 · Director · Writer", or the roles alone without a year. */
fun creditLine(credit: PersonCredit): String {
    val roles = creditRoleLabel(credit.roles)
    val year = credit.year.orEmpty()
    return if (year.isNotEmpty() && roles.isNotEmpty()) "$year · $roles" else year.ifEmpty { roles }
}
