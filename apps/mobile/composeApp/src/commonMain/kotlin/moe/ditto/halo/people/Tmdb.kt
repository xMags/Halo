package moe.ditto.halo.people

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import moe.ditto.halo.api.StrictJson
import moe.ditto.halo.browse.scriptTrim
import moe.ditto.halo.browse.ScriptSpaceClass

/*
 * A small TMDB (themoviedb.org) client for people search, person pages and
 * IMDb/TVDB id lookups, which Halo without an account runs with the viewer's
 * own key. Every answer is untrusted third-party JSON: it is read under a size
 * cap and reduced to bounded, validated fields before it is cached or used.
 * The credential travels only in the Authorization header (a read token) or,
 * for a 32-hex API key, in the `api_key` parameter TMDB requires for that
 * kind; it never appears in an error. A port of `@halo/core`'s `people/tmdb.ts`.
 */

private const val TmdbBaseUrl = "https://api.themoviedb.org"
private const val TmdbImageBase = "https://image.tmdb.org/t/p"
private const val TmdbTimeoutMs = 8_000L
private const val MaxTmdbResponseBytes = 2L * 1024 * 1024
private const val Hour = 3_600_000L
private const val Day = 24 * Hour
private const val MaxText = 200
private const val MaxBiographyText = 2_000
private const val MaxSearchPeople = 8
private const val MaxKnownFor = 3
private const val MaxTitleQuery = 200
/** The most credits a person page lists, before mapping to IMDb ids. */
const val MaxPersonCredits = 60
/** IMDb lookups in flight at once while mapping a person's credits. */
const val CreditMappingConcurrency = 6
/** The whole time mapping a person's credits may take. */
const val CreditMappingBudgetMs = 8_000L

/** TMDB TV genres that are not shows a person acted in: News, Reality, Talk. */
private val ExcludedTvGenres = setOf(10763.0, 10764.0, 10767.0)
/** "Self", "Himself", "Herself", alone or followed by " - Host", " (archive footage)" and the like. */
private val SelfCharacter = Regex("""^(?:self|himself|herself)(?:${ScriptSpaceClass}*[-(:].*)?$""", RegexOption.IGNORE_CASE)
/** TMDB image paths look like "/abc123XYZ.jpg". */
private val ImagePathShape = Regex("""^/[A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png|webp)$""")
private val ImdbTitleShape = Regex("""^tt[0-9]{1,10}$""")
private val YearPrefix = Regex("""^([0-9]{4})-[0-9]{2}-[0-9]{2}$""")
private val ApiKeyShape = Regex("""^[0-9a-fA-F]{32}$""")
private val ReadTokenShape = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
private val FindIdShape = Regex("""^[A-Za-z0-9]{1,32}$""")

/** The two kinds of TMDB credential: a read token (Bearer header) and a 32-hex API key (`api_key` parameter). */
sealed interface TmdbCredential {
    val value: String

    data class ReadToken(override val value: String) : TmdbCredential

    data class ApiKey(override val value: String) : TmdbCredential
}

/**
 * What was pasted, trimmed, as a credential when it has either shape; null
 * otherwise. Only the shape is judged: whether TMDB accepts it is TMDB's to say.
 */
fun parseTmdbCredential(pasted: String): TmdbCredential? {
    val value = pasted.trim(' ', '\t', '\r', '\n')
    if (ApiKeyShape.matches(value)) return TmdbCredential.ApiKey(value)
    if (value.length < 100 || value.length > 2048) return null
    return if (ReadTokenShape.matches(value)) TmdbCredential.ReadToken(value) else null
}

data class TmdbTitleHit(val type: String, val tmdbId: Long, val name: String, val year: String?, val poster: String?, val popularity: Double)

data class TmdbPersonHit(val tmdbId: Long, val name: String, val photo: String?)

data class TmdbFindResult(val movies: List<TmdbTitleHit>, val series: List<TmdbTitleHit>, val people: List<TmdbPersonHit>)

/** One title a person is credited on, roles merged, before its IMDb id is known. */
data class CreditCandidate(
    val title: TmdbTitleHit,
    val roles: List<PersonCreditRole>,
    val character: String?,
)

data class TmdbPersonDetails(
    val tmdbId: Long,
    val name: String,
    val photo: String?,
    val knownFor: PersonDepartment?,
    val biography: String?,
    /** At most [MaxPersonCredits], most popular first. */
    val credits: List<CreditCandidate>,
)

