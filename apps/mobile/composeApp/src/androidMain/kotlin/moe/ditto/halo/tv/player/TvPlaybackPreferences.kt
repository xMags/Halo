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

    var surroundSound: SurroundSound by mutableStateOf(surroundSoundIn(store))
        private set

    fun updateMatchFrameRate(enabled: Boolean) {
        matchFrameRate = enabled
        store.write(MatchFrameRateKey, enabled.toString())
    }

    fun updateSurroundSound(choice: SurroundSound) {
        surroundSound = choice
        store.write(SurroundSoundKey, choice.name)
    }

    /** Whether a video opens where it was left; off, everything starts from the beginning. */
    var resumePlayback: Boolean by mutableStateOf(store.read(ResumePlaybackKey) != "false")
        private set

    fun updateResumePlayback(enabled: Boolean) {
        resumePlayback = enabled
        store.write(ResumePlaybackKey, enabled.toString())
    }

    /**
     * Whether the TV's video chip decodes. Read when the app starts, because
     * the player is built with it then: a change applies from the next start.
     */
    var hardwareDecoding: Boolean by mutableStateOf(hardwareDecodingIn(store))
        private set

    /** The value the running player was built with, to say when a restart is due. */
    val hardwareDecodingAtStart: Boolean = hardwareDecoding

    fun updateHardwareDecoding(enabled: Boolean) {
        hardwareDecoding = enabled
        store.write(HardwareDecodingKey, enabled.toString())
    }

    companion object {
        private const val MatchFrameRateKey = "halo.tv.matchFrameRate"
        private const val SurroundSoundKey = "halo.tv.surroundSound"
        private const val ResumePlaybackKey = "halo.tv.resumePlayback"
        private const val HardwareDecodingKey = "halo.tv.hardwareDecoding"

        /** For the activity, which builds the player before any screen exists. */
        fun hardwareDecodingIn(store: KeyValueStore): Boolean = store.read(HardwareDecodingKey) != "false"

        /** For the sound output, which decides each file's passthrough as the player loads it. */
        fun surroundSoundIn(store: KeyValueStore): SurroundSound =
            SurroundSound.entries.firstOrNull { it.name == store.read(SurroundSoundKey) } ?: SurroundSound.Auto
    }
}
