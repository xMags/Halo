package moe.ditto.halo.people

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The people, credits, lookups and capabilities a Halo server answers, read on
 * the phone. Everything here is untrusted: text is bounded and stripped of
 * control characters, images are accepted only from TMDB's own image host over
 * https, ids only in their exact shapes, and unknown fields are ignored. A bad
 * entry is skipped rather than failing the whole answer; an answer that names
 * nothing throws [PeopleAnswerException]. A port of `@halo/core`'s
 * `people/readers.ts`, held to `packages/core/test/vectors/people.json` and
 * `magnets.json`.
 */

enum class PersonDepartment(val wire: String) { Acting("Acting"), Directing("Directing"), Writing("Writing") }

enum class PersonCreditRole(val wire: String) { Cast("cast"), Director("director"), Writer("writer") }

/** What a server offers beyond the shared contract. Anything it does not say reads as off. */
data class Capabilities(
    val people: Boolean = false,
    val lookup: Boolean = false,
    val magnets: Boolean = false,
    /** Only an explicit false says uncached torrents may be fetched. */
    val magnetsCachedOnly: Boolean = true,
) {
    companion object {
        val None = Capabilities()
    }
}

data class PersonSummary(
    val tmdbId: Long,
    val name: String,
    val knownFor: PersonDepartment?,
    val photo: String?,
    val popularity: Double,
    val knownForTitles: List<String>,
)

data class PeopleSearch(
    val people: List<PersonSummary>,
    /** The name a misspelling was read as, when the server corrected it to someone listed. */
    val corrected: String?,
)

data class PersonCredit(
    /** `movie` or `series`. */
    val type: String,
    val imdbId: String,
    /** 0 when the server sent none. */
    val tmdbId: Long,
    val name: String,
    val year: String?,
    val poster: String?,
    val roles: List<PersonCreditRole>,
    val character: String?,
    val popularity: Double,
)

data class PersonProfile(
    val tmdbId: Long,
    val name: String,
    val photo: String?,
    val knownFor: PersonDepartment?,
    val biography: String?,
)

data class PersonDetail(
    val person: PersonProfile,
    val credits: List<PersonCredit>,
    /** Some credits could not be given an IMDb id in time; the page says so. */
    val partial: Boolean,
)

sealed interface IdLookup {
    val name: String

    data class Person(override val name: String, val tmdbId: Long, val photo: String?) : IdLookup

    data class Title(override val name: String, val type: String, val id: String, val year: String?, val poster: String?) : IdLookup
}

class PeopleAnswerException(message: String) : Exception(message)

private const val MaxName = 200
private const val MaxBiography = 2000
private const val MaxImageUrl = 300
private const val MaxPeople = 8
private const val MaxKnownForTitles = 3
private const val MaxCredits = 60
private const val TmdbImagePrefix = "https://image.tmdb.org/t/p/"
private const val MaxSafeInteger = 9_007_199_254_740_991.0
private val ImagePath = Regex("""^[A-Za-z0-9/_.-]+$""")
private val ImdbTitleId = Regex("""^tt\d{1,10}$""")
private val FourDigits = Regex("""^\d{4}$""")

private fun isControl(c: Char): Boolean = c.code < 0x20 || c.code in 0x7F..0x9F

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun requireObject(value: JsonElement?, message: String): JsonObject = value.objectOrNull() ?: throw PeopleAnswerException(message)

/** Trimmed, control characters as spaces, refused rather than cut when longer than the bound: a clipped name names someone else. */
internal fun boundedText(value: JsonElement?, maximum: Int = MaxName): String? {
    val text = value.stringOrNull()?.trim() ?: return null
    if (text.isEmpty() || text.length > maximum) return null
    return text.map { if (isControl(it)) ' ' else it }.joinToString("")
}

/** Prose: line breaks kept, other control characters as spaces, cut (not refused) at the bound. */
private fun prose(value: JsonElement?, maximum: Int): String? {
    val raw = value.stringOrNull()?.trim()?.replace("\r\n", "\n")?.replace('\r', '\n') ?: return null
    var text = raw.take(maximum).map { if (it != '\n' && isControl(it)) ' ' else it }.joinToString("")
    if (text.isNotEmpty() && text.last().isHighSurrogate()) text = text.dropLast(1)
    return text.trim().ifEmpty { null }
}

/** A positive whole number JavaScript can hold exactly, as JSON numbers carry them. */
internal fun safePositiveInteger(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.doubleOrNull ?: return null
    if (!number.isFinite() || number <= 0 || number > MaxSafeInteger || number != kotlin.math.floor(number)) return null
    return number.toLong()
}

private fun popularity(value: JsonElement?): Double {
    val primitive = value as? JsonPrimitive ?: return 0.0
    if (primitive.isString) return 0.0
    val number = primitive.doubleOrNull ?: return 0.0
    return if (number.isFinite() && number > 0) number else 0.0
}

/** Whether a picture link is TMDB's own image host over https, with a plain path. */
fun isTmdbImageUrl(url: String): Boolean {
    if (url.length > MaxImageUrl || !url.startsWith(TmdbImagePrefix)) return false
    val path = url.substring(TmdbImagePrefix.length)
    return path.isNotEmpty() && ".." !in path && ImagePath.matches(path)
}

internal fun tmdbImage(value: JsonElement?): String? = value.stringOrNull()?.takeIf(::isTmdbImageUrl)