/** TMDB answered with an error status. Carries the status only, never the URL, which may hold an API key. */
class TmdbRequestException(val status: Int) : Exception("TMDB request failed with $status")

class TmdbResponseTooLargeException : Exception("TMDB response too large")

/** TMDB answered 404; callers see null or an empty answer instead. */
private class TmdbNotFoundException : Exception("TMDB has no such entry")

/**
 * An in-memory cache with a time to live and a size cap. Expiry is checked on
 * read; over the cap the least recently used entry goes first; concurrent
 * callers of one key share one load, and a failed load is not kept.
 *
 * The caller whose load runs may be cancelled (a newer keystroke, a deadline)
 * while others wait on it. Its cancellation is not theirs, so they are told
 * the load was abandoned and each tries again, one of them loading anew. The
 * bookkeeping runs under [NonCancellable]: a cancelled loader that skipped it
 * would leave the key marked as loading, and every later caller would wait on
 * a load that never ends.
 */
class TtlCache<V>(private val maxEntries: Int, private val ttlMs: Long, private val now: () -> Long) {
    private class Entry<V>(val value: V, val expiresAt: Long)

    /** What waiters see when the caller loading for them was cancelled. */
    private class LoadAbandonedException : Exception("the shared load was cancelled")

    private val entries = LinkedHashMap<String, Entry<V>>()
    private val loading = mutableMapOf<String, Deferred<V>>()
    private val lock = Mutex()

    suspend fun getOrLoad(key: String, loader: suspend () -> V): V {
        while (true) {
            val waiting: Deferred<V>
            val mine: CompletableDeferred<V>?
            lock.withLock {
                val entry = entries.remove(key)
                if (entry != null && now() < entry.expiresAt) {
                    entries[key] = entry
                    return entry.value
                }
                val pending = loading[key]
                if (pending != null) {
                    waiting = pending
                    mine = null
                } else {
                    mine = CompletableDeferred()
                    loading[key] = mine
                    waiting = mine
                }
            }
            if (mine != null) return load(key, mine, loader)
            try {
                return waiting.await()
            } catch (_: LoadAbandonedException) {
                // The loader was cancelled, not this caller: try again.
            }
        }
    }

    private suspend fun load(key: String, mine: CompletableDeferred<V>, loader: suspend () -> V): V {
        val value = try {
            loader()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { lock.withLock { loading.remove(key) } }
            mine.completeExceptionally(if (failure is CancellationException) LoadAbandonedException() else failure)
            throw failure
        }
        withContext(NonCancellable) {
            lock.withLock {
                entries.remove(key)
                entries[key] = Entry(value, now() + ttlMs)
                while (entries.size > maxEntries) entries.remove(entries.keys.first())
                loading.remove(key)
            }
        }
        mine.complete(value)
        return value
    }
}

private val ByteOrderMark = Char(0xFEFF).toString()

