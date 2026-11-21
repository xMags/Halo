package moe.ditto.halo.people

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.ditto.halo.browse.foldedText
import moe.ditto.halo.browse.scriptTrim

/*
 * A misspelled name, and the answers of the people and lookup routes
 * (`GET /people/search`, `GET /people/:tmdbId`, `GET /lookup`) with the
 * server's validation, statuses and bodies. Halo without an account builds
 * these bodies on the phone and reads them through the same readers as the
 * server's (PeopleReaders.kt). A port of `@halo/core`'s `people/closest.ts`
 * and `people/answers.ts`.
 */

/** A word shorter than this finds too many people to be worth a search. */
private const val MinWordLength = 3
/** At most this many words are searched, longest first. */
private const val MaxWordsSearched = 3
private const val MaxClosestPeople = 3

/** A name as compared: lower case, accents off, anything but letters and digits a space. */
fun nameKey(text: String): String = foldedText(text).replace(Regex("""[^\p{L}\p{N}]+"""), " ").scriptTrim()

/** How many letters may differ for a name of this length to still be the one meant. */
fun allowedDistance(length: Int): Int = maxOf(1, length / 4)

/** The edit distance between two texts, or `limit + 1` as soon as it is known to be over [limit]. */
fun editDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        var best = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (current[j] < best) best = current[j]
        }
        if (best > limit) return limit + 1
        previous = current
    }
    return previous[b.length]
}

/** The words a misspelled name is searched by: none for a single word, else those of three letters or more, longest first, at most three. */
fun closestPeopleWords(query: String): List<String> {
    val words = nameKey(query).split(' ').distinct()
    if (words.size < 2) return emptyList()
    return words.filter { it.length >= MinWordLength }.sortedByDescending { it.length }.take(MaxWordsSearched)
}

data class ClosestPeople(val people: List<PersonSummary>, val corrected: String)

/**
 * The people a misspelled [query] most likely means, or null when nobody is
 * near enough. Throws only when every word's search failed upstream.
 */
suspend fun closestPeople(searchPeople: suspend (String) -> List<PersonSummary>, query: String): ClosestPeople? {
    val typed = nameKey(query)
    val searched = closestPeopleWords(query)
    if (searched.isEmpty()) return null
    val answers = coroutineScope {
        searched.map { word ->
            async {
                try {
                    Result.success(searchPeople(word))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Result.failure<List<PersonSummary>>(failure)
                }
            }
        }.awaitAll()
    }
    answers.firstOrNull { it.isFailure }?.let { failed ->
        if (answers.all { it.isFailure }) throw failed.exceptionOrNull() ?: IllegalStateException("people search failed")
    }
    val limit = allowedDistance(typed.length)
    val near = LinkedHashMap<Long, Pair<PersonSummary, Int>>()
    for (answer in answers) {
        for (person in answer.getOrNull().orEmpty()) {
            if (person.tmdbId in near) continue
            val distance = editDistance(typed, nameKey(person.name), limit)
            if (distance <= limit) near[person.tmdbId] = person to distance
        }
    }
    val ranked = near.values.sortedWith(compareBy<Pair<PersonSummary, Int>> { it.second }.thenByDescending { it.first.popularity })
    val best = ranked.firstOrNull() ?: return null
    return ClosestPeople(ranked.take(MaxClosestPeople).map { it.first }, best.first.name)
}

/** A route's status and body, as the server would answer it. */
data class RouteAnswer(val status: Int, val body: JsonElement)

private fun refusal(status: Int, message: String) = RouteAnswer(status, JsonObject(mapOf("error" to JsonPrimitive(message))))

private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun personJson(person: PersonSummary): JsonObject = JsonObject(
    mapOf(
        "tmdbId" to JsonPrimitive(person.tmdbId),
        "name" to JsonPrimitive(person.name),
        "knownFor" to nullable(person.knownFor?.wire),
        "photo" to nullable(person.photo),
        "popularity" to JsonPrimitive(person.popularity),
        "knownForTitles" to JsonArray(person.knownForTitles.map(::JsonPrimitive)),
    ),
)

private const val LookupFailed = "lookup failed"

