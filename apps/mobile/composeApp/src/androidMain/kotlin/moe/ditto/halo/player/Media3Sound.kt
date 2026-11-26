package moe.ditto.halo.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Which compressed sound the second engine may send on untouched for the file
 * it is about to load, and what it learned: the TV decides, per device and
 * output, from what was seen there. Without one, every track is decoded.
 */
internal interface Media3SoundPolicy {
    /** Media3's `C.ENCODING_*` values; empty decodes everything. Asked on the main thread as a file loads. */
    fun passthroughEncodings(): Set<Int>

    /** Sending [encoding] on failed at the output itself (it would not open or take data). */
    fun onPassthroughFailed(encoding: Int)
}

/** The second engine sends [item]'s sound on untouched as [encoding]; [starts] counts the outputs opened for it. */
internal data class Media3SoundState(val item: MediaItem, val encoding: Int, val starts: Int)

/** Media3's `C.ENCODING_AC3`, `C.ENCODING_E_AC3` and `C.ENCODING_E_AC3_JOC`. */
internal object Media3Encodings {
    const val Ac3 = 5
    const val Eac3 = 6
    const val Eac3Joc = 18

    /** True for the compressed encodings this app ever lets through. */
    fun isPassthrough(encoding: Int): Boolean = encoding == Ac3 || encoding == Eac3 || encoding == Eac3Joc

    /**
     * Whether [encoding] may go out under [allowed]. Media3 sends an Atmos
     * stream (`E_AC3_JOC`) as plain E-AC-3 where the output takes only that,
     * so allowing E-AC-3 allows it too.
     */
    fun allows(allowed: Set<Int>, encoding: Int): Boolean =
        encoding in allowed || (encoding == Eac3Joc && Eac3 in allowed)
}

/**
 * The sink in front of Media3's own: compressed sound it may not send on
 * ([allowed]) is reported unsupported, so the renderer decodes that track
 * instead. Changing [allowed] takes effect when the audio renderer is set up
 * again. The output's own changes (an HDMI re-plug) reach Media3 unchanged; a
 * re-plug opens the output again, which the host sees and reports.
 */
@OptIn(UnstableApi::class)
internal class PassthroughGate(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile var allowed: Set<Int> = emptySet()

    private fun refused(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        val encoding = MimeTypes.getEncoding(mime, format.codecs)
        return !Media3Encodings.allows(allowed, encoding)
    }

    override fun supportsFormat(format: Format): Boolean = !refused(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (refused(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}
