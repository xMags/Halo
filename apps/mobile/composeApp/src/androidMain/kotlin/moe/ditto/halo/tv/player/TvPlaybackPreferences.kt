package moe.ditto.halo.tv.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.ditto.halo.storage.KeyValueStore

/** What the surround setting allows. */
internal enum class SurroundSound { Auto, Off }

/**
 * Playback settings that belong to this TV and its sound system, not to the
 * account: kept on the device and never synced, so a phone signed in to the
 * same account is not affected.
 */
internal class TvPlaybackPreferences(private val store: KeyValueStore) {
    var matchFrameRate: Boolean by mutableStateOf(store.read(MatchFrameRateKey) != "false")
        private set

    var surroundSound: SurroundSound by mutableStateOf(
        SurroundSound.entries.firstOrNull { it.name == store.read(SurroundSoundKey) } ?: SurroundSound.Auto,
    )
        private set

    fun updateMatchFrameRate(enabled: Boolean) {
        matchFrameRate = enabled
        store.write(MatchFrameRateKey, enabled.toString())
    }

    fun updateSurroundSound(choice: SurroundSound) {
        surroundSound = choice
        store.write(SurroundSoundKey, choice.name)
    }

    private companion object {
        const val MatchFrameRateKey = "halo.tv.matchFrameRate"
        const val SurroundSoundKey = "halo.tv.surroundSound"
    }
}
