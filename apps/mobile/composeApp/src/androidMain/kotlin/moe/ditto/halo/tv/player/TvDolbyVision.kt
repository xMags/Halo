package moe.ditto.halo.tv.player

import java.security.MessageDigest
import java.util.Locale
import moe.ditto.halo.api.Stream
import moe.ditto.halo.browse.resolveSummary
import moe.ditto.halo.browse.SourceEntry
import moe.ditto.halo.browse.SourcesResolve
import moe.ditto.halo.player.VideoFormat
import moe.ditto.halo.player.VideoPathPolicy

// The pure rules for Dolby Vision on a TV: when mpv shows a picture wrongly,
// which engine a file goes to, and how the sources page lists such files.
// TvDolbyVisionSources keeps what one device learned and asks these.

/**
 * True when the picture playing is a Dolby Vision profile that is wrong
 * without processing ([VideoPathPolicy.needsDolbyVisionProcessing]) and the
 * engine was seen not to apply that processing on this device
 * ([VideoFormat.dolbyVisionProcessed] false). Nothing is assumed about the
 * device: where the engine applies it, the file plays as it is.
 */
internal fun dolbyVisionShownWrongly(format: VideoFormat): Boolean =
    VideoPathPolicy.needsDolbyVisionProcessing(format.dolbyVisionProfile) && format.dolbyVisionProcessed == false

/** What this device's second engine has shown it can do with such files. */
internal enum class SecondEngineState {
    /** Not tried yet under this setup. */
    Untried,

    /** It played one, with the device's own Dolby Vision decoder. */
    Works,

    /** Its first try failed in the decoder; not tried again under this setup. */
    Fails,
}

/** What the player does about the Dolby Vision picture it is playing. */
internal enum class DolbyVisionStep {
    /** Nothing, or not yet: the picture is fine, or mpv has not been judged. */
    Wait,

    /** Play the file in the second engine instead. */
    HandOver,

    /** Remember the file and move to another source, as before the second engine. */
    MoveOn,
}

/**
 * The step for the picture playing in [engine]. Only mpv's pictures are
 * judged. A file that needs processing goes to the second engine when it can
 * take this file ([secondEngineForFile]): at once when the device has already
 * shown the second engine works ([secondEngineWorks]), so mpv's wrong picture
 * is not shown again; otherwise only once mpv was seen not to process it.
 * Without the second engine, mpv's verdict moves on as before.
 */
internal fun dolbyVisionStep(
    format: VideoFormat,
    engine: PlaybackEngine,
    secondEngineForFile: Boolean,
    secondEngineWorks: Boolean,
): DolbyVisionStep {
    if (engine != PlaybackEngine.Mpv) return DolbyVisionStep.Wait
    if (!VideoPathPolicy.needsDolbyVisionProcessing(format.dolbyVisionProfile)) return DolbyVisionStep.Wait
    // mpv was seen to process it: the file plays where it is.
    if (format.dolbyVisionProcessed == true) return DolbyVisionStep.Wait
    if (secondEngineForFile && secondEngineWorks) return DolbyVisionStep.HandOver
    if (format.dolbyVisionProcessed != false) return DolbyVisionStep.Wait
    return if (secondEngineForFile) DolbyVisionStep.HandOver else DolbyVisionStep.MoveOn
}

/** Android's `MediaCodecInfo.CodecProfileLevel.DolbyVisionProfile*` flag for profile [profile]: one bit per profile. */
internal fun dolbyVisionProfileFlag(profile: Int): Int? = if (profile in 0..9) 1 shl profile else null

/** One `video/dolby-vision` decoder this device lists, and the profiles it states (empty when it states none). */
internal data class DolbyVisionDecoder(val name: String, val profileFlags: Set<Int>)

/**
 * True when one of [decoders] takes [profile]: it lists that profile, or
 * lists none at all (then only a try can tell, and a failed try is caught).
 */
internal fun decodesDolbyVisionProfile(decoders: List<DolbyVisionDecoder>, profile: Int): Boolean {
    val flag = dolbyVisionProfileFlag(profile) ?: return false
    return decoders.any { it.profileFlags.isEmpty() || flag in it.profileFlags }
}

/**
 * Whether the second engine may take a file of [profile] on this device:
 * the device has a decoder for it and the engine has not failed here.
 */
internal fun secondEngineUsable(state: SecondEngineState, decoderTakes: Boolean): Boolean =
    decoderTakes && state != SecondEngineState.Fails

/**
 * The engine for a file about to load. The second engine only for a source
 * this device already saw mpv show wrongly ([record]'s [DolbyVisionRecord.wrong]),
 * that has not failed in the second engine, while [usable] for its profile.
 * Everything else, every file on a device that learned nothing, goes to mpv.
 */
internal fun engineForSource(fingerprint: String, record: DolbyVisionRecord?, usable: (profile: Int) -> Boolean): PlaybackEngine {
    if (record == null) return PlaybackEngine.Mpv
    val profile = record.wrong[fingerprint] ?: return PlaybackEngine.Mpv
    if (fingerprint in record.failed) return PlaybackEngine.Mpv
    return if (usable(profile)) PlaybackEngine.Media3 else PlaybackEngine.Mpv
}

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
 * Vision files wrongly in mpv. Without that, it is returned untouched.
 *
 * Where the second engine plays them ([secondEngineUsable]), they stay where
 * the shared rank puts them; only a source that failed in both engines
 * ([failedEverywhere]) is left out. Where it does not, as before the second
 * engine: a source this TV opened and found such a file ([knownWrong]) is
 * left out, and a name that states only Dolby Vision moves below every other
 * one. Either way a source is never left out when nothing else is left, and
 * positions are numbered again from 0, so the first entry is still the pick.
 */
