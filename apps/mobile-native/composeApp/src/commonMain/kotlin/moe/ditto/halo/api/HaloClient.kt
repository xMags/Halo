package moe.ditto.halo.api

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ditto.halo.auth.TokenProvider

/**
 * The server answered with a non-success status. Transport failures are not
 * this type — they surface as the HTTP client's own exceptions, because the
 * two have opposite session consequences: a 401 that survives a refresh is a
 * dead session, while a network error must leave it intact.
 */
class HaloApiException(
    val status: Int,
    message: String,
) : Exception(message)

/**
 * Typed client for the Halo API.
 *
 * Holds no auth state: every request draws a bearer token from [TokenProvider],
 * and a 401 buys exactly one forced refresh and retry. When that refresh
 * returns null the auth layer has already cleared the session, so this class
 * only reports the failure — it never signs anyone out itself. Concurrent
 * requests hitting 401 together are safe because refreshing is single-flight
 * beneath the provider.
 *
 * `GET /auth/config` is deliberately absent: it is public, is called before a
 * server is even chosen, and is already owned by the auth layer's config
 * source. Everything here requires a session.
 */
class HaloClient(
    baseUrl: String,
    private val tokens: TokenProvider,
    private val httpClient: HttpClient,
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    /** The authenticated user including admin status; drives admin-only UI. */
    suspend fun getMe(): Me = request(HttpMethod.Get, "/auth/me", Me.serializer())

    /** Global (admin-managed) addons plus the caller's own, each ordered by position. */
    suspend fun getAddons(): AddonsResponse =
        request(HttpMethod.Get, "/addons", AddonsResponse.serializer())

    /**
     * Declares the caller's own addon list, array order being priority. Only
     * transport URLs are sent — the server fetches and validates each manifest
     * itself and applies the list as a diff, so unchanged entries keep their ids.
     */
    suspend fun putAddons(transportUrls: List<String>): List<AddonEntry> = request(
        HttpMethod.Put,
        "/addons",
        ListSerializer(AddonEntry.serializer()),
        body = HaloJson.encodeToString(ListSerializer(String.serializer()), transportUrls),
    )

    /** Admin-only: declares the global addon list shown to every user. Same diff contract. */
    suspend fun putGlobalAddons(transportUrls: List<String>): List<AddonEntry> = request(
        HttpMethod.Put,
        "/addons/global",
        ListSerializer(AddonEntry.serializer()),
        body = HaloJson.encodeToString(ListSerializer(String.serializer()), transportUrls),
    )

    /** Per-addon knobs on the caller's own entries (currently catalog visibility). */
    suspend fun patchAddon(addonId: String, hideCatalogs: Boolean) {
        exchange(
            HttpMethod.Patch,
            "/addons/${addonId.encodeURLPathPart()}",
            body = HaloJson.encodeToString(AddonPatch.serializer(), AddonPatch(hideCatalogs)),
        )
    }

    /** Admin-only: the same knob on a global entry, applying to every user. */
    suspend fun patchGlobalAddon(addonId: String, hideCatalogs: Boolean) {
        exchange(
            HttpMethod.Patch,
            "/addons/global/${addonId.encodeURLPathPart()}",
            body = HaloJson.encodeToString(AddonPatch.serializer(), AddonPatch(hideCatalogs)),
        )
    }

    /**
     * One catalog, resolved server-side. [addonId] is the opaque [AddonEntry.id];
     * clients never address an addon by transport URL, which can embed secrets.
     */
    suspend fun getCatalog(
        addonId: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): CatalogResponse = request(
        HttpMethod.Get,
        "/catalog",
        CatalogResponse.serializer(),
        query = mapOf("addon" to addonId, "type" to type, "id" to id) + extra,
    )

    /** First effective addon that can describe this type/id wins; 404 when none can. */
    suspend fun getMeta(type: String, id: String): MetaResponse = request(
        HttpMethod.Get,
        "/meta",
        MetaResponse.serializer(),
        query = mapOf("type" to type, "id" to id),
    )

    /** Fans out to every effective addon; playable streams grouped by addon. */
    suspend fun getStreams(type: String, videoId: String): StreamsResult = request(
        HttpMethod.Get,
        "/streams",
        StreamsResult.serializer(),
        query = mapOf("type" to type, "videoId" to videoId),
    )

    /**
     * The episode following [videoId] plus, when the addon that served the
     * current stream still exists, that episode's stream with the same
     * bingeGroup — Stremio's binge-continuation rule of exact group equality
     * from the same addon only.
     */
    suspend fun getNextEpisode(
        type: String,
        metaId: String,
        videoId: String,
        addonId: String? = null,
        bingeGroup: String? = null,
    ): NextEpisodeResult = request(
        HttpMethod.Get,
        "/next-episode",
        NextEpisodeResult.serializer(),
        query = buildMap {
            put("type", type)
            put("metaId", metaId)
            put("videoId", videoId)
            addonId?.let { put("addon", it) }
            bingeGroup?.let { put("bingeGroup", it) }
        },
    )

    /**
     * External subtitles from every subtitle-capable addon. Passing
     * [videoHash]/[videoSize] is what turns guesswork into exact matches, so
     * callers should compute them whenever the source allows it.
     */
    suspend fun getSubtitles(
        type: String,
        videoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
    ): SubtitlesResult = request(
        HttpMethod.Get,
        "/subtitles",
        SubtitlesResult.serializer(),
        query = buildMap {
            put("type", type)
            put("videoId", videoId)
            videoHash?.let { put("videoHash", it) }
            videoSize?.let { put("videoSize", it.toString()) }
            filename?.let { put("filename", it) }
        },
    )

    /** Includes tombstones (`removedAt` set) so removals sync instead of resurrecting. */
    suspend fun getLibrary(): List<LibraryItem> =
        request(HttpMethod.Get, "/library", ListSerializer(LibraryItem.serializer()))

    /** Batched upsert; the response is the caller's full library after merging. */
    suspend fun putLibrary(items: List<LibraryItem>): List<LibraryItem> = request(
        HttpMethod.Put,
        "/library",
        ListSerializer(LibraryItem.serializer()),
        body = HaloJson.encodeToString(ListSerializer(LibraryItem.serializer()), items),
    )

    suspend fun getWatchStates(): List<WatchState> =
        request(HttpMethod.Get, "/watch-state", ListSerializer(WatchState.serializer()))

    /**
     * Batched upsert applied last-write-wins per row; the response is the
     * caller's full watch state after merging, not just the rows sent.
     */
    suspend fun putWatchStates(states: List<WatchState>): List<WatchState> = request(
        HttpMethod.Put,
        "/watch-state",
        ListSerializer(WatchState.serializer()),
        body = HaloJson.encodeToString(ListSerializer(WatchState.serializer()), states),
    )

    suspend fun getSettings(): SettingsPayload =
        request(HttpMethod.Get, "/settings", SettingsPayload.serializer())

    /** Last-write-wins: the server keeps whichever payload carries the newer timestamp. */
    suspend fun putSettings(value: UserSettings, updatedAt: Long): SettingsPayload = request(
        HttpMethod.Put,
        "/settings",
        SettingsPayload.serializer(),
        body = HaloJson.encodeToString(SettingsPayload.serializer(), SettingsPayload(value, updatedAt)),
    )

    /**
     * URL that fetches [target] through the API's proxy. The server does not
     * origin-allowlist; it authenticates the request and rejects targets
     * resolving to private or reserved addresses, re-validating every redirect.
     */
    fun proxyUrl(target: String): String = "$baseUrl/addon-proxy?url=${target.encodeUriComponent()}"

    private suspend fun <T> request(
        method: HttpMethod,
        path: String,
        serializer: KSerializer<T>,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
    ): T {
        val text = exchange(method, path, body, query)
        return try {
            HaloJson.decodeFromString(serializer, text)
        } catch (error: SerializationException) {
            throw IllegalStateException("Malformed response from $path", error)
        }
    }

    /**
     * Runs one request, spending a single forced refresh if the first attempt
     * comes back 401, and returns the success body. A 401 that survives the
     * retry means the session is gone rather than stale.
     */
    private suspend fun exchange(
        method: HttpMethod,
        path: String,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
    ): String {
        var response = execute(method, path, body, query, tokens.accessToken())
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshed = tokens.refreshAccessToken() ?: throw HaloApiException(401, Unauthorized)
            response = execute(method, path, body, query, refreshed)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw HaloApiException(
                response.status.value,
                errorMessage(text) ?: "HTTP ${response.status.value} from $path",
            )
        }
        return text
    }

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: String?,
        query: Map<String, String>,
        token: String?,
    ): HttpResponse = httpClient.request("$baseUrl$path") {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        query.forEach { (name, value) -> parameter(name, value) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Error bodies are `{"error": ...}`; anything else falls back to the status. */
    private fun errorMessage(body: String): String? = try {
        HaloJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val Unauthorized = "Unauthorized"
    }
}

private const val HexDigits = "0123456789ABCDEF"

/** Characters JavaScript's encodeURIComponent leaves alone besides alphanumerics. */
private const val UnreservedPunctuation = "-_.!~*'()"

/**
 * Percent-encodes a value exactly as `encodeURIComponent` does.
 *
 * Ktor's query encoder is close but escapes more than JavaScript's — `.`
 * becomes `%2E`, for instance. Both forms decode to the same target, so this
 * is not a correctness fix; it keeps every Halo client emitting one canonical
 * proxy URL for a given asset, so anything keyed on the URL string (image
 * caches most of all) sees a hit rather than two spellings of one resource.
 */
internal fun String.encodeUriComponent(): String = buildString {
    for (byte in this@encodeUriComponent.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char in UnreservedPunctuation
        if (unreserved) {
            append(char)
        } else {
            append('%').append(HexDigits[code shr 4]).append(HexDigits[code and 0x0F])
        }
    }
}
