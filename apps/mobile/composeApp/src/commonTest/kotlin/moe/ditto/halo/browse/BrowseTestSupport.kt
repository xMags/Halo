package moe.ditto.halo.browse

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.CatalogResponse
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.api.CatalogExtra
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.auth.TokenProvider

private object StaticTokens : TokenProvider {
    override suspend fun accessToken(): String = "token"

    override suspend fun refreshAccessToken(): String = "token"
}

/**
 * A real client over a mock transport that can answer with a status as well as
 * a body. Browse endpoints fan out across third-party addons, so "this one
 * returned 502" is a first-class case rather than an error path.
 */
internal class BrowseApi(private val reply: (HttpRequestData) -> Pair<HttpStatusCode, String>) {
    val requests = mutableListOf<HttpRequestData>()

    val client: HaloClient = HaloClient(
        baseUrl = "https://halo.test",
        tokens = StaticTokens,
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                val (status, body) = reply(request)
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ),
    )

    fun reads(path: String): List<HttpRequestData> =
        requests.filter { it.method == HttpMethod.Get && it.url.encodedPath == path }

    fun writtenBodies(): List<String> = requests.mapNotNull { (it.body as? TextContent)?.text }
}

/** Answers every request with [body]; the common case for a read-only screen. */
internal fun browseApi(body: (HttpRequestData) -> String) = BrowseApi { HttpStatusCode.OK to body(it) }

internal fun addonsJson(global: List<AddonEntry> = emptyList(), user: List<AddonEntry> = emptyList()): String =
    HaloJson.encodeToString(AddonsResponse.serializer(), AddonsResponse(global, user))

internal fun catalogJson(metas: List<MetaPreview>): String =
    HaloJson.encodeToString(CatalogResponse.serializer(), CatalogResponse(metas))

internal fun addon(
    id: String,
    name: String = id.replaceFirstChar { it.uppercase() },
    catalogs: List<ManifestCatalog> = emptyList(),
    position: Int = 0,
) = AddonEntry(
    id = id,
    manifest = Manifest(id = "$id.addon", version = "1.0.0", name = name, catalogs = catalogs),
    position = position,
)

internal fun catalog(
    type: String,
    id: String,
    name: String? = null,
    extra: List<CatalogExtra> = emptyList(),
    extraSupported: List<String> = emptyList(),
    extraRequired: List<String> = emptyList(),
) = ManifestCatalog(
    type = type,
    id = id,
    name = name,
    extra = extra,
    extraSupported = extraSupported,
    extraRequired = extraRequired,
)

internal fun meta(id: String, type: String = "movie", name: String = id) =
    MetaPreview(id = id, type = type, name = name)

internal fun video(id: String, season: Int? = null, episode: Int? = null, title: String? = null) =
    MetaVideo(id = id, title = title, season = season, episode = episode)

internal fun watchState(
    videoId: String,
    itemId: String,
    positionSec: Double = 300.0,
    durationSec: Double = 1_000.0,
    watched: Boolean = false,
    name: String? = null,
    poster: String? = null,
    updatedAt: Long = 1_000,
) = WatchState(
    videoId = videoId,
    itemId = itemId,
    positionSec = positionSec,
    durationSec = durationSec,
    watched = watched,
    name = name,
    poster = poster,
    updatedAt = updatedAt,
)

internal fun libraryItem(
    id: String,
    name: String = id,
    poster: String? = null,
    addedAt: Long = 1_000,
    removedAt: Long? = null,
) = LibraryItem(
    id = id,
    type = id.substringBefore(':'),
    name = name,
    poster = poster,
    addedAt = addedAt,
    removedAt = removedAt,
    updatedAt = addedAt,
)