internal fun tvPlayableSources(
    resolve: SourcesResolve,
    learned: Boolean,
    secondEngineUsable: Boolean,
    knownWrong: (String) -> Boolean,
    failedEverywhere: (String) -> Boolean,
): TvSourcesAdjustment {
    if (!learned) return TvSourcesAdjustment(resolve, leftOut = 0, movedDown = 0)
    val hidden: (String) -> Boolean = if (secondEngineUsable) failedEverywhere else knownWrong
    val rest = resolve.pool.filterNot { entry -> entry.stream.url?.let(hidden) == true }
    val kept = rest.ifEmpty { resolve.pool }
    val leftOut = resolve.pool.size - kept.size
    val (named, plain) = if (secondEngineUsable) {
        emptyList<SourceEntry>() to kept
    } else {
        kept.partition { statesDolbyVisionOnly(it.stream) }
    }
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
 * How long what this TV learned holds after it last learned something. The
 * cause is the decoder path, which changes with an update of the app or of
 * the device, or with the decoding setting; a change of either is caught at
 * once ([DolbyVisionRecord.setup]), and this catches what is not (a system
 * update that keeps the app). The cost of forgetting too early is one moment
 * of mpv's wrong colours before the player hands over or moves on by itself.
 */
internal const val DolbyVisionMemoryMs = 30L * 24 * 60 * 60 * 1000

/**
 * What this TV learned, under which [setup] (this app install and its
 * decoding setting), when it last learned something: the sources mpv showed
 * wrongly with their profile ([wrong], by fingerprint, oldest first), what
 * the second engine has shown ([secondEngine]), and the sources that failed
 * in it too ([failed]).
 */
internal data class DolbyVisionRecord(
    val setup: String,
    val learnedAt: Long,
    val wrong: Map<String, Int>,
    val secondEngine: SecondEngineState = SecondEngineState.Untried,
    val failed: Set<String> = emptySet(),
    /** The frame rate a remembered source was seen playing at, by fingerprint; only for those known. */
    val frameRates: Map<String, Double> = emptyMap(),
)

private val Fingerprint = Regex("^[0-9a-f]{64}$")

/**
 * The profile a first record (`v1`) stands for: it kept no profile, and the
 * only one this device had then seen wrongly was 5.
 */
private const val ProfileOfFirstRecords = 5

/**
 * `v2`, then the setup, the time, `engine <state>`, and one `wrong <fp>
 * <profile>` or `failed <fp>` line each.
 */
internal fun encodeDolbyVisionRecord(record: DolbyVisionRecord): String = buildList {
    add("v3")
    add(record.setup)
    add(record.learnedAt.toString())
    add("engine ${record.secondEngine.name.lowercase()}")
    record.wrong.forEach { (fingerprint, profile) -> add("wrong $fingerprint $profile") }
    record.failed.forEach { add("failed $it") }
    record.frameRates.forEach { (fingerprint, fps) -> add("rate $fingerprint ${String.format(Locale.ROOT, "%.4f", fps)}") }
}.joinToString("\n")

/**
 * Null for nothing stored or a record that is not well formed; it is then
 * ignored as a whole. A `v1` record (sources only) reads as sources mpv
 * showed wrongly, with the second engine untried; a `v2` one as written,
 * with no frame rates (`v3` adds `rate <fp> <fps>` lines).
 */
internal fun decodeDolbyVisionRecord(text: String?): DolbyVisionRecord? {
    val lines = text?.split('\n') ?: return null
    return when (lines.firstOrNull()) {
        "v1" -> decodeFirstRecord(lines)
        "v2" -> decodeSecondRecord(lines, withRates = false)
        "v3" -> decodeSecondRecord(lines, withRates = true)
        else -> null
    }
}

private fun decodeFirstRecord(lines: List<String>): DolbyVisionRecord? {
    if (lines.size < 4) return null
    val setup = lines[1].takeIf { it.isNotBlank() } ?: return null
    val learnedAt = lines[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val fingerprints = lines.drop(3)
    if (fingerprints.any { !Fingerprint.matches(it) }) return null
    return DolbyVisionRecord(setup, learnedAt, fingerprints.associateWith { ProfileOfFirstRecords })
}

private fun decodeSecondRecord(lines: List<String>, withRates: Boolean): DolbyVisionRecord? {
    if (lines.size < 4) return null
    val setup = lines[1].takeIf { it.isNotBlank() } ?: return null
    val learnedAt = lines[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val engineLine = lines[3].split(' ')
    if (engineLine.size != 2 || engineLine[0] != "engine") return null
    val engine = SecondEngineState.entries.firstOrNull { it.name.lowercase() == engineLine[1] } ?: return null
    val wrong = LinkedHashMap<String, Int>()
    val failed = LinkedHashSet<String>()
    val rates = LinkedHashMap<String, Double>()
    for (line in lines.drop(4)) {
        val parts = line.split(' ')
        when {
            parts.size == 3 && parts[0] == "wrong" && Fingerprint.matches(parts[1]) ->
                wrong[parts[1]] = parts[2].toIntOrNull()?.takeIf { it in 0..20 } ?: return null
            parts.size == 2 && parts[0] == "failed" && Fingerprint.matches(parts[1]) -> failed += parts[1]
            withRates && parts.size == 3 && parts[0] == "rate" && Fingerprint.matches(parts[1]) ->
                rates[parts[1]] = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 1000 } ?: return null
            else -> return null
        }
    }
    if (wrong.isEmpty()) return null
    return DolbyVisionRecord(setup, learnedAt, wrong, engine, failed, rates.filterKeys { it in wrong })
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
