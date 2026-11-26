package moe.ditto.halo.tv.player

import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.storage.StorageKeys

/**
 * How long a format that failed to start stays off. A viewer who connects a
 * receiver later gets passthrough back within this without touching a
 * setting, when the output's identity does not already change with it. The
 * cost of remembering too long is sound decoded here, which still plays; the
 * cost of each retry is one title held on a still picture for the watchdog's
 * window. A week keeps that to one such title a week on a device where
 * passthrough really does not work.
 */
internal const val PassthroughFailureExpiryMs = 7L * 24 * 60 * 60 * 1000

/** One HDMI sound output, as far as it stays the same across a re-plug. */
internal data class AudioOutputDevice(
    /** "hdmi" or "hdmi-arc". */
    val kind: String,
    /**
     * What the platform calls the device. Android falls back to the box's
     * own model name when the output reports none, so it is never blank.
     */
    val productName: String,
)

/**
 * What identifies the sound output for [PassthroughFailures]: the kinds and
 * names of the HDMI outputs, never their ids or format lists. Fire OS drops
 * and re-adds its HDMI output on every refresh rate switch, with a new id
 * each time and a format list that may be read half filled. Null when none is
 * listed, which is what a re-plug looks like halfway through: unknown, not a
 * different output.
 */
internal fun outputIdentity(devices: List<AudioOutputDevice>): String? {
    if (devices.isEmpty()) return null
    return devices
        .map { "${it.kind}:${it.productName.trim().replace('\n', ' ').replace('\r', ' ')}" }
        .distinct()
        .sorted()
        .joinToString(";")
}

/**
 * Formats that were taken on paper but did not start ([TvPassthroughWatchdog]),
 * kept on this device and never synced, for the output they failed on. Each
 * stays off until [PassthroughFailureExpiryMs] has passed or a different
 * output is identified; an output that is momentarily not identified
 * ([outputIdentity] null) changes nothing.
 *
 * Thread-safe: read where the formats are worked out (the IO dispatcher) and
 * recorded from the watchdog. The stored copy is read on first use, not at
 * construction, so the activity's start never waits on storage.
 */
internal class PassthroughFailures(
    private val store: KeyValueStore,
    /** Wall-clock milliseconds; the record has to survive a restart, which a boot-relative clock does not. */
    private val now: () -> Long = System::currentTimeMillis,
    private val expiryMs: Long = PassthroughFailureExpiryMs,
) {
    private val lock = Any()
    // Serializes writes only, so storage I/O never runs under [lock]. Each
    // write takes the state as it is when it runs, so the last one is current.
    private val writeLock = Any()
    private var loaded = false
    private var output: String? = null
    private val failed = mutableMapOf<PassthroughFormat, Long>()

    /**
     * [supported] without what failed on [output]. A known output that
     * differs from the one remembered forgets the failures first; a null one
     * keeps them.
     */
    fun usable(supported: Set<PassthroughFormat>, output: String?): Set<PassthroughFormat> {
        var changed = false
        val usable = synchronized(lock) {
            changed = loadAndExpire()
            if (output != null && output != this.output) {
                if (this.output != null && failed.isNotEmpty()) {
                    failed.clear()
                    changed = true
                }
                // Failures recorded before the output was ever identified are
                // kept and adopted: they were almost certainly this output's.
                if (failed.isNotEmpty()) changed = true
                this.output = output
            }
            supported - failed.keys
        }
        if (changed) persist()
        return usable
    }

    /** Recorded against the output the last [usable] call identified; in memory only if none was. */
    fun record(formats: Set<PassthroughFormat>) {
        if (formats.isEmpty()) return
        synchronized(lock) {
            loadAndExpire()
            val at = now()
            formats.forEach { failed[it] = at }
        }
        persist()
    }

    /** Under [lock]. True when the remembered set changed and should be written. */
    private fun loadAndExpire(): Boolean {
        if (!loaded) {
            loaded = true
            val stored = runCatching { store.read(StorageKeys.TvPassthroughFailures) }.getOrNull()
            decodePassthroughFailures(stored)?.let { record ->
                output = record.output
                failed.putAll(record.failed)
            }
        }
        val at = now()
        val expired = failed.filterValues { recordedAt -> isExpired(recordedAt, at) }.keys
        if (expired.isEmpty()) return false
        failed.keys.removeAll(expired)
        return true
    }

    /** A record from the future (the clock was moved back) is expired too, so it cannot stay forever. */
    private fun isExpired(recordedAt: Long, at: Long): Boolean = at < recordedAt || at - recordedAt >= expiryMs

    private fun persist() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                val known = output
                if (known == null || failed.isEmpty()) null else encodePassthroughFailures(StoredPassthroughFailures(known, failed.toMap()))
            }
            // A failed write only loses the memory across a restart.
            runCatching {
                if (text == null) store.delete(StorageKeys.TvPassthroughFailures) else store.write(StorageKeys.TvPassthroughFailures, text)
            }
        }
    }
}

internal data class StoredPassthroughFailures(val output: String, val failed: Map<PassthroughFormat, Long>)

private const val StoredFormatVersion = "v1"

/**
 * One line each: the version, the output's identity, then one
 * `<mpv name> <recorded at, epoch ms>` per format. [outputIdentity] has no
 * line breaks in it.
 */
internal fun encodePassthroughFailures(record: StoredPassthroughFailures): String = buildString {
    append(StoredFormatVersion).append('\n').append(record.output)
    PassthroughFormat.entries.forEach { format ->
        val at = record.failed[format] ?: return@forEach
        append('\n').append(format.mpvName).append(' ').append(at)
    }
}

/** Null for nothing stored or anything malformed, which is then ignored as a whole. */
internal fun decodePassthroughFailures(text: String?): StoredPassthroughFailures? {
    val lines = text?.split('\n') ?: return null
    if (lines.size < 3 || lines[0] != StoredFormatVersion) return null
    val output = lines[1].takeIf { it.isNotBlank() } ?: return null
    val failed = mutableMapOf<PassthroughFormat, Long>()
    for (line in lines.drop(2)) {
        val parts = line.split(' ')
        if (parts.size != 2) return null
        val format = PassthroughFormat.entries.firstOrNull { it.mpvName == parts[0] } ?: return null
        val at = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        failed[format] = at
    }
    return StoredPassthroughFailures(output, failed)
}
