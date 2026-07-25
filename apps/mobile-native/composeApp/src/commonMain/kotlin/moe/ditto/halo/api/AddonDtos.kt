package moe.ditto.halo.api

import kotlinx.serialization.Serializable

/**
 * Stremio addon protocol types, as they reach this client.
 *
 * Halo never talks to addons directly — the API fetches, validates, and stores
 * every manifest, and its resolution endpoints return these shapes. That
 * validation is what lets `id`, `version`, and `name` stay non-null here:
 * a manifest missing them is rejected before it can be stored, and a client
 * cannot inject one.
 *
 * Fields addons emit inconsistently are nullable; collections default to empty
 * so a null or absent array degrades into "supports nothing" rather than a
 * failed decode.
 */

@Serializable(with = ManifestResourceSerializer::class)
data class ManifestResource(
    val name: String,
    /** Null when the addon used the shorthand string form, which constrains nothing. */
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

/** Object form of a manifest resource; only ever used through [ManifestResourceSerializer]. */
@Serializable
internal data class DetailedResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

@Serializable
data class CatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int? = null,
)

@Serializable
data class ManifestCatalog(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<CatalogExtra> = emptyList(),
    /** Legacy alternatives to [extra], still emitted alongside it by Cinemeta. */
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
) {
    /**
     * Whether this catalog can answer a search query. Both spellings must be
     * checked: addons that predate the `extra` array advertise search only
     * through `extraSupported`, and search rows come from nothing else.
     */
    val supportsSearch: Boolean
        get() = extra.any { it.name == SearchExtra } || extraSupported.contains(SearchExtra)

    companion object {
        const val SearchExtra = "search"
    }
}

@Serializable
data class ManifestBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

@Serializable
data class Manifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val contactEmail: String? = null,
    val resources: List<ManifestResource> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<ManifestCatalog> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: ManifestBehaviorHints? = null,
)

/**
 * The fields every browsable entry carries, whether it came from a catalog row
 * or a full meta lookup — poster grids and cards accept either.
 */
interface MetaCard {
    val id: String
    val type: String
    val name: String
    val poster: String?
    val background: String?
    val logo: String?
    val description: String?
    val releaseInfo: String?
    val imdbRating: String?
    val genres: List<String>

    /**
     * Addon-declared card aspect ("square", "poster", "landscape"). Deliberately
     * a plain string: an unrecognised shape must fall back to the default, not
     * fail the whole catalog the way an enum would.
     */
    val posterShape: String?
}

@Serializable
data class MetaPreview(
    override val id: String,
    override val type: String,
    override val name: String,
    override val poster: String? = null,
    override val posterShape: String? = null,
    override val background: String? = null,
    override val logo: String? = null,
    override val description: String? = null,
    override val releaseInfo: String? = null,
    override val imdbRating: String? = null,
    override val genres: List<String> = emptyList(),
) : MetaCard

@Serializable
data class MetaVideo(
    val id: String,
    val title: String? = null,
    /** Cinemeta populates this instead of [title]; read both through [displayTitle]. */
    val name: String? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val displayTitle: String?
        get() = title ?: name
}

@Serializable
data class MetaDetail(
    override val id: String,
    override val type: String,
    override val name: String,
    override val poster: String? = null,
    override val posterShape: String? = null,
    override val background: String? = null,
    override val logo: String? = null,
    override val description: String? = null,
    override val releaseInfo: String? = null,
    override val imdbRating: String? = null,
    override val genres: List<String> = emptyList(),
    val videos: List<MetaVideo> = emptyList(),
    val runtime: String? = null,
    val language: String? = null,
    val country: String? = null,
    val awards: String? = null,
    val website: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
) : MetaCard

@Serializable
data class StreamBehaviorHints(
    /** Not directly playable in a browser (e.g. requires transcoding). */
    val notWebReady: Boolean = false,
    /** Streams sharing a bingeGroup keep the same source across episodes. */
    val bingeGroup: String? = null,
    val proxyHeaders: ProxyHeaders? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
)

@Serializable
data class ProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

@Serializable
data class Stream(
    /** Direct URL — the only source kind Halo plays (debrid/HTTP). */
    val url: String? = null,
    /** Torrent sources — recognised so they can be filtered out, never played. */
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val behaviorHints: StreamBehaviorHints? = null,
) {
    /** Halo plays direct URLs only; everything else is listed nowhere. */
    val isPlayable: Boolean
        get() = !url.isNullOrBlank()
}

@Serializable
data class Subtitle(
    val id: String,
    val url: String,
    /** ISO 639-2 in practice (e.g. "eng", "ger"), but addons are not held to it. */
    val lang: String,
)

@Serializable
data class CatalogResponse(
    val metas: List<MetaPreview> = emptyList(),
)

@Serializable
data class MetaResponse(
    val meta: MetaDetail,
)
