package moe.ditto.halo.device

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.AddonSubtitles
import moe.ditto.halo.api.CatalogResponse
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.MetaResponse
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.NextEpisodeResult
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.api.SubtitlesResult
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * Addon resolution: which installed addons to ask, in what order, and how
 * their answers combine. A Kotlin port of `packages/core/src/addon/resolve.ts`,
 * `manifest.ts` and `nextVideo.ts`, which the API server runs for signed-in
 * users; keep them in step, so Halo answers the same with or without an
 * account.
 *
 * One deliberate omission: the shared resolver also asks an installed TMDB
 * addon for a pictured cast. This app shows no cast, so that request is not
 * made.
 */

/** Per-addon request budget for every resolution call, the server's. */
internal const val ResolveTimeoutMillis = 10_000L

private const val MaxCatalogExtras = 8
private const val MaxCatalogExtraKeyLength = 64
private const val MaxCatalogExtraValueLength = 256
private const val MaxSubtitleFilenameLength = 1_024
private val VideoHashPattern = Regex("^[0-9a-fA-F]{16}$")

/** An installed addon as resolution needs it: always with its transport URL. */
internal data class ResolvableAddon(
    val id: String,
    val transportUrl: String,
    val manifest: Manifest,
)

internal data class SubtitleQuery(
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
)

/** Why a catalog request's extras are refused, or null when they are fine. */
internal fun catalogExtraProblem(extra: Map<String, String>): String? {
    if (extra.size > MaxCatalogExtras) return "too many extra params"
    if (extra.keys.any { it.length > MaxCatalogExtraKeyLength } ||
        extra.values.any { it.length > MaxCatalogExtraValueLength }
    ) {
        return "extra param too long"
    }
    return null
}

/** Why a subtitle query is refused, or null when it is fine. */
internal fun subtitleQueryProblem(query: SubtitleQuery): String? {
    if (query.videoHash != null && !VideoHashPattern.matches(query.videoHash)) return "videoHash must be 16 hex chars"
    if (query.videoSize != null && query.videoSize <= 0) return "videoSize must be a positive integer"
    if (query.filename != null && query.filename.length > MaxSubtitleFilenameLength) return "filename too long"
    return null
}

/**
 * Asks addons directly, under the server's per-addon budget. Each call's
 * failure rules follow the shared resolver's: a catalog failure throws, a
 * meta lookup moves on to the next capable addon, and the fan-outs report
 * each failed addon beside the others' answers.
 */
