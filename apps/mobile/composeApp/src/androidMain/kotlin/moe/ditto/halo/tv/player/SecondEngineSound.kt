package moe.ditto.halo.tv.player

import moe.ditto.halo.storage.KeyValueStore

/*
 * Dolby surround sent on untouched by the second engine, on a TV output
 * where mpv's own pass-through was seen to fail. The pure rules here; the
 * TV's TvSecondEngineSound asks them with this device's facts.
 */

/** The formats this round passes through in the second engine: Dolby only. */
internal val SecondEngineSoundFormats = setOf(PassthroughFormat.Ac3, PassthroughFormat.Eac3)

/** The Dolby format a track of [codec] (mpv's or the mapping's name) is sent as, or null for any other sound. */
internal fun dolbyFormatOf(codec: String?): PassthroughFormat? = when (codec?.lowercase()) {
    "ac3" -> PassthroughFormat.Ac3
    "eac3" -> PassthroughFormat.Eac3
    else -> null
}

/** What the second engine has shown for one format on this output. */
internal enum class SoundResult { Works, Fails }

/**
 * The sound side of the second engine's choice, for one format: everything
 * that must hold before it may send it on untouched.
 */
internal data class SecondEngineSoundFacts(
    /** The TV's Surround setting is Auto and the system's switch is not set to never. */
    val surroundAllowed: Boolean,
    /** mpv's pass-through was seen to fail for it on this output. */
    val mpvFailed: Boolean,
    /** The engine's own reading of the output says it takes the format. */
    val outputTakes: Boolean,
    /** The device can decode it too, so a failed pass-through still has sound. */
    val decoderFallback: Boolean,
    /** What the second engine showed for it on this output, null when untried. */
    val result: SoundResult?,
)

/** True when the second engine may send [facts]' format on untouched. */
internal fun secondEngineMayPassThrough(facts: SecondEngineSoundFacts): Boolean =
    facts.surroundAllowed && facts.mpvFailed && facts.outputTakes && facts.decoderFallback && facts.result != SoundResult.Fails

/**
 * Whether a file playing in mpv goes to the second engine for its sound:
 * the sound may pass through there ([soundFacts], for the selected track's
 * [format]), and nothing else about the file is lost on the way. The picture
 * must come from a hardware decoder (mpv's direct path shows it), and the
 * selected subtitle, if any, must be one the app draws itself as text:
 * never trade a working picture subtitle or kept script styling for surround.
 */
internal fun soundHandover(
    format: PassthroughFormat?,
    soundFacts: SecondEngineSoundFacts?,
    pictureFromHardware: Boolean,
    subtitleDrawnByApp: Boolean,
): Boolean {
    if (format == null || format !in SecondEngineSoundFormats || soundFacts == null) return false
    return secondEngineMayPassThrough(soundFacts) && pictureFromHardware && subtitleDrawnByApp
}

/** A stored result: when, and how it went. */
internal data class SoundRecord(val result: SoundResult, val at: Long)

/** `v1`, the output's identity, then one `<mpv name> <works|fails> <epoch ms>` per format. */
internal fun encodeSecondEngineSound(output: String, results: Map<PassthroughFormat, SoundRecord>): String = buildList {
    add("v1")
    add(output)
    PassthroughFormat.entries.forEach { format ->
        val record = results[format] ?: return@forEach
        add("${format.mpvName} ${record.result.name.lowercase()} ${record.at}")
    }
}.joinToString("\n")

/** Null for nothing stored or anything malformed, which is then ignored as a whole. */
internal fun decodeSecondEngineSound(text: String?): Pair<String, Map<PassthroughFormat, SoundRecord>>? {
    val lines = text?.split('\n') ?: return null
    if (lines.size < 3 || lines[0] != "v1") return null
    val output = lines[1].takeIf { it.isNotBlank() } ?: return null
    val results = LinkedHashMap<PassthroughFormat, SoundRecord>()
    for (line in lines.drop(2)) {
        val parts = line.split(' ')
        if (parts.size != 3) return null
        val format = PassthroughFormat.entries.firstOrNull { it.mpvName == parts[0] } ?: return null
        val result = SoundResult.entries.firstOrNull { it.name.lowercase() == parts[1] } ?: return null
        val at = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        results[format] = SoundRecord(result, at)
    }
    return output to results
}

/**
 * The second engine's own pass-through results, kept apart from mpv's
 * ([PassthroughFailures]) but by the same rules: on this device only, never
 * synced, for one output identity ([outputIdentity]; a different known output
 * forgets them, a momentarily unknown one keeps them), each for
 * [PassthroughFailureExpiryMs] after it was seen. Works expires too, so a
 * changed sound system is tried again.
 *
 * Thread-safe; writes run outside the state lock, the last one current.
 */
internal class SecondEngineSoundMemory(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val expiryMs: Long = PassthroughFailureExpiryMs,
) {
    private val lock = Any()
    private val writeLock = Any()
    private var loaded = false
    private var output: String? = null
    private val results = mutableMapOf<PassthroughFormat, SoundRecord>()

    /** What was seen for [format] on [output], null when untried there or expired. */
    fun resultFor(format: PassthroughFormat, output: String?): SoundResult? = synchronized(lock) {
        load()
        if (output != null && this.output != null && output != this.output) return@synchronized null
        results[format]?.takeIf { !expired(it.at) }?.result
    }

    /** Records [result] for [format] on [output]; a different known output starts afresh. */
    fun record(format: PassthroughFormat, result: SoundResult, output: String?) {
        synchronized(lock) {
            load()
            if (output != null && output != this.output) {
                results.clear()
                this.output = output
            }
            results.entries.removeAll { expired(it.value.at) }
            results[format] = SoundRecord(result, now())
        }
        persist()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        decodeSecondEngineSound(runCatching { store.read(StoreKey) }.getOrNull())?.let { (storedOutput, stored) ->
            output = storedOutput
            results.putAll(stored)
        }
    }

    /** A time from the future (the clock moved back) is expired too, so it cannot last forever. */
    private fun expired(at: Long): Boolean {
        val time = now()
        return time < at || time - at >= expiryMs
    }

    private fun persist() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                val known = output
                if (known == null || results.isEmpty()) null else encodeSecondEngineSound(known, results.toMap())
            }
            runCatching { if (text == null) store.delete(StoreKey) else store.write(StoreKey, text) }
        }
    }

    private companion object {
        const val StoreKey = "halo.tv.secondEngineSound.v1"
    }
}
