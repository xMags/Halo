package moe.ditto.halo.downloads

/**
 * Where this device keeps downloaded media, and how much room is left there.
 *
 * The transfer itself is not behind this port. Downloading is an HTTP range
 * request written to a file, and both halves are already available to shared
 * code, so a per-platform engine would be the same engine written twice. What
 * genuinely differs per platform is the directory: which one survives the
 * system reclaiming space, and which one is kept out of the device's backups.
 *
 * Both implementations answer with a directory that is *not* the cache
 * directory the image and subtitle caches use. A poster is re-fetchable and a
 * subtitle is a few kilobytes; a film someone deliberately kept for a flight is
 * neither, and the system deleting it under storage pressure would be data
 * loss.
 *
 * Every method is cheap and safe to call from any thread.
 */
interface DownloadStoragePort {
    /**
     * The downloads directory, created if it did not exist, or null when this
     * platform has nowhere to put one.
     *
     * Null is an ordinary answer rather than a failure: it is what a platform
     * with no implementation yet should say, and the Downloads screen renders
     * it as such instead of offering a button that does nothing.
     */
    fun directory(): String?

    /**
     * Free bytes on the volume holding [directory], or null when the platform
     * cannot say. Null means proceed: refusing a download because the amount of
     * free space is unknown would be worse than attempting one that may fail.
     */
    fun freeBytes(): Long?

    /**
     * Total bytes on the volume holding [directory], or null when the platform
     * cannot say.
     *
     * Only the storage meter reads this, and only alongside [freeBytes]: the two
     * together are what place the downloads against everything else on the
     * device. Nothing decides whether a download may start from it — that is
     * [freeBytes]'s job alone, since a full volume is full whatever its size.
     */
    fun totalBytes(): Long?
}

/** For platforms with no implementation yet, and for tests. */
object NoDownloadStorage : DownloadStoragePort {
    override fun directory(): String? = null
    override fun freeBytes(): Long? = null
    override fun totalBytes(): Long? = null
}
