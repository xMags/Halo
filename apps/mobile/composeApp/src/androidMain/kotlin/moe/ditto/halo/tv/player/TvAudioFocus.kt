package moe.ditto.halo.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** What the player does when another app takes or gives back the sound. */
internal enum class TvFocusResponse { Pause, Resume, Nothing }

/**
 * The audio focus rule, apart from Android so it can be tested: a lasting loss
 * pauses for good, a short one (a notification, an assistant) pauses and plays
 * on once it ends, and a request to lower the volume is left to the system,
 * which lowers it by itself.
 */
internal class TvAudioFocusPolicy {
    private var resumeOnGain = false

    fun onChange(change: Int, playing: Boolean): TvFocusResponse = when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> {
            resumeOnGain = false
            if (playing) TvFocusResponse.Pause else TvFocusResponse.Nothing
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            // Only what this loss paused comes back; a film the viewer had
            // paused stays paused.
            resumeOnGain = playing || resumeOnGain
            if (playing) TvFocusResponse.Pause else TvFocusResponse.Nothing
        }
        AudioManager.AUDIOFOCUS_GAIN -> {
            val resume = resumeOnGain
            resumeOnGain = false
            if (resume) TvFocusResponse.Resume else TvFocusResponse.Nothing
        }
        else -> TvFocusResponse.Nothing
    }

    /** The viewer chose to play or pause, which overrides a pending resume. */
    fun onViewerChoice() {
        resumeOnGain = false
    }
}

/**
 * Holds audio focus for the TV player. [onChange] runs on the main thread with
 * the system's AUDIOFOCUS_* value.
 */
internal class TvAudioFocus(context: Context, private val onChange: (Int) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(
            { change ->
                // A lasting loss ends the claim; the next play asks again.
                if (change == AudioManager.AUDIOFOCUS_LOSS) held = false
                onChange(change)
            },
            Handler(Looper.getMainLooper()),
        )
        .build()
    var held = false
        private set

    /** True when the sound is ours to play. */
    fun request(): Boolean {
        if (held) return true
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (!held) return
        audioManager.abandonAudioFocusRequest(request)
        held = false
    }
}
