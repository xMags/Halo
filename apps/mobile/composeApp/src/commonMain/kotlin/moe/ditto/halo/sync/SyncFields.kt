package moe.ditto.halo.sync

/**
 * Field limits the server enforces on synced rows.
 *
 * These are trimmed client-side because a sync write is a batch and the server
 * validates before touching any row: one bad field rejects everything sent
 * with it. The values at risk are exactly the ones copied out of addon
 * metadata, which is not held to any of these rules — so a single addon
 * returning a relative poster path would otherwise stop an entire library or
 * watch-state write from landing, with nothing in the UI to explain it.
 */
internal object SyncFields {
    const val MaxNameLength = 512

    /**
     * Posters must parse as a URL server-side. Anything that is not an
     * absolute http(s) address is dropped rather than sent — the row is worth
     * more than the image.
     */
    fun poster(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    /** Names must be between 1 and 512 characters. */
    fun name(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MaxNameLength)
}
