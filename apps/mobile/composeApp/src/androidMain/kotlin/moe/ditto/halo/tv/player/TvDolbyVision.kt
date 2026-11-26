package moe.ditto.halo.tv.player

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.resolveSummary
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourcesResolve
import moe.ditto.halo.player.VideoFormat
import moe.ditto.halo.player.VideoPathPolicy
import moe.ditto.halo.storage.KeyValueStore

/**
 * True when the picture playing is a Dolby Vision profile that is wrong
 * without processing ([VideoPathPolicy.needsDolbyVisionProcessing]) and the
 * engine was seen not to apply that processing on this device
 * ([VideoFormat.dolbyVisionProcessed] false). Nothing is assumed about the
 * device: where the engine applies it, the file plays as it is.
 */
internal fun dolbyVisionShownWrongly(format: VideoFormat): Boolean =
    VideoPathPolicy.needsDolbyVisionProcessing(format.dolbyVisionProfile) && format.dolbyVisionProcessed == false

// The same words the sources page reads Dolby Vision from, and a bare "DV"
// only in capitals, as there.
private val DolbyVisionWords = Regex("""\b(dolby[ .]?vision|dovi)\b""", RegexOption.IGNORE_CASE)
private val DolbyVisionTag = Regex("""\bDV\b""")

// Any other range named beside it: a release that states one carries it as
// its base layer (profile 7 or 8), which plays right without processing.
private val OtherRange = Regex("""\b(hdr10\+?|hdr|hlg|sdr)\b""", RegexOption.IGNORE_CASE)

/**
 * True when a source's text names Dolby Vision and no other range. Such a
 * file is often a profile that needs processing, but may be one with a base
 * layer whose release simply did not say so; it is only ever moved down on
 * the strength of its name, never hidden.
 */
internal fun statesDolbyVisionOnly(stream: Stream): Boolean {
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    val dolbyVision = DolbyVisionWords.containsMatchIn(text) || DolbyVisionTag.containsMatchIn(text)
    return dolbyVision && !OtherRange.containsMatchIn(text)
}

/** [resolve] as this TV lists it, and what was changed, for the log. */
internal data class TvSourcesAdjustment(val resolve: SourcesResolve, val leftOut: Int, val movedDown: Int)

/**
 * The sources page's [resolve] on a TV that has [learned] it shows such Dolby
 * Vision files wrongly (it opened one and the engine did not process it).
 * Without that, it is returned untouched. The shared rank is kept among the
 * rest; only these two move:
 *
 * - a source this TV opened and found to be such a file ([knownWrong], by its
 *   URL) is left out and counted with the formats this TV cannot play, unless
 *   nothing else is left: a wrongly coloured picture is better than none;
 * - a source whose name states only Dolby Vision ([statesDolbyVisionOnly])
 *   moves below every other one, so it is neither the pick nor an automatic
 *   switch while anything else is offered.
 *
 * Positions are numbered again from 0, so the first entry is still the pick.
 */
internal fun tvPlayableSources(
    resolve: SourcesResolve,
    learned: Boolean,
    knownWrong: (String) -> Boolean,
): TvSourcesAdjustment {
    if (!learned) return TvSourcesAdjustment(resolve, leftOut = 0, movedDown = 0)
    val rest = resolve.pool.filterNot { entry -> entry.stream.url?.let(knownWrong) == true }
    val kept = rest.ifEmpty { resolve.pool }
    val leftOut = resolve.pool.size - kept.size
    val (named, plain) = kept.partition { statesDolbyVisionOnly(it.stream) }
    // Moving them down means nothing when every source is one.
    val movedDown = if (plain.isEmpty()) 0 else named.size
    if (leftOut == 0 && movedDown == 0) return TvSourcesAdjustment(resolve, leftOut = 0, movedDown = 0)
    val ordered: List<SourceEntry> = (plain.sortedBy { it.rank } + named.sortedBy { it.rank })
        .mapIndexed { position, entry -> entry.copy(rank = position) }
    val answered = resolve.providers.count { it.answered }
    val failed = resolve.providers.size - answered
    return TvSourcesAdjustment(
        resolve = resolve.copy(
            pool = ordered,
            summary = resolveSummary(ordered.size, answered, failed),
            unplayableCount = resolve.unplayableCount + leftOut,
        ),
        leftOut = leftOut,
        movedDown = movedDown,
    )
}

