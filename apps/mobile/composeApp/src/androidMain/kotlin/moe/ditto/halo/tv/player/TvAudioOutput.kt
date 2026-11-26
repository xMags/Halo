package moe.ditto.halo.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ditto.halo.AndroidMpvPlayerHost
import moe.ditto.halo.player.AudioPassthroughState
import moe.ditto.halo.player.PassthroughForLoad
import moe.ditto.halo.storage.KeyValueStore

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

/** `Settings.Global` `encoded_surround_output`'s NEVER: the viewer told the system never to send compressed sound. */
internal const val EncodedSurroundNever = 1

/**
 * The formats that count as taken as they are: those the output [listed]
 * that also [opens] an output for. Nothing when the system's own surround
 * switch ([encodedSurroundOutput], null when the device has none) says never.
 */
internal fun confirmedFormats(
    listed: Set<PassthroughFormat>,
    encodedSurroundOutput: Int?,
    opens: (PassthroughFormat) -> Boolean,
): Set<PassthroughFormat> {
    if (encodedSurroundOutput == EncodedSurroundNever) return emptySet()
    return listed.filterTo(mutableSetOf()) { opens(it) }
}

/** The formats in [spdifCodecs] that would carry a [codec] track; empty when it is decoded here. */
internal fun formatsCarrying(spdifCodecs: String?, codec: String?): Set<PassthroughFormat> {
    if (spdifCodecs == null || !passesThrough(spdifCodecs, codec)) return emptySet()
    val names = spdifCodecs.split(',').toSet()
    val carriers = when (codec?.lowercase()) {
        "ac3" -> setOf(PassthroughFormat.Ac3)
        "eac3" -> setOf(PassthroughFormat.Eac3)
        // Either goes, as in [passesThrough].
        "dts" -> setOf(PassthroughFormat.Dts, PassthroughFormat.DtsHd)
        "truehd" -> setOf(PassthroughFormat.TrueHd)
        else -> emptySet()
    }
    return carriers.filterTo(mutableSetOf()) { it.mpvName in names }
}

/** How mpv names its pass-through decoders: `spdif_` and the codec. */
private const val PassthroughDecoderPrefix = "spdif_"

/**
 * True while the selected audio track goes to the receiver untouched, so it
 * cannot be delayed or sped up here. The [decoder] mpv chose decides once it
 * is known: mpv falls back to decoding here by itself when a pass-through
 * output will not open, whatever was asked. Until then the answer is what
 * was asked: [spdifCodecs] for a track of [codec].
 */
internal fun passthroughActive(decoder: String?, spdifCodecs: String?, codec: String?): Boolean {
    if (decoder != null) return decoder.startsWith(PassthroughDecoderPrefix)
    return passesThrough(spdifCodecs, codec)
}

/**
 * The TV's sound output: which compressed formats it takes as they are, and
 * telling the engine to send those through. Asked again for every title,
 * because a receiver can be switched on or off between them; only formats
 * seen failing on this same output are remembered ([PassthroughFailures], in
 * [store]).
 *
 * The player host asks it for every file before loading it
 * ([PassthroughForLoad]); the activity installs it there.
 */