private fun imdbTitleId(value: JsonElement?): String? = value.stringOrNull()?.takeIf(ImdbTitleId::matches)

private fun titleType(value: JsonElement?): String? = value.stringOrNull()?.takeIf { it == "movie" || it == "series" }

private fun year(value: JsonElement?): String? = value.stringOrNull()?.takeIf(FourDigits::matches)

internal fun department(value: JsonElement?): PersonDepartment? =
    value.stringOrNull()?.let { wire -> PersonDepartment.entries.firstOrNull { it.wire == wire } }

private fun roles(value: JsonElement?): List<PersonCreditRole> {
    val found = mutableListOf<PersonCreditRole>()
    for (item in value as? JsonArray ?: return found) {
        if (found.size == 3) break
        val role = PersonCreditRole.entries.firstOrNull { it.wire == item.stringOrNull() } ?: continue
        if (role !in found) found += role
    }
    return found
}

private fun summary(value: JsonElement): PersonSummary? {
    val obj = value.objectOrNull() ?: return null
    val tmdbId = safePositiveInteger(obj["tmdbId"]) ?: return null
    val name = boundedText(obj["name"]) ?: return null
    val titles = (obj["knownForTitles"] as? JsonArray).orEmpty().mapNotNull { boundedText(it) }.take(MaxKnownForTitles)
    return PersonSummary(tmdbId, name, department(obj["knownFor"]), tmdbImage(obj["photo"]), popularity(obj["popularity"]), titles)
}

private fun credit(value: JsonElement): PersonCredit? {
    val obj = value.objectOrNull() ?: return null
    val type = titleType(obj["type"]) ?: return null
    val imdbId = imdbTitleId(obj["imdbId"]) ?: return null
    val name = boundedText(obj["name"]) ?: return null
    val held = roles(obj["roles"])
    // Without a role it would not belong on the page.
    if (held.isEmpty()) return null
    return PersonCredit(
        type = type,
        imdbId = imdbId,
        tmdbId = safePositiveInteger(obj["tmdbId"]) ?: 0,
        name = name,
        year = year(obj["year"]),
        poster = tmdbImage(obj["poster"]),
        roles = held,
        character = boundedText(obj["character"]),
        popularity = popularity(obj["popularity"]),
    )
}

/** `GET /capabilities`. An older server has no `magnets` at all, which reads as off. */
fun readCapabilities(value: JsonElement): Capabilities {
    val obj = requireObject(value, "The capabilities response must be an object.")
    val magnets = obj["magnets"].objectOrNull()
    fun flag(element: JsonElement?) = (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return Capabilities(
        people = flag(obj["people"]) == true,
        lookup = flag(obj["lookup"]) == true,
        magnets = flag(magnets?.get("enabled")) == true,
        magnetsCachedOnly = flag(magnets?.get("cachedOnly")) != false,
    )
}

/** `GET /people/search`: up to eight people, each once, and the corrected name when it names one of them. */
fun readPeopleSearch(value: JsonElement): PeopleSearch {
    val obj = requireObject(value, "The people search response must be an object.")
    val people = mutableListOf<PersonSummary>()
    for (item in obj["people"] as? JsonArray ?: JsonArray(emptyList())) {
        if (people.size == MaxPeople) break
        val person = summary(item) ?: continue
        if (people.none { it.tmdbId == person.tmdbId }) people += person
    }
    val corrected = boundedText(obj["corrected"])?.takeIf { name -> people.any { it.name == name } }
    return PeopleSearch(people, corrected)
}

/** `GET /people/:tmdbId`. */
fun readPerson(value: JsonElement): PersonDetail {
    val root = requireObject(value, "The person response must be an object.")
    val person = requireObject(root["person"], "The person response has no person.")
    val tmdbId = safePositiveInteger(person["tmdbId"])
    val name = boundedText(person["name"])
    if (tmdbId == null || name == null) throw PeopleAnswerException("The person response names nobody.")
    val credits = mutableListOf<PersonCredit>()
    for (item in root["credits"] as? JsonArray ?: JsonArray(emptyList())) {
        if (credits.size == MaxCredits) break
        val entry = credit(item) ?: continue
        if (credits.none { it.type == entry.type && it.imdbId == entry.imdbId }) credits += entry
    }
    return PersonDetail(
        person = PersonProfile(tmdbId, name, tmdbImage(person["photo"]), department(person["knownFor"]), prose(person["biography"], MaxBiography)),
        credits = credits,
        partial = (root["partial"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
    )
}

/** `GET /lookup`. */
fun readIdLookup(value: JsonElement): IdLookup {
    val obj = requireObject(value, "The lookup response must be an object.")
    val kind = obj["kind"].stringOrNull()
    val name = boundedText(obj["name"])
    if (kind == null || name == null) throw PeopleAnswerException("The lookup response names nothing.")
    if (kind == "person") {
        val tmdbId = safePositiveInteger(obj["tmdbId"]) ?: throw PeopleAnswerException("The lookup response names nobody.")
        return IdLookup.Person(name, tmdbId, tmdbImage(obj["photo"]))
    }
    if (kind != "title") throw PeopleAnswerException("The lookup response is of an unknown kind.")
    val type = titleType(obj["type"])
    val id = imdbTitleId(obj["id"])
    if (type == null || id == null) throw PeopleAnswerException("The lookup response names no title.")
    return IdLookup.Title(name, type, id, year(obj["year"]), tmdbImage(obj["poster"]))
}