class TmdbClient(
    private val http: HttpClient,
    private val credential: TmdbCredential,
    private val now: () -> Long,
    /** Told when TMDB refuses the credential (401), so the features turn off until it is replaced. */
    private val onRefused: () -> Unit = {},
    /**
     * Where IMDb id lookups run. A caller that stops waiting (a person page's
     * deadline) leaves its lookup to finish and fill the cache, as the
     * desktop's does, so the page is complete on a later visit instead of
     * partial again. Each lookup ends within the TMDB request timeout.
     */
    private val lookups: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val searchPeopleCache = TtlCache<List<PersonSummary>>(1_000, Hour, now)
    private val personCache = TtlCache<TmdbPersonDetails?>(500, 6 * Hour, now)
    private val imdbIdCache = TtlCache<String?>(20_000, 7 * Day, now)
    private val findCache = TtlCache<TmdbFindResult>(5_000, 7 * Day, now)
    private val searchTitleCache = TtlCache<List<TmdbTitleHit>>(1_000, Hour, now)

    private suspend fun getJson(pathAndQuery: String): JsonElement {
        var url = "$TmdbBaseUrl$pathAndQuery"
        if (credential is TmdbCredential.ApiKey) {
            url += (if ('?' in pathAndQuery) "&" else "?") + "api_key=" + credential.value.encodeURLParameter()
        }
        val text = try {
            withTimeout(TmdbTimeoutMs) {
                val response = http.get(url) {
                    header(HttpHeaders.Accept, "application/json")
                    if (credential is TmdbCredential.ReadToken) header(HttpHeaders.Authorization, "Bearer ${credential.value}")
                }
                if (response.status.value == 404) throw TmdbNotFoundException()
                if (!response.status.isSuccess()) {
                    if (response.status.value == 401) onRefused()
                    throw TmdbRequestException(response.status.value)
                }
                val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declared != null && declared > MaxTmdbResponseBytes) throw TmdbResponseTooLargeException()
                val bytes = response.bodyAsChannel().readRemaining(MaxTmdbResponseBytes + 1).readByteArray()
                if (bytes.size > MaxTmdbResponseBytes) throw TmdbResponseTooLargeException()
                // A leading byte order mark is dropped, as the desktop's TextDecoder drops it.
                bytes.decodeToString().removePrefix(ByteOrderMark)
            }
        } catch (_: TimeoutCancellationException) {
            throw TmdbRequestException(504)
        }
        return try {
            StrictJson.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw TmdbRequestException(502)
        }
    }

    suspend fun searchPeople(query: String): List<PersonSummary> {
        val q = query.scriptTrim()
        return searchPeopleCache.getOrLoad(q.lowercase()) {
            parsePeopleSearch(getJson("/3/search/person?query=${q.encodeURLParameter()}&include_adult=false"))
        }
    }

    /** Null when TMDB has no person with this id. */
    suspend fun person(tmdbId: Long): TmdbPersonDetails? {
        require(tmdbId > 0) { "TMDB ids are positive whole numbers" }
        return try {
            personCache.getOrLoad(tmdbId.toString()) {
                // An answer without a usable name is treated as no such person.
                parsePersonDetails(getJson("/3/person/$tmdbId?append_to_response=combined_credits,external_ids")) ?: throw TmdbNotFoundException()
            }
        } catch (_: TmdbNotFoundException) {
            null
        }
    }

    /** Null when TMDB knows no IMDb id for the title (that answer is cached too). */
    suspend fun imdbIdFor(kind: String, tmdbId: Long): String? {
        require(tmdbId > 0) { "TMDB ids are positive whole numbers" }
        val lookup = lookups.async {
            imdbIdCache.getOrLoad("$kind:$tmdbId") {
                try {
                    getJson("/3/$kind/$tmdbId/external_ids").objectOrNull()?.get("imdb_id").stringOrNull()?.takeIf(ImdbTitleShape::matches)
                } catch (_: TmdbNotFoundException) {
                    // TMDB no longer knowing the title is an answer ("none"), not a failure.
                    null
                }
            }
        }
        // Cancelling this wait leaves the lookup running (see [lookups]).
        return lookup.await()
    }

    suspend fun find(source: String, id: String): TmdbFindResult {
        require(FindIdShape.matches(id)) { "find id must be 1 to 32 letters or digits" }
        val externalSource = if (source == "tvdb") "tvdb_id" else "imdb_id"
        return findCache.getOrLoad("$source:$id") {
            try {
                parseFind(getJson("/3/find/${id.encodeURLParameter()}?external_source=$externalSource"))
            } catch (_: TmdbNotFoundException) {
                TmdbFindResult(emptyList(), emptyList(), emptyList())
            }
        }
    }

    suspend fun searchTitle(kind: String, name: String, year: Int? = null): List<TmdbTitleHit> {
        val q = name.scriptTrim()
        require(q.isNotEmpty() && q.length <= MaxTitleQuery) { "title query must be 1 to $MaxTitleQuery characters" }
        val yearParam = year?.let { "&${if (kind == "movie") "year" else "first_air_date_year"}=$it" }.orEmpty()
        return searchTitleCache.getOrLoad("$kind:${year ?: ""}:${q.lowercase()}") {
            val results = getJson("/3/search/$kind?query=${q.encodeURLParameter()}&include_adult=false$yearParam").objectOrNull()?.get("results") as? JsonArray
            results.orEmpty().mapNotNull { titleHit(it, if (kind == "movie") "movie" else "series") }
        }
    }
}

/**
 * Gives each credit its IMDb id, keeping the order. A credit TMDB has no IMDb
 * id for is left out. At most [concurrency] lookups run at once, and none
 * starts once [budgetMs] has passed; `partial` is true when any credit's id is
 * still unknown at the end, because the budget ran out or a lookup failed.
 */