/** `GET /people/search?q=`: the people named so, or the ones a misspelling most likely means with the name they were found under. */
suspend fun peopleSearchAnswer(client: TmdbClient, rawQuery: String?): RouteAnswer {
    val q = rawQuery.orEmpty().scriptTrim()
    if (q.length < 2 || q.length > 120) return refusal(400, "q must be 2 to 120 characters")
    return try {
        val people = client.searchPeople(q)
        if (people.isNotEmpty()) return RouteAnswer(200, JsonObject(mapOf("people" to JsonArray(people.map(::personJson)))))
        // Nobody by that spelling. A failure here is still "nobody".
        val closest = try {
            closestPeople(client::searchPeople, q)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val body = if (closest == null) {
            mapOf("people" to JsonArray(emptyList()))
        } else {
            mapOf("people" to JsonArray(closest.people.map(::personJson)), "corrected" to JsonPrimitive(closest.corrected))
        }
        RouteAnswer(200, JsonObject(body))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        refusal(502, LookupFailed)
    }
}

private val TmdbIdText = Regex("""^[0-9]{1,16}$""")

/** `GET /people/:tmdbId`: the person, their credits with IMDb ids, and whether some could not be mapped in time. */
suspend fun personAnswer(client: TmdbClient, raw: String, now: () -> Long): RouteAnswer {
    val tmdbId = raw.takeIf(TmdbIdText::matches)?.toLongOrNull()
    if (tmdbId == null || tmdbId <= 0 || tmdbId > 9_007_199_254_740_991) return refusal(400, "tmdbId must be a positive whole number")
    return try {
        val details = client.person(tmdbId) ?: return refusal(404, "person not found")
        val (credits, partial) = mapCreditsToImdb(client::imdbIdFor, details.credits, now)
        val person = JsonObject(
            mapOf(
                "tmdbId" to JsonPrimitive(details.tmdbId),
                "name" to JsonPrimitive(details.name),
                "photo" to nullable(details.photo),
                "knownFor" to nullable(details.knownFor?.wire),
                "biography" to nullable(details.biography),
            ),
        )
        val creditList = credits.map { credit ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(credit.type),
                    "imdbId" to JsonPrimitive(credit.imdbId),
                    "tmdbId" to JsonPrimitive(credit.tmdbId),
                    "name" to JsonPrimitive(credit.name),
                    "year" to nullable(credit.year),
                    "poster" to nullable(credit.poster),
                    "roles" to JsonArray(credit.roles.map { JsonPrimitive(it.wire) }),
                    "character" to nullable(credit.character),
                    "popularity" to JsonPrimitive(credit.popularity),
                ),
            )
        }
        RouteAnswer(200, JsonObject(mapOf("person" to person, "credits" to JsonArray(creditList), "partial" to JsonPrimitive(partial))))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        refusal(502, LookupFailed)
    }
}

private val TvdbId = Regex("""^[0-9]{1,10}$""")
private val ImdbId = Regex("""^(?:tt|nm)[0-9]{1,10}$""")

/**
 * `GET /lookup?source=&id=`: a pasted IMDb or TVDB link, as the title or
 * person it names. A title is answered with its IMDb id, the id every addon
 * understands; one without an IMDb id is not found.
 */
suspend fun lookupAnswer(client: TmdbClient, source: String?, rawId: String?): RouteAnswer {
    val id = rawId.orEmpty()
    if (source != "tvdb" && source != "imdb") return refusal(400, "source must be tvdb or imdb")
    if (source == "tvdb" && !TvdbId.matches(id)) return refusal(400, "a tvdb id is digits only")
    if (source == "imdb" && !ImdbId.matches(id)) return refusal(400, "an imdb id is tt or nm followed by digits")
    return try {
        val found = client.find(source, id)
        if (source == "imdb" && id.startsWith("nm")) {
            val person = found.people.firstOrNull() ?: return refusal(404, "not found")
            return RouteAnswer(
                200,
                JsonObject(mapOf("kind" to JsonPrimitive("person"), "tmdbId" to JsonPrimitive(person.tmdbId), "name" to JsonPrimitive(person.name), "photo" to nullable(person.photo))),
            )
        }
        // TVDB numbers series first; an IMDb title id names one or the other.
        val title = (if (source == "tvdb") found.series.firstOrNull() ?: found.movies.firstOrNull() else found.movies.firstOrNull() ?: found.series.firstOrNull())
            ?: return refusal(404, "not found")
        val imdbId = if (source == "imdb") id else client.imdbIdFor(if (title.type == "movie") "movie" else "tv", title.tmdbId)
        imdbId ?: return refusal(404, "not found")
        RouteAnswer(
            200,
            JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("title"),
                    "type" to JsonPrimitive(title.type),
                    "id" to JsonPrimitive(imdbId),
                    "name" to JsonPrimitive(title.name),
                    "year" to nullable(title.year),
                    "poster" to nullable(title.poster),
                ),
            ),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        refusal(502, LookupFailed)
    }
}
