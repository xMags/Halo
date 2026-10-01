package moe.ditto.halo.auth

/** Wall-clock time in epoch milliseconds; injectable so token-expiry band logic is testable. */
fun interface EpochClock {
    fun nowMs(): Long
}

object SystemEpochClock : EpochClock {
    override fun nowMs(): Long = systemEpochMillis()
}

internal expect fun systemEpochMillis(): Long
