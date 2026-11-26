package moe.ditto.halo.tv.player

import android.content.Context
import android.media.MediaCodecList
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ditto.halo.player.Media3Encodings
import moe.ditto.halo.player.Media3SoundPolicy

/**
 * Dolby surround through the second engine, on a TV output where mpv's own
 * pass-through was seen to fail ([TvAudioOutput.mpvFailedFormats]). It tells
 * the second engine what it may send on untouched for each file
 * ([Media3SoundPolicy]), decides whether a file playing in mpv goes there for
 * its sound ([handoverFormat]), and keeps the second engine's own results
 * ([SecondEngineSoundMemory]). A device where mpv's pass-through works never
 * gets here: it has no mpv failure to start from.
 *
 * [passthroughEncodings] runs on the main thread as a file loads (all its
 * reads are small: the system's last HDMI report, two settings, the memory);
 * [handoverFormat] runs off it.
 */
@OptIn(UnstableApi::class)
internal class TvSecondEngineSound(
    context: Context,
    private val output: TvAudioOutput,
    private val memory: SecondEngineSoundMemory,
) : Media3SoundPolicy {
    private val appContext = context.applicationContext

    // The Dolby formats this device can also decode, so a failed pass-through
    // still has sound. Read once.
    private val decodable: Set<PassthroughFormat> by lazy {
        val types = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.map { type -> type.lowercase() } }
                .toSet()
        }.getOrDefault(emptySet())
        SecondEngineSoundFormats.filterTo(mutableSetOf()) { format -> mimeOf(format) in types }
    }

    /** Reads this device's sound decoders now; blocking, so off the main thread, before a file loads. */
    fun prefetchDecoders() {
        decodable
    }

    // Files the second engine could not play when taken for their sound; for
    // this run of the app only, since the cause may have been the file.
    private val keptWithMpv = HashSet<String>()

    /** The source URLs that stay with mpv for their sound in this run of the app. */
    val filesMpvKeeps: Set<String> get() = keptWithMpv

    fun keepWithMpv(url: String) {
        keptWithMpv += url
    }

    /** What must hold for [format] on [identity], read now. */
    private fun factsFor(format: PassthroughFormat, identity: String?, takes: Set<PassthroughFormat>): SecondEngineSoundFacts =
        SecondEngineSoundFacts(
            surroundAllowed = output.surroundAllowed(),
            mpvFailed = format in output.mpvFailedFormats(identity),
            outputTakes = format in takes,
            decoderFallback = format in decodable,
            result = memory.resultFor(format, identity),
        )

    /** Media3's own reading of the output, as its sink will see it. */
    private fun outputTakes(): Set<PassthroughFormat> {
        val capabilities = runCatching {
            AudioCapabilities.getCapabilities(appContext, AudioAttributes.DEFAULT, null, emptyList())
        }.getOrNull() ?: return emptySet()
        return SecondEngineSoundFormats.filterTo(mutableSetOf()) { capabilities.supportsEncoding(encodingOf(it)) }
    }

    override fun passthroughEncodings(): Set<Int> {
        val identity = output.outputIdentityNow()
        val takes = outputTakes()
        val allowed = SecondEngineSoundFormats.filter { secondEngineMayPassThrough(factsFor(it, identity, takes)) }
        if (allowed.isNotEmpty()) {
            Log.i(LogTag, "second engine: sound may pass through as ${allowed.map { it.mpvName }} on ${identity ?: "an output not listed"}; the output takes ${takes.map { it.mpvName }}")
        }
        return allowed.mapTo(mutableSetOf()) { encodingOf(it) }
    }

    override fun onPassthroughFailed(encoding: Int) {
        val format = formatOf(encoding) ?: return
        memory.record(format, SoundResult.Fails, output.outputIdentityNow())
        Log.i(LogTag, "second engine: pass-through of ${format.mpvName} failed; such files stay with mpv on this output for ${PassthroughFailureExpiryMs / DayMs} days or until it changes")
    }

    /** Sound was seen to play sent on untouched as [encoding]. */
    fun passthroughWorked(encoding: Int) {
        val format = formatOf(encoding) ?: return
        if (memory.resultFor(format, output.outputIdentityNow()) == SoundResult.Works) return
        memory.record(format, SoundResult.Works, output.outputIdentityNow())
        Log.i(LogTag, "second engine: pass-through of ${format.mpvName} proven on this output; such files start there")
    }

    /**
     * The format a file playing in mpv with a [codec] sound track is handed
     * to the second engine for, or null to stay in mpv ([soundHandover]).
     * The output is read once it is there: an HDMI re-plug, which a display
     * mode switch causes on some devices just as a film starts, takes the
     * output away for a few seconds, and a reading then would say it takes
     * nothing.
     */
    suspend fun handoverFormat(codec: String?, pictureFromHardware: Boolean, subtitleDrawnByApp: Boolean): PassthroughFormat? {
        val format = dolbyFormatOf(codec) ?: return null
        return withContext(Dispatchers.IO) {
            var identity = output.outputIdentityNow()
            var waited = 0L
            while (identity == null && waited < OutputSettleMs) {
                delay(OutputPollMs)
                waited += OutputPollMs
                identity = output.outputIdentityNow()
            }
            // The system's HDMI report follows the device's return by a moment.
            if (waited > 0) delay(OutputPollMs)
            val takes = outputTakes()
            val facts = factsFor(format, identity, takes)
            val handOver = soundHandover(format, facts, pictureFromHardware, subtitleDrawnByApp)
            if (facts.mpvFailed && facts.surroundAllowed) {
                // Only where this path applies at all, so ordinary files log nothing.
                Log.i(
                    LogTag,
                    "second engine: ${format.mpvName} track, output ${identity ?: "not listed"} takes ${takes.map { it.mpvName }}, " +
                        "decodable ${format in decodable}, earlier ${facts.result?.name?.lowercase() ?: "untried"}, " +
                        "picture from hardware $pictureFromHardware, subtitle drawn by the app $subtitleDrawnByApp: " +
                        if (handOver) "taking the file for its sound" else "staying with mpv",
                )
            }
            format.takeIf { handOver }
        }
    }

    private fun encodingOf(format: PassthroughFormat): Int = when (format) {
        PassthroughFormat.Ac3 -> Media3Encodings.Ac3
        else -> Media3Encodings.Eac3
    }

    private fun formatOf(encoding: Int): PassthroughFormat? = when (encoding) {
        Media3Encodings.Ac3 -> PassthroughFormat.Ac3
        Media3Encodings.Eac3, Media3Encodings.Eac3Joc -> PassthroughFormat.Eac3
        else -> null
    }

    private fun mimeOf(format: PassthroughFormat): String = when (format) {
        PassthroughFormat.Ac3 -> "audio/ac3"
        else -> "audio/eac3"
    }

    private companion object {
        const val LogTag = "HALO_TV"
        const val DayMs = 24L * 60 * 60 * 1000

        /** About the re-plug a display mode switch causes on a TV box (measured: 3.2 s), and a little over. */
        const val OutputSettleMs = 4_000L
        const val OutputPollMs = 250L
    }
}
