package moe.ditto.halo.storage

import moe.ditto.halo.browse.foldLineSpeed

/**
 * This device's measured line speed, in megabits per second, which the source
 * ranking weighs files against. Device-local and never synced: a phone on a
 * mobile network and a TV on a wired line have different lines, and the
 * reading describes the network this device plays over.
 *
 * Written by the player when a network stream's session ends (Android's
 * `MpvCore`), read by the sources pages on the phone and the TV, which share
 * this one key. Safe from any thread, as [KeyValueStore] is.
 */
internal class LineSpeedStore(private val store: KeyValueStore) {
    /** The kept reading; 0 when nothing has measured the line yet, or the stored value is unusable. */
    fun current(): Double {
        val value = store.read(StorageKeys.MeasuredLineSpeed)?.toDoubleOrNull() ?: return 0.0
        return if (value.isFinite() && value > 0) value else 0.0
    }

    /**
     * Folds one playback session into the kept reading ([foldLineSpeed]) and
     * writes it when it changed. Returns the new reading when it was written,
     * null when nothing changed.
     */
    fun record(sessionPeakMbps: Double, stalled: Boolean): Double? {
        val previous = current()
        val next = foldLineSpeed(previous, sessionPeakMbps, stalled)
        if (next == previous) return null
        store.write(StorageKeys.MeasuredLineSpeed, next.toString())
        return next
    }
}