internal class AddonResolver(
    private val http: HttpClient,
    private val timeoutMillis: Long = ResolveTimeoutMillis,
) {
    /** One catalog from one addon. Throws when the addon fails; the caller decides what that means. */
    suspend fun catalog(addon: ResolvableAddon, type: String, id: String, extra: Map<String, String>): CatalogResponse {
        val json = fetch(resourceUrl(addon.transportUrl, "catalog", type, id, extra))
        return decode(json) { HaloJson.decodeFromJsonElement(CatalogResponse.serializer(), it) }
    }

    /**
     * First addon that can describe this type/id wins; null if none can. An
     * answer that is not a usable meta counts as that addon failing, so the
     * next one is asked.
     */
    suspend fun meta(addons: List<ResolvableAddon>, type: String, id: String): MetaResponse? {
        for (addon in addons) {
            if (!addon.manifest.supportsResource("meta", type, id)) continue
            try {
                val json = fetch(resourceUrl(addon.transportUrl, "meta", type, id))
                return decode(json) { HaloJson.decodeFromJsonElement(MetaResponse.serializer(), it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Try the next addon that can describe this id.
            }
        }
        return null
    }

    /** Asks every stream-capable addon at once; playable streams grouped by addon, plus each failure. */
    suspend fun streams(addons: List<ResolvableAddon>, type: String, videoId: String): StreamsResult {
        val capable = addons.filter { it.manifest.supportsResource("stream", type, videoId) }
        val settled = settleAll(capable) { addon ->
            normalizeStreamsResponse(fetch(resourceUrl(addon.transportUrl, "stream", type, videoId)))
        }
        val results = mutableListOf<AddonStreams>()
        val errors = mutableListOf<AddonError>()
        settled.forEachIndexed { index, outcome ->
            val addon = capable[index]
            outcome.fold(
                onSuccess = { streams ->
                    if (streams.isNotEmpty()) results += AddonStreams(addon.source(), streams)
                },
                onFailure = { errors += addonError(addon.id, addon.manifest.name, it) },
            )
        }
        return StreamsResult(results, errors)
    }

    /** Asks every subtitle-capable addon at once. `hashMatched` is true iff a video hash was sent. */
    suspend fun subtitles(
        addons: List<ResolvableAddon>,
        type: String,
        videoId: String,
        query: SubtitleQuery,
    ): SubtitlesResult {
        val capable = addons.filter { it.manifest.supportsResource("subtitles", type, videoId) }
        val extra = buildMap {
            query.videoHash?.takeIf { it.isNotEmpty() }?.let { put("videoHash", it) }
            query.videoSize?.takeIf { it > 0 }?.let { put("videoSize", it.toString()) }
            query.filename?.takeIf { it.isNotEmpty() }?.let { put("filename", it) }
        }
        val settled = settleAll(capable) { addon ->
            normalizeSubtitlesResponse(fetch(resourceUrl(addon.transportUrl, "subtitles", type, videoId, extra)))
        }
        val results = mutableListOf<AddonSubtitles>()
        val errors = mutableListOf<AddonError>()
        settled.forEachIndexed { index, outcome ->
            val addon = capable[index]
            outcome.fold(
                onSuccess = { results += AddonSubtitles(addon.source(), it) },
                onFailure = { errors += addonError(addon.id, addon.manifest.name, it) },
            )
        }
        return SubtitlesResult(results, errors, hashMatched = query.videoHash != null)
    }

    /**
     * Binge continuation: the episode after [videoId] in [metaId]'s ordering,
     * plus, when the addon that served the current stream is still installed,
     * that addon's stream for the next episode with the same bingeGroup.
     * Stremio's rule exactly: same addon, exact group equality. A null stream
     * means "fall back to the stream picker". Null when no addon can describe
     * the title at all.
     */
    suspend fun nextEpisode(
        addons: List<ResolvableAddon>,
        type: String,
        metaId: String,
        videoId: String,
        addonId: String?,
        bingeGroup: String?,
        nowMillis: Long,
    ): NextEpisodeResult? {
        val meta = meta(addons, type, metaId) ?: return null
        val next = nextVideo(meta.meta.videos, videoId, nowMillis) ?: return NextEpisodeResult(null, null)

        // A missing addon is benign: it was uninstalled mid-playback. The next
        // episode is still reported, just without a matched stream.
        val entry = addonId?.let { id -> addons.firstOrNull { it.id == id } }
        var stream: Stream? = null
        if (entry != null && bingeGroup != null && entry.manifest.supportsResource("stream", type, next.id)) {
            try {
                val json = fetch(resourceUrl(entry.transportUrl, "stream", type, next.id))
                stream = normalizeStreamsResponse(json).firstOrNull { it.behaviorHints?.bingeGroup == bingeGroup }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort: an unreachable addon degrades to the picker, not a failure.
            }
        }
        return NextEpisodeResult(next, stream)
    }

    /**
     * Fetches and validates the manifest for every URL, all or nothing: the
     * manifests in order, or the first URL whose manifest could not be
     * fetched or is invalid. Fetches run concurrently.
     */
    suspend fun manifests(urls: List<String>): ManifestsResult {
        val settled = settleAll(urls) { url -> parseManifest(fetch(manifestUrl(url))) ?: throw InvalidAddonResponseException() }
        val failed = settled.indexOfFirst { it.isFailure }
        if (failed >= 0) return ManifestsResult.Failed("could not fetch a valid manifest for ${urls[failed]}")
        return ManifestsResult.Fetched(urls.zip(settled.map { it.getOrThrow() }).toMap())
    }

    /** One addon request under its budget; a timeout is reported as the addon's failure, not a cancellation. */
    private suspend fun fetch(url: String): JsonElement = try {
        withTimeout(timeoutMillis) { http.getAddonJson(url) }
    } catch (_: TimeoutCancellationException) {
        throw AddonTimeoutException()
    }

    /**
     * Runs [block] for every item at once and keeps each outcome, like
     * `Promise.allSettled`. Cancelling the caller still cancels them all.
     */
    private suspend fun <T, R> settleAll(items: List<T>, block: suspend (T) -> R): List<Result<R>> = coroutineScope {
        items.map { item ->
            async {
                try {
                    Result.success(block(item))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
        }.awaitAll()
    }
}

internal sealed interface ManifestsResult {
    data class Fetched(val manifests: Map<String, Manifest>) : ManifestsResult

    data class Failed(val message: String) : ManifestsResult
}

private fun ResolvableAddon.source() = AddonSource(id = id, name = safeAddonName(manifest.name))

private inline fun <T> decode(json: JsonElement, read: (JsonElement) -> T): T = try {
    read(json)
} catch (_: SerializationException) {
    throw InvalidAddonResponseException()
} catch (_: IllegalArgumentException) {
    throw InvalidAddonResponseException()
}

/**
 * Validates a manifest fetched from a third-party addon, loosely on purpose:
 * only the fields Halo depends on are checked. Null when one is missing or
 * has the wrong shape. Load-bearing: a non-empty `id` and `name`, a string
 * `version`, `types` as strings, `resources` as names or objects with a string
 * `name`, and `catalogs` as objects with string `type` and `id`.
 */
internal fun parseManifest(value: JsonElement): Manifest? {
    if (value !is JsonObject) return null
    if (value["id"].string().isNullOrEmpty() || value["version"].string() == null ||
        value["name"].string().isNullOrEmpty()
    ) {
        return null
    }
    val types = value["types"] as? JsonArray ?: return null
    if (!types.all { it.string() != null }) return null
    val resources = value["resources"] as? JsonArray ?: return null
    if (!resources.all { it.string() != null || (it is JsonObject && it["name"].string() != null) }) return null
    val catalogs = value["catalogs"] as? JsonArray ?: return null
    if (!catalogs.all { it is JsonObject && it["type"].string() != null && it["id"].string() != null }) return null
    return try {
        HaloJson.decodeFromJsonElement(Manifest.serializer(), value)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Specials (season 0) sort after every regular season, Stremio's convention. */
private fun seasonKey(season: Int): Int = if (season == 0) Int.MAX_VALUE else season

/**
 * The episode that follows [currentVideoId] in playback order: seasons
 * ascending with specials last, episodes ascending within a season. Videos
 * missing numbers keep their order (the sort is stable). Null when the
 * current video is not listed, is the last one, or its follower is released
 * after [nowMillis]: meta addons list unaired episodes, and "next" must never
 * point at something without streams.
 */
internal fun nextVideo(videos: List<MetaVideo>, currentVideoId: String, nowMillis: Long): MetaVideo? {
    val ordered = videos.sortedWith { a, b ->
        val aSeason = a.season
        val bSeason = b.season
        val aEpisode = a.episode
        val bEpisode = b.episode
        if (aSeason == null || bSeason == null || aEpisode == null || bEpisode == null) {
            0
        } else {
            seasonKey(aSeason).compareTo(seasonKey(bSeason)).takeIf { it != 0 } ?: aEpisode.compareTo(bEpisode)
        }
    }
    val index = ordered.indexOfFirst { it.id == currentVideoId }
    if (index == -1 || index == ordered.lastIndex) return null
    val next = ordered[index + 1]
    val released = next.released?.let(::releaseMillis)
    if (released != null && released > nowMillis) return null
    return next
}

private val DateOnly = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * A release date as epoch milliseconds. Addons send ISO 8601 timestamps, and
 * a bare date means its midnight UTC, as JavaScript reads it. Anything else is
 * unknown, which counts as released.
 */
@OptIn(ExperimentalTime::class)
private fun releaseMillis(released: String): Long? {
    val text = if (DateOnly.matches(released)) "${released}T00:00:00Z" else released
    return try {
        Instant.parse(text).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }
}
