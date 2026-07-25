package moe.ditto.halo.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Decoding policy for every payload crossing the API boundary.
 *
 * The settings applied here are not stylistic — each one covers a shape that
 * real addons emit and that a default-configured decoder would reject:
 *
 * - `ignoreUnknownKeys`: Cinemeta's manifest carries `addonCatalogs` and a
 *   per-catalog `genres`; its catalog responses carry `hasMore` and cache
 *   directives; its metas carry two dozen fields the protocol never documents.
 * - `coerceInputValues`: addons send explicit `null` for absent collections
 *   (Cinemeta emits `"director": null`, Torrentio `"idPrefixes": null`) where
 *   the protocol implies omission.
 * - `isLenient`: numeric values arrive where strings are declared — ratings and
 *   release years are the common offenders, since JavaScript clients never had
 *   to care which one an addon chose.
 * - `explicitNulls = false`: absent means absent on the way out; the API
 *   distinguishes a missing optional from an explicit null.
 *
 * Together these make decoding tolerant in the same way an untyped JavaScript
 * client is implicitly tolerant. A strict decoder would be a behavior
 * regression against the addon ecosystem, not a safety improvement.
 */
val HaloJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

/**
 * `resources` entries are either a bare resource name or an object that
 * additionally constrains types/id prefixes — both forms occur in the wild
 * (Cinemeta and OpenSubtitles use strings, Torrentio objects), so the two
 * collapse into one type here and the distinction is preserved only for
 * round-tripping.
 */
internal object ManifestResourceSerializer : KSerializer<ManifestResource> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("moe.ditto.halo.api.ManifestResource")

    override fun deserialize(decoder: Decoder): ManifestResource {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("ManifestResource can only be read from JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> ManifestResource(name = element.content)
            is JsonObject -> input.json.decodeFromJsonElement<DetailedResource>(element).let {
                ManifestResource(name = it.name, types = it.types, idPrefixes = it.idPrefixes)
            }
            else -> throw SerializationException("Manifest resource must be a string or an object")
        }
    }

    override fun serialize(encoder: Encoder, value: ManifestResource) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("ManifestResource can only be written as JSON")
        val shorthand = value.types == null && value.idPrefixes == null
        output.encodeJsonElement(
            if (shorthand) {
                JsonPrimitive(value.name)
            } else {
                output.json.encodeToJsonElement(
                    DetailedResource(value.name, value.types, value.idPrefixes),
                )
            },
        )
    }
}