suspend fun mapCreditsToImdb(
    imdbIdFor: suspend (kind: String, tmdbId: Long) -> String?,
    candidates: List<CreditCandidate>,
    now: () -> Long,
    budgetMs: Long = CreditMappingBudgetMs,
    concurrency: Int = CreditMappingConcurrency,
): Pair<List<PersonCredit>, Boolean> {
    val deadline = now() + budgetMs
    val ids = arrayOfNulls<String>(candidates.size)
    var partial = false
    var next = 0
    val lock = Mutex()
    coroutineScope {
        (0 until minOf(maxOf(1, concurrency), candidates.size)).map {
            async {
                while (true) {
                    val index = lock.withLock {
                        if (next >= candidates.size) return@withLock -1
                        if (now() >= deadline) {
                            partial = true
                            return@withLock -1
                        }
                        next++
                    }
                    if (index < 0) return@async
                    val credit = candidates[index]
                    val remaining = deadline - now()
                    val outcome = try {
                        withTimeoutOrNull(maxOf(1, remaining)) {
                            Result.success(imdbIdFor(if (credit.title.type == "movie") "movie" else "tv", credit.title.tmdbId))
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                    when {
                        outcome == null -> {
                            lock.withLock { partial = true }
                            return@async
                        }
                        outcome.isFailure -> lock.withLock { partial = true }
                        else -> ids[index] = outcome.getOrNull()
                    }
                }
            }
        }.awaitAll()
    }
    val credits = candidates.mapIndexedNotNull { index, candidate ->
        val imdbId = ids[index] ?: return@mapIndexedNotNull null
        val hit = candidate.title
        PersonCredit(hit.type, imdbId, hit.tmdbId, hit.name, hit.year, hit.poster, candidate.roles, candidate.character, hit.popularity)
    }
    return credits to partial
}

/**
 * A long text cut to at most [max] characters, ending on its last full
 * sentence when one ends in the second half of what fits; otherwise cut at
 * [max] as it is.
 */
fun endAtSentence(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.substring(0, max)
    var end = -1
    Regex("""[.!?]["')\]]?(?=${ScriptSpaceClass})""").findAll(cut).forEach { end = it.range.last + 1 }
    return if (end >= max / 2.0) cut.substring(0, end) else cut
}

/**
 * A person's titles from TMDB's `combined_credits`: cast, directing (crew job
 * "Director") and writing (crew department "Writing"), one entry per title
 * with its roles merged. Only films and series count; news, reality and talk
 * shows and appearances as oneself are dropped. Most popular first, at most
 * [MaxPersonCredits].
 */
fun selectCredits(combinedCredits: JsonElement?): List<CreditCandidate> {
    val credits = combinedCredits.objectOrNull() ?: return emptyList()
    class Building(val hit: TmdbTitleHit, val roles: MutableList<PersonCreditRole>, var character: String?, var popularity: Double)
    val byTitle = LinkedHashMap<String, Building>()
    fun add(entry: JsonElement, role: PersonCreditRole) {
        val hit = creditTitle(entry) ?: return
        val character = if (role == PersonCreditRole.Cast) tmdbText(entry.objectOrNull()?.get("character")) else null
        if (character != null && SelfCharacter.matches(character)) return
        val key = "${hit.type}:${hit.tmdbId}"
        val existing = byTitle[key]
        if (existing == null) {
            byTitle[key] = Building(hit, mutableListOf(role), character, hit.popularity)
            return
        }
        if (role !in existing.roles) existing.roles += role
        if (existing.character == null) existing.character = character
        existing.popularity = maxOf(existing.popularity, hit.popularity)
    }
    (credits["cast"] as? JsonArray).orEmpty().forEach { add(it, PersonCreditRole.Cast) }
    for (entry in (credits["crew"] as? JsonArray).orEmpty()) {
        val obj = entry.objectOrNull() ?: continue
        if (obj["job"].stringOrNull() == "Director") add(entry, PersonCreditRole.Director)
        if (obj["department"].stringOrNull() == "Writing") add(entry, PersonCreditRole.Writer)
    }
    return byTitle.values
        .map { CreditCandidate(it.hit.copy(popularity = it.popularity), it.roles.sortedBy(PersonCreditRole::ordinal), it.character) }
        .sortedWith(compareByDescending<CreditCandidate> { it.title.popularity }.thenBy { it.title.tmdbId })
        .take(MaxPersonCredits)
}

private fun creditTitle(entry: JsonElement): TmdbTitleHit? {
    val obj = entry.objectOrNull() ?: return null
    return when (obj["media_type"].stringOrNull()) {
        "tv" -> {
            val genres = (obj["genre_ids"] as? JsonArray).orEmpty()
            // Numbers only, as the desktop compares them: a quoted "10767" is not the genre.
            val excluded = genres.any { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull in ExcludedTvGenres }
            if (excluded) null else titleHit(entry, "series")
        }
        "movie" -> titleHit(entry, "movie")
        else -> null
    }
}

private fun tmdbText(value: JsonElement?): String? = value.stringOrNull()?.scriptTrim()?.takeIf { it.isNotEmpty() && it.length <= MaxText }

private fun tmdbPopularity(value: JsonElement?): Double {
    val primitive = value as? JsonPrimitive ?: return 0.0
    if (primitive.isString) return 0.0
    val number = primitive.doubleOrNull ?: return 0.0
    return if (number.isFinite() && number > 0) number else 0.0
}

private fun tmdbYear(value: JsonElement?): String? = value.stringOrNull()?.let { YearPrefix.find(it)?.groupValues?.get(1) }

private fun tmdbImageUrl(size: String, path: JsonElement?): String? =
    path.stringOrNull()?.takeIf(ImagePathShape::matches)?.let { "$TmdbImageBase/$size$it" }

private fun tmdbDepartment(value: JsonElement?): PersonDepartment? =
    value.stringOrNull()?.let { wire -> PersonDepartment.entries.firstOrNull { it.wire == wire } }

private fun titleHit(entry: JsonElement, type: String): TmdbTitleHit? {
    val obj = entry.objectOrNull() ?: return null
    val tmdbId = safePositiveInteger(obj["id"]) ?: return null
    val name = tmdbText(if (type == "movie") obj["title"] else obj["name"]) ?: return null
    return TmdbTitleHit(
        type = type,
        tmdbId = tmdbId,
        name = name,
        year = tmdbYear(if (type == "movie") obj["release_date"] else obj["first_air_date"]),
        poster = tmdbImageUrl("w342", obj["poster_path"]),
        popularity = tmdbPopularity(obj["popularity"]),
    )
}

private fun parsePeopleSearch(body: JsonElement): List<PersonSummary> {
    val people = mutableListOf<PersonSummary>()
    for (entry in (body.objectOrNull()?.get("results") as? JsonArray).orEmpty()) {
        if (people.size >= MaxSearchPeople) break
        val obj = entry.objectOrNull() ?: continue
        val tmdbId = safePositiveInteger(obj["id"]) ?: continue
        val name = tmdbText(obj["name"]) ?: continue
        val knownForTitles = (obj["known_for"] as? JsonArray).orEmpty()
            .mapNotNull { title -> title.objectOrNull()?.let { tmdbText(it["title"]) ?: tmdbText(it["name"]) } }
            .take(MaxKnownFor)
        people += PersonSummary(
            tmdbId = tmdbId,
            name = name,
            knownFor = tmdbDepartment(obj["known_for_department"]),
            photo = tmdbImageUrl("w185", obj["profile_path"]),
            popularity = tmdbPopularity(obj["popularity"]),
            knownForTitles = knownForTitles,
        )
    }
    return people
}

private fun parsePersonDetails(body: JsonElement): TmdbPersonDetails? {
    val obj = body.objectOrNull() ?: return null
    val tmdbId = safePositiveInteger(obj["id"]) ?: return null
    val name = tmdbText(obj["name"]) ?: return null
    val biography = obj["biography"].stringOrNull()?.scriptTrim()?.let { endAtSentence(it, MaxBiographyText) }.orEmpty()
    return TmdbPersonDetails(
        tmdbId = tmdbId,
        name = name,
        photo = tmdbImageUrl("w185", obj["profile_path"]),
        knownFor = tmdbDepartment(obj["known_for_department"]),
        biography = biography.ifEmpty { null },
        credits = selectCredits(obj["combined_credits"]),
    )
}

private fun parseFind(body: JsonElement): TmdbFindResult {
    val obj = body.objectOrNull() ?: return TmdbFindResult(emptyList(), emptyList(), emptyList())
    fun titles(value: JsonElement?, type: String) = (value as? JsonArray).orEmpty().mapNotNull { titleHit(it, type) }
    val people = (obj["person_results"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val person = entry.objectOrNull() ?: return@mapNotNull null
        val tmdbId = safePositiveInteger(person["id"]) ?: return@mapNotNull null
        val name = tmdbText(person["name"]) ?: return@mapNotNull null
        TmdbPersonHit(tmdbId, name, tmdbImageUrl("w185", person["profile_path"]))
    }
    return TmdbFindResult(titles(obj["movie_results"], "movie"), titles(obj["tv_results"], "series"), people)
}