internal class TvAudioOutput(
    context: Context,
    private val host: AndroidMpvPlayerHost,
    private val store: KeyValueStore,
) : PassthroughForLoad {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val contentResolver = context.contentResolver
    private val failures = PassthroughFailures(store)
    private val watchdog = TvPassthroughWatchdog(host, failures::record)

    /** Dolby surround through the second engine where mpv's pass-through failed on this output. */
    val secondEngine = TvSecondEngineSound(context, this, SecondEngineSoundMemory(store))

    /** What the file last loaded plays with; see [AudioPassthroughState]. */
    val passthroughState: StateFlow<AudioPassthroughState?> get() = host.audioPassthrough

    /**
     * Blocking: before Android 10 it opens a test output per listed format.
     * Never on the main thread; [spdifCodecsForNextFile] runs it on the IO
     * dispatcher.
     */
    fun supportedFormats(): Set<PassthroughFormat> {
        val surround = encodedSurroundOutput()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return confirmedFormats(directPlaybackFormats(), surround) { true }
        }
        val listed = hdmiFormats()
        val confirmed = confirmedFormats(listed, surround, ::opensOutput)
        if (surround == EncodedSurroundNever && listed.isNotEmpty()) {
            Log.i(LogTag, "system surround output is set to never: decoded here")
        }
        (listed - confirmed).takeIf { surround != EncodedSurroundNever && it.isNotEmpty() }?.let { refused ->
            Log.i(LogTag, "output lists ${refused.map { it.mpvName }} but will not open them: decoded here")
        }
        return confirmed
    }

    /**
     * The viewer's surround setting as stored now, applied to what the output
     * takes now: asked for every file, since the setting or the receiver may
     * have changed since the last one. The host sets the answer just before
     * the file loads.
     */
    override suspend fun spdifCodecsForNextFile(): String? = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val codecs = codecsFor(TvPlaybackPreferences.surroundSoundIn(store))
        Log.i(LogTag, "next file: audio-spdif ${codecs ?: "empty"}, decided in ${SystemClock.elapsedRealtime() - started} ms")
        codecs
    }

    /** Blocking, like [supportedFormats]. Off asks nothing of the output. */
    private fun codecsFor(choice: SurroundSound): String? {
        if (choice == SurroundSound.Off) {
            Log.i(LogTag, "surround Off: decoded here (PCM)")
            return null
        }
        val supported = runCatching { supportedFormats() }.getOrDefault(emptySet())
        // Unknown (null) while the HDMI output is away, as in the middle of a
        // re-plug; the remembered failures then stay as they are.
        val output = runCatching { outputIdentity(hdmiDevices()) }.getOrNull()
        val usable = failures.usable(supported, output)
        (supported - usable).takeIf { it.isNotEmpty() }?.let { skipped ->
            Log.i(LogTag, "passthrough stays off for ${skipped.map { it.mpvName }}: it did not start earlier on this output")
        }
        val codecs = spdifCodecsFor(usable, choice)
        Log.i(
            LogTag,
            "surround $choice, output ${output ?: "not listed"} accepts ${supported.map { it.mpvName }}: ${codecs ?: "decoded here (PCM)"}",
        )
        return codecs
    }

    /**
     * Watches the start of the opened file's [codec] track sent on under
     * [spdifCodecs], and decodes it here if it never starts. See
     * [TvPassthroughWatchdog]; a fallback shows in [passthroughState].
     */
    suspend fun guardStart(spdifCodecs: String, codec: String?) = watchdog.watch(spdifCodecs, codec)

    /** The viewer's Surround setting is Auto and the system's own switch is not set to never. */
    fun surroundAllowed(): Boolean =
        TvPlaybackPreferences.surroundSoundIn(store) == SurroundSound.Auto && encodedSurroundOutput() != EncodedSurroundNever

    /** The sound output as [outputIdentity] names it now; null while no HDMI output is listed (as mid re-plug). */
    fun outputIdentityNow(): String? = runCatching { outputIdentity(hdmiDevices()) }.getOrNull()

    /** The formats mpv's pass-through was seen to fail for on the output in use. */
    fun mpvFailedFormats(output: String?): Set<PassthroughFormat> = failures.failedOn(output)

    /** The system's surround switch, where the device has one; null otherwise. */
    private fun encodedSurroundOutput(): Int? = runCatching {
        Settings.Global.getInt(contentResolver, EncodedSurroundOutputSetting, -1).takeIf { it >= 0 }
    }.getOrNull()

    /** The HDMI outputs by kind and name, for [outputIdentity]. */
    private fun hdmiDevices(): List<AudioOutputDevice> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { device ->
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_HDMI -> "hdmi"
                AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi-arc"
                else -> return@mapNotNull null
            }
            AudioOutputDevice(kind, device.productName?.toString().orEmpty())
        }

    /**
     * Whether an output for [format] can actually be opened: a test track is
     * built and released at once, never written to or played. Before Android
     * 10 the HDMI list alone is not proof; a box can list formats its own
     * sound processing takes while nothing downstream decodes them.
     */
    private fun opensOutput(format: PassthroughFormat): Boolean = ProbeChannelMasks.any { mask ->
        runCatching { opensTrack(format.encoding, mask) }.getOrDefault(false)
    }

    private fun opensTrack(encoding: Int, mask: Int): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(ProbeSampleRate, mask, encoding)
        if (minBuffer <= 0) return false
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(ProbeSampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            return track.state == AudioTrack.STATE_INITIALIZED
        } finally {
            track.release()
        }
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
        /** The test track's format: 5.1 first, then stereo, at the rate films use. */
        val ProbeChannelMasks = listOf(
            AudioFormat.CHANNEL_OUT_5POINT1,
            AudioFormat.CHANNEL_OUT_STEREO,
        )
        const val ProbeSampleRate = 48_000
        const val EncodedSurroundOutputSetting = "encoded_surround_output"
        const val LogTag = "HALO_TV"
    }
}