/** A source's identity as it is kept: a digest of its URL, which carries account keys and is never stored. */
internal fun sourceFingerprint(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * How long what this TV learned holds after it last saw such a picture
 * wrong. The cause is the decoder path, which changes with an update of the
 * app or of the device, or with the decoding setting; a change of either is
 * caught at once ([DolbyVisionRecord.setup]), and this catches what is not
 * (a system update that keeps the app). The cost of forgetting too early is
 * one moment of wrong colours before the player moves on by itself.
 */
internal const val DolbyVisionMemoryMs = 30L * 24 * 60 * 60 * 1000

/**
 * What this TV learned: under which [setup] (this app install and its
 * decoding setting), when it last saw such a picture wrong, and the
 * fingerprints of the sources that showed it, oldest first.
 */
internal data class DolbyVisionRecord(val setup: String, val learnedAt: Long, val fingerprints: List<String>)

private const val StoredVersion = "v1"
private val Fingerprint = Regex("^[0-9a-f]{64}$")

internal fun encodeDolbyVisionRecord(record: DolbyVisionRecord): String =
    (listOf(StoredVersion, record.setup, record.learnedAt.toString()) + record.fingerprints).joinToString("\n")

/** Null for nothing stored or a record that is not well formed; it is then ignored as a whole. */
internal fun decodeDolbyVisionRecord(text: String?): DolbyVisionRecord? {
    val lines = text?.split('\n') ?: return null
    if (lines.size < 4 || lines[0] != StoredVersion) return null
    val setup = lines[1].takeIf { it.isNotBlank() } ?: return null
    val learnedAt = lines[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val fingerprints = lines.drop(3)
    if (fingerprints.any { !Fingerprint.matches(it) }) return null
    return DolbyVisionRecord(setup, learnedAt, fingerprints.distinct())
}

/**
 * [record] if it still holds for [setup] at [now]: same setup, and learned
 * within [DolbyVisionMemoryMs]. A time from the future (the clock moved
 * back) does not hold either, so it cannot last forever.
 */
internal fun liveDolbyVisionRecord(record: DolbyVisionRecord?, setup: String, now: Long): DolbyVisionRecord? {
    if (record == null || record.setup != setup) return null
    if (now < record.learnedAt || now - record.learnedAt >= DolbyVisionMemoryMs) return null
    return record
}

/**
 * Dolby Vision on this TV: the sources it opened and found shown wrongly,
 * kept only in this device's own store (never synced, never sent), by
 * [sourceFingerprint]; and the way to play such a file on when nothing else
 * can. A device that never saw such a picture wrong keeps nothing and lists
 * sources exactly as before.
 *
 * Main thread only, like the screens that use it; the stored copy is small
 * and read once, on first use.
 */
internal class TvDolbyVisionSources(
    private val store: KeyValueStore,
    /** This app install and its decoding setting; a change forgets what was learned. */
    private val setup: String,
    /** Plays the current file on without the processing it was found not to get. */
    private val showAsIs: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var record: DolbyVisionRecord? = null
    private var loaded = false

    /** Moves whenever something is learned, so a list on screen is worked out again. */
    var revision by mutableIntStateOf(0)
        private set

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

    /** True once this TV has seen such a picture wrong, under its current setup. */
    val learned: Boolean get() = current() != null

    fun isKnown(url: String): Boolean = current()?.fingerprints?.contains(sourceFingerprint(url)) == true

    /** Records [url] as shown wrongly here; a failed write only loses it across a restart. */
    fun remember(url: String, profile: Int) {
        val fingerprint = sourceFingerprint(url)
        val known = current()?.fingerprints.orEmpty()
        val fingerprints = (known - fingerprint + fingerprint).takeLast(MaxKept)
        val next = DolbyVisionRecord(setup, now(), fingerprints)
        record = next
        runCatching { store.write(StoreKey, encodeDolbyVisionRecord(next)) }
        revision += 1
        Log.i(LogTag, "remembered this source as Dolby Vision profile $profile, shown wrongly on this TV (${fingerprints.size} known)")
    }

    /** [tvPlayableSources] with what this TV has learned. */
    fun adjust(resolve: SourcesResolve): TvSourcesAdjustment = tvPlayableSources(resolve, learned, ::isKnown)

    suspend fun playOnAsIs() = showAsIs()

    private companion object {
        const val StoreKey = "halo.tv.dolbyVisionSources.v1"
        const val MaxKept = 500
        const val LogTag = "HALO_TV"
    }
}
