package moe.ditto.halo.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.auth.EpochClock

/** Where a remembered subtitle selection came from. */
@Serializable
enum class SubtitleChoiceKind {
    @SerialName("off")
    Off,

    @SerialName("embedded")
    Embedded,

    @SerialName("external")
    External,

    @SerialName("downloaded")
    Downloaded,
}

/**
 * A subtitle selection worth restoring later. Enough is recorded to find the
 * same track again exactly, with the language as the fallback when the exact
 * one is gone — a different release of the next episode will not have the same
 * subtitle id.
 */
@Serializable
data class SubtitleChoice(
    val kind: SubtitleChoiceKind = SubtitleChoiceKind.Off,
    /** Addon language code for external tracks, parsed label for embedded ones. */
    val lang: String? = null,
    /** Embedded: the exact track name, for same-file restore before falling back to language. */
    val trackName: String? = null,
    /** External: the addon's subtitle id, to re-find the exact result for this video. */
    val subId: String? = null,
    /** External: the stored file name, so the same video restores offline from disk. */
    val fileName: String? = null,
    /** Stamped by the store on write; supplying it has no effect. */
    val updatedAt: Long = 0,
)

/**
 * The last subtitle a viewer explicitly chose, per video and per library item.
 *
 * Only deliberate choices are recorded. An automatically applied
 * preferred-language default never writes here, or the preference would
 * reinforce itself into looking like a decision the viewer made.
 *
 * Device-local by design: it describes files on this device as much as
 * content, so syncing it would restore selections pointing at subtitles the
 * other device never downloaded.
 */
class SubtitleChoiceStore(
    private val store: KeyValueStore,
    private val clock: EpochClock,
) {
    /**
     * The exact choice for this video, otherwise the one carried over from the
     * item — which is what makes the next episode of a series keep the
     * subtitle language chosen for the last one.
     */
    fun choiceFor(videoId: String, itemId: String): SubtitleChoice? {
        val all = read()
        return all[videoKey(videoId)] ?: all[itemKey(itemId)]
    }

    fun remember(videoId: String, itemId: String, choice: SubtitleChoice): SubtitleChoice {
        val stamped = choice.copy(updatedAt = clock.nowMs())
        val all = read().toMutableMap()
        all[videoKey(videoId)] = stamped
        all[itemKey(itemId)] = stamped
        write(all.capped())
        return stamped
    }

    /** Oldest entries fall off first once the cap is reached. */
    private fun Map<String, SubtitleChoice>.capped(): Map<String, SubtitleChoice> {
        if (size <= MaxEntries) return this
        return entries
            .sortedByDescending { it.value.updatedAt }
            .take(MaxEntries)
            .associate { it.key to it.value }
    }

    private fun read(): Map<String, SubtitleChoice> {
        val raw = store.read(StorageKeys.SubtitleChoices) ?: return emptyMap()
        val root = try {
            HaloJson.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        } catch (_: SerializationException) {
            return emptyMap()
        }
        // Decoded per entry: one unreadable record, from an older or newer
        // build, must not discard every other remembered choice.
        return root.mapNotNull { (key, element) ->
            try {
                key to HaloJson.decodeFromJsonElement(SubtitleChoice.serializer(), element)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()
    }

    private fun write(all: Map<String, SubtitleChoice>) {
        store.write(StorageKeys.SubtitleChoices, HaloJson.encodeToString(Serializer, all))
    }

    private companion object {
        val Serializer = MapSerializer(String.serializer(), SubtitleChoice.serializer())

        const val MaxEntries = 300

        fun videoKey(videoId: String) = "video:$videoId"

        fun itemKey(itemId: String) = "item:$itemId"
    }
}
