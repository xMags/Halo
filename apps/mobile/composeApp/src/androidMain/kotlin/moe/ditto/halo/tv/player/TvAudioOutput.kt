package moe.ditto.halo.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import moe.ditto.halo.AndroidMpvPlayerHost

/** Compressed surround formats a receiver or soundbar can decode itself. */
internal enum class PassthroughFormat(
    /** The name in mpv's `audio-spdif` list. */
    val mpvName: String,
    /** The matching AudioFormat.ENCODING_* value. */
    val encoding: Int,
) {
    Ac3("ac3", AudioFormat.ENCODING_AC3),
    Eac3("eac3", AudioFormat.ENCODING_E_AC3),
    Dts("dts", AudioFormat.ENCODING_DTS),
    DtsHd("dts-hd", AudioFormat.ENCODING_DTS_HD),
    TrueHd("truehd", AudioFormat.ENCODING_DOLBY_TRUEHD),
}

/** mpv's `audio-spdif` value for what the output accepts and what the viewer allows; null decodes everything here. */
internal fun spdifCodecsFor(supported: Set<PassthroughFormat>, choice: SurroundSound): String? {
    if (choice == SurroundSound.Off) return null
    return PassthroughFormat.entries.filter { it in supported }.takeIf { it.isNotEmpty() }?.joinToString(",") { it.mpvName }
}

/**
 * True when an audio track of [codec] (mpv's codec name) goes to the receiver
 * untouched under [spdifCodecs]. Such sound cannot be delayed or sped up here.
 */
internal fun passesThrough(spdifCodecs: String?, codec: String?): Boolean {
    val names = spdifCodecs?.split(',')?.toSet() ?: return false
    return when (codec?.lowercase()) {
        "ac3" -> "ac3" in names
        "eac3" -> "eac3" in names
        // mpv sends DTS-HD's core when only plain DTS is allowed.
        "dts" -> "dts" in names || "dts-hd" in names
        "truehd" -> "truehd" in names
        else -> false
    }
}

/**
 * The TV's sound output: which compressed formats it takes as they are, and
 * telling the engine to send those through. Asked again for every title,
 * because a receiver can be switched on or off between them.
 */
internal class TvAudioOutput(context: Context, private val host: AndroidMpvPlayerHost) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun supportedFormats(): Set<PassthroughFormat> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) directPlaybackFormats() else hdmiFormats()

    /** Applies [choice] for the next title and returns the `audio-spdif` value used. */
    suspend fun apply(choice: SurroundSound): String? {
        val supported = runCatching { supportedFormats() }.getOrDefault(emptySet())
        val codecs = spdifCodecsFor(supported, choice)
        Log.i(LogTag, "surround $choice, output accepts ${supported.map { it.mpvName }}: ${codecs ?: "decoded here (PCM)"}")
        host.setAudioPassthrough(codecs)
        return codecs
    }

    /** Android 10 and later answer per format, for the output in use. */
    private fun directPlaybackFormats(): Set<PassthroughFormat> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        return PassthroughFormat.entries.filterTo(mutableSetOf()) { format ->
            ChannelMasks.any { mask ->
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(format.encoding)
                    .setSampleRate(48_000)
                    .setChannelMask(mask)
                    .build()
                AudioTrack.isDirectPlaybackSupported(audioFormat, attributes)
            }
        }
    }

    /**
     * Before Android 10: what the HDMI output lists. An output that lists
     * nothing is treated as taking nothing, since sending a receiver a format
     * it cannot decode gives silence or noise.
     */
    private fun hdmiFormats(): Set<PassthroughFormat> {
        val encodings = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC }
            .flatMap { it.encodings.toList() }
            .toSet()
        return PassthroughFormat.entries.filterTo(mutableSetOf()) { it.encoding in encodings }
    }

    private companion object {
        val ChannelMasks = listOf(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_STEREO,
        )
        const val LogTag = "HALO_TV"
    }
}
