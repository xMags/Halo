package moe.ditto.halo.storage

import moe.ditto.halo.api.VideoFitMode

/**
 * The native player's framing choice for this device.
 *
 * Screen shape and preferred crop are device concerns. Syncing the choice
 * makes a phone overwrite a tablet, so the server DTO remains compatible but
 * this player neither reads nor writes its `videoFitMode` field.
 */
internal class VideoFitModeStore(private val store: KeyValueStore) {
    fun current(): VideoFitMode =
        VideoFitMode.fromWire(store.read(StorageKeys.VideoFitMode)) ?: VideoFitMode.Contain

    fun update(mode: VideoFitMode) {
        store.write(StorageKeys.VideoFitMode, mode.wire)
    }
}
