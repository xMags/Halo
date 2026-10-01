package moe.ditto.halo.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * DTOs owned by the Halo API itself (as opposed to the addon protocol).
 * Timestamps are Unix milliseconds.
 *
 * Server-side validation these must respect on the way out, because a single
 * bad row rejects the whole batch with 400:
 *  - `poster` must parse as a URL when present — omit it rather than sending
 *    a blank or relative value from an addon meta.
 *  - `name` must be 1..512 characters.
 *  - timestamps must be positive integers.
 */

@Serializable
data class AddonEntry(
    /**
     * Opaque server-assigned id; resolution endpoints are addressed by it.
     * Stable while the addon stays installed.
     */
    val id: String,
    /**
     * Absent on global entries for non-admin callers: transport URLs can embed
     * secrets (e.g. debrid API keys), so only the opaque id leaves the server.
     */
    val transportUrl: String? = null,
    val manifest: Manifest,
    val position: Int,
    /**
     * True when the server has already stripped this entry's catalogs from
     * [manifest] on the wire. Nothing needs filtering client-side; the flag
     * only lets settings tell "hidden" apart from "has no catalogs".
     */
    val hideCatalogs: Boolean = false,
)

@Serializable
data class AddonsResponse(
    val global: List<AddonEntry> = emptyList(),
    val user: List<AddonEntry> = emptyList(),
) {
    /** Resolution order: global addons first, then the caller's own. */
    val effective: List<AddonEntry>
        get() = global + user
}

@Serializable
data class Me(
    val id: String,
    val username: String,
    /**
     * Computed server-side per request (OIDC groups claim / local column) and
     * never derived from the token — admin UI gates on this alone.
     */
    val isAdmin: Boolean,
    val createdAt: Long,
)

@Serializable
data class LibraryItem(
    /** `"${type}:${metaId}"`, e.g. "movie:tt0111161". */
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val addedAt: Long,
    /** Soft delete, so a removal syncs instead of being resurrected by a stale device. */
    val removedAt: Long? = null,
    /** Client-set; the server keeps whichever write is newest. */
    val updatedAt: Long,
) {
    val isRemoved: Boolean
        get() = removedAt != null
}

@Serializable
data class WatchState(
    /** Meta id for movies, video id (e.g. "tt0944947:1:2") for episodes. */
    val videoId: String,
    /** Library item id this video belongs to. */
    val itemId: String,
    /** Fractional seconds — the server validates a minimum, not an integer. */
    val positionSec: Double,
    val durationSec: Double,
    val watched: Boolean,
    /** Denormalised display fields, per-row last-write-wins like the rest. */
    val name: String? = null,
    val poster: String? = null,
    val updatedAt: Long,
)

/**
 * Per-addon knobs, sent separately from the addon list so that list stays a
 * plain array of transport URLs.
 */
@Serializable
internal data class AddonPatch(val hideCatalogs: Boolean)

/** Identifies which effective addon a resolution result came from. */
@Serializable
data class AddonSource(
    val id: String,
    val name: String,
)

/** Per-addon failure surfaced by the fan-out endpoints (never a stack trace). */
@Serializable
data class AddonError(
    val id: String,
    /** Legacy compatibility field. New servers guarantee sanitized text. */
    val message: String,
    /** Optional so this client remains compatible with older Halo servers. */
    val name: String? = null,
    val code: String? = null,
    val status: Int? = null,
)

