package moe.ditto.halo.player

/**
 * Decides mpv's `audio-spdif` list for the file about to load, or null to
 * decode everything here. Asked by the player host for every load, before
 * the load is queued, so the list is in place before the file opens. Only a
 * device that sends sound on to a receiver has one (the TV).
 */
internal fun interface PassthroughForLoad {
    suspend fun spdifCodecsForNextFile(): String?
}

/**
 * The passthrough in force for [item], the last file loaded: the list it was
 * loaded with (or emptied to when its start failed), and the audio decoder
 * mpv chose for it, null until mpv names one.
 */
internal data class AudioPassthroughState(
    val item: MediaItem,
    val spdifCodecs: String?,
    val decoder: String? = null,
)
