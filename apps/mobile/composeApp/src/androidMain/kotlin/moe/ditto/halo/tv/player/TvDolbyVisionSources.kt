package moe.ditto.halo.tv.player

import android.media.MediaCodecList
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import moe.ditto.halo.browse.SourcesResolve
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.storage.KeyValueStore

/**
 * Dolby Vision on this TV: what it learned, kept only in this device's own
 * store (never synced, never sent) under one key, by [sourceFingerprint]:
 * the sources mpv showed wrongly, whether the second engine shows them, and
 * the sources that failed in both. A device that never saw such a picture
 * wrongly keeps nothing, lists sources exactly as before and plays every
 * file in mpv.
 *
 * It answers the engine router ([EngineChooser]) and the sources page, and
 * is told by the player screen what it saw. Main thread only, like those;
 * the stored copy is small and read once, on first use, and this device's
 * Dolby Vision decoders are read from its codec list once, on first need.
 */
internal class TvDolbyVisionSources(
    private val store: KeyValueStore,
    /** This app install and its decoding setting; a change forgets what was learned. */
    private val setup: String,
    /** Plays the current file on in mpv without the processing it was found not to get. */
    private val showAsIs: suspend () -> Unit,
    /** Whether the video chip decodes; without it the second engine has no decoder to use. */
    private val hardwareDecoding: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : EngineChooser {
    private var record: DolbyVisionRecord? = null
    private var loaded = false

    /** Moves whenever something is learned, so a list on screen is worked out again. */
    var revision by mutableIntStateOf(0)
        private set

    private val decoders: List<DolbyVisionDecoder> by lazy { readDolbyVisionDecoders() }

    /** Reads this device's Dolby Vision decoders now; blocking, so off the main thread, before any screen asks. */
    fun prefetchDecoders() {
        decoders
    }

    private fun current(): DolbyVisionRecord? {
        if (!loaded) {
            loaded = true
            record = decodeDolbyVisionRecord(runCatching { store.read(StoreKey) }.getOrNull())
        }
        val live = liveDolbyVisionRecord(record, setup, now())
        if (live == null && record != null) {
            record = null
            runCatching { store.delete(StoreKey) }
            Log.i(LogTag, "Dolby Vision: what this TV learned no longer holds (setup changed or too old); trying such sources again")
        }
        return live
    }

    private fun write(next: DolbyVisionRecord) {
        record = next
        runCatching { store.write(StoreKey, encodeDolbyVisionRecord(next)) }
        revision += 1
    }

    /** True once this TV has seen mpv show such a picture wrongly, under its current setup. */
    val learned: Boolean get() = current() != null

    /** True when the second engine has played such a file on this device. */
    val secondEngineWorks: Boolean get() = current()?.secondEngine == SecondEngineState.Works

    private fun usable(profile: Int): Boolean {
        if (!hardwareDecoding) return false
        val state = current()?.secondEngine ?: SecondEngineState.Untried
        return secondEngineUsable(state, decodesDolbyVisionProfile(decoders, profile))
    }

    /** Whether the second engine may take [url], a file of [profile]: usable here, and it has not failed on this one. */
    fun secondEngineFor(url: String, profile: Int): Boolean {
        if (!usable(profile)) return false
        return sourceFingerprint(url) !in current()?.failed.orEmpty()
    }

    override fun engineFor(item: MediaItem): PlaybackEngine =
        engineForSource(sourceFingerprint(item.url), current(), ::usable)

    /** The rate a remembered source played at, so its display mode is set before its first picture. */
    override fun frameRateFor(item: MediaItem): Double? = current()?.frameRates?.get(sourceFingerprint(item.url))

    /** Records [url] as a file of [profile] that mpv shows wrongly here, played at [frameRate] when known. */
    fun remember(url: String, profile: Int, frameRate: Double? = null) {
        val fingerprint = sourceFingerprint(url)
        val known = current()
        val wrong = LinkedHashMap(known?.wrong.orEmpty())
        wrong.remove(fingerprint)
        wrong[fingerprint] = profile
        while (wrong.size > MaxKept) wrong.remove(wrong.keys.first())
        val rates = LinkedHashMap(known?.frameRates.orEmpty().filterKeys { it in wrong })
        frameRate?.takeIf { it.isFinite() && it > 0 }?.let { rates[fingerprint] = it }
        write(
            DolbyVisionRecord(
                setup = setup,
                learnedAt = now(),
                wrong = wrong,
                secondEngine = known?.secondEngine ?: SecondEngineState.Untried,
                failed = known?.failed.orEmpty().filterTo(LinkedHashSet()) { it in wrong },
                frameRates = rates,
            ),
        )
        Log.i(LogTag, "remembered this source as Dolby Vision profile $profile, shown wrongly by mpv on this TV (${wrong.size} known)")
    }

    /** The second engine played such a file with the device's own Dolby Vision decoder. */
    fun secondEngineWorked() {
        val known = current() ?: return
        if (known.secondEngine == SecondEngineState.Works) return
        write(known.copy(secondEngine = SecondEngineState.Works, learnedAt = now()))
        Log.i(LogTag, "second engine: shows Dolby Vision on this TV; such files start there from now on")
    }

    /**
     * The second engine could not play [url]. That file is left to mpv from
     * now on; and if it had never worked here, the second engine is not
     * tried again under this setup.
     */
    fun secondEngineFailed(url: String) {
        val known = current() ?: return
        val fingerprint = sourceFingerprint(url)
        val state = if (known.secondEngine == SecondEngineState.Untried) SecondEngineState.Fails else known.secondEngine
        write(known.copy(secondEngine = state, failed = known.failed + fingerprint, learnedAt = now()))
        Log.i(LogTag, "second engine: failed for this source; ${if (state == SecondEngineState.Fails) "not tried again on this TV" else "this source stays with mpv"}")
    }

    /** [tvPlayableSources] with what this TV has learned. */
    fun adjust(resolve: SourcesResolve): TvSourcesAdjustment {
        val known = current()
        return tvPlayableSources(
            resolve = resolve,
            learned = known != null,
            // The page cannot know a file's profile; the one such files have been is 5.
            secondEngineUsable = known != null && usable(ListedProfile),
            knownWrong = { url -> known?.wrong?.containsKey(sourceFingerprint(url)) == true },
            failedEverywhere = { url -> sourceFingerprint(url) in known?.failed.orEmpty() },
        )
    }

    suspend fun playOnAsIs() = showAsIs()

    private fun readDolbyVisionDecoders(): List<DolbyVisionDecoder> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .mapNotNull { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(DolbyVisionMime, ignoreCase = true) } ?: return@mapNotNull null
                val profiles = runCatching { info.getCapabilitiesForType(type).profileLevels.mapTo(HashSet()) { it.profile } }
                    .getOrDefault(emptySet())
                DolbyVisionDecoder(info.name, profiles)
            }
    }.getOrDefault(emptyList()).also { found ->
        Log.i(LogTag, "Dolby Vision decoders on this TV: ${found.joinToString { "${it.name} (${it.profileFlags.size} profiles)" }.ifEmpty { "none" }}")
    }

    private companion object {
        const val StoreKey = "halo.tv.dolbyVisionSources.v1"
        const val MaxKept = 500
        const val ListedProfile = 5
        const val DolbyVisionMime = "video/dolby-vision"
        const val LogTag = "HALO_TV"
    }
}