@Serializable
data class AddonStreams(
    val addon: AddonSource,
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class StreamsResult(
    val results: List<AddonStreams> = emptyList(),
    val errors: List<AddonError> = emptyList(),
)

@Serializable
data class AddonSubtitles(
    val addon: AddonSource,
    val subtitles: List<Subtitle> = emptyList(),
)

@Serializable
data class SubtitlesResult(
    val results: List<AddonSubtitles> = emptyList(),
    val errors: List<AddonError> = emptyList(),
    /** True only when a videoHash was sent, i.e. results are exact matches. */
    val hashMatched: Boolean = false,
)

/**
 * `video` null = nothing follows (series over, unknown episode, or unaired).
 * `stream` null = no same-addon bingeGroup match, so the caller should fall
 * back to the stream picker rather than autoplaying something unrelated.
 */
@Serializable
data class NextEpisodeResult(
    val video: MetaVideo? = null,
    val stream: Stream? = null,
)

/** Player framing preference. Unknown wire values read as null, not a failure. */
enum class VideoFitMode(val wire: String) {
    Cover("cover"),
    Contain("contain"),
    ;

    companion object {
        fun fromWire(value: String?): VideoFitMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Subtitle outline weight. Unknown wire values read as null, not a failure. */
enum class SubtitleOutline(val wire: String) {
    None("none"),
    Thin("thin"),
    Normal("normal"),
    Thick("thick"),
    ;

    companion object {
        fun fromWire(value: String?): SubtitleOutline? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Synced user preferences, held as the raw JSON object rather than a typed
 * record.
 *
 * This is deliberate and load-bearing. The server validates known fields but
 * stores the blob with unknown ones intact (`.passthrough()`), precisely so a
 * client that predates a setting cannot delete it. Decoding into a fixed
 * Kotlin class would drop unknown keys on read and omit them on the next
 * write — silently destroying preferences written by the desktop client or a
 * newer build. Keeping the object whole and patching keys individually is what
 * preserves that guarantee.
 *
 * Typed accessors read tolerantly: a value of the wrong JSON type reads as
 * null and the caller falls back to its default, rather than failing.
 */
@Serializable(with = UserSettingsSerializer::class)
data class UserSettings(val raw: JsonObject = EmptySettings) {
    val preferredAudioLang: String? get() = string("preferredAudioLang")
    val preferredSubtitleLang: String? get() = string("preferredSubtitleLang")
    val videoFitMode: VideoFitMode? get() = VideoFitMode.fromWire(string("videoFitMode"))
    val subtitleScalePercent: Int? get() = int("subtitleScalePercent")
    val subtitleFontFamily: String? get() = string("subtitleFontFamily")
    val subtitleOutline: SubtitleOutline? get() = SubtitleOutline.fromWire(string("subtitleOutline"))
    val subtitleShadow: Boolean? get() = boolean("subtitleShadow")
    val playbackRate: Double? get() = double("playbackRate")
    val autoplayNextEpisode: Boolean? get() = boolean("autoplayNextEpisode")

    fun withPreferredAudioLang(value: String?) = put("preferredAudioLang", value?.let(::JsonPrimitive))

    fun withPreferredSubtitleLang(value: String?) = put("preferredSubtitleLang", value?.let(::JsonPrimitive))

    fun withVideoFitMode(value: VideoFitMode?) = put("videoFitMode", value?.let { JsonPrimitive(it.wire) })

    fun withSubtitleScalePercent(value: Int?) = put("subtitleScalePercent", value?.let(::JsonPrimitive))

    fun withSubtitleFontFamily(value: String?) = put("subtitleFontFamily", value?.let(::JsonPrimitive))

    fun withSubtitleOutline(value: SubtitleOutline?) = put("subtitleOutline", value?.let { JsonPrimitive(it.wire) })

    fun withSubtitleShadow(value: Boolean?) = put("subtitleShadow", value?.let(::JsonPrimitive))

    fun withPlaybackRate(value: Double?) = put("playbackRate", value?.let(::JsonPrimitive))

    fun withAutoplayNextEpisode(value: Boolean?) = put("autoplayNextEpisode", value?.let(::JsonPrimitive))

    /** Sets [key], or removes it when [value] is null. Every other key survives untouched. */
    private fun put(key: String, value: JsonElement?): UserSettings =
        UserSettings(JsonObject(if (value == null) raw.minus(key) else raw.plus(key to value)))

    private fun primitive(key: String): JsonPrimitive? = raw[key] as? JsonPrimitive

    private fun string(key: String): String? = primitive(key)?.contentOrNull

    private fun int(key: String): Int? = primitive(key)?.intOrNull

    private fun double(key: String): Double? = primitive(key)?.doubleOrNull

    private fun boolean(key: String): Boolean? = primitive(key)?.booleanOrNull

    companion object {
        val EmptySettings = JsonObject(emptyMap())
        val Empty = UserSettings()
    }
}

internal object UserSettingsSerializer : KSerializer<UserSettings> {
    private val delegate = JsonObject.serializer()

    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): UserSettings = UserSettings(delegate.deserialize(decoder))

    override fun serialize(encoder: Encoder, value: UserSettings) = delegate.serialize(encoder, value.raw)
}

@Serializable
data class SettingsPayload(
    val value: UserSettings = UserSettings.Empty,
    /** Client-set; the server keeps whichever write is newest. */
    val updatedAt: Long,
)
