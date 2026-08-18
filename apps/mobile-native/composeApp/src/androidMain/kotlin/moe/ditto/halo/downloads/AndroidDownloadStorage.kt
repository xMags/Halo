package moe.ditto.halo.downloads

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Android's downloads directory: app-private internal storage, under
 * `filesDir` rather than `cacheDir`.
 *
 * The distinction is the whole point of the choice. Everything in `cacheDir`
 * may be reclaimed by the system whenever it wants space, which is right for
 * poster art and subtitle files and wrong for a film someone downloaded to
 * watch on a flight. The manifest already sets `allowBackup="false"`, so
 * nothing here needs excluding from a backup that does not happen.
 *
 * Internal rather than external storage keeps the files out of the media
 * scanner and out of other apps' reach, and needs no permission on any
 * supported version.
 */
internal class AndroidDownloadStorage(context: Context) : DownloadStoragePort {
    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.filesDir, DirectoryName)

    override fun directory(): String? {
        if (directory.isDirectory) return directory.path
        return if (directory.mkdirs()) directory.path else null
    }

    override fun freeBytes(): Long? = volume()?.availableBytes?.takeIf { it >= 0 }

    override fun totalBytes(): Long? = volume()?.totalBytes?.takeIf { it > 0 }

    /**
     * Measured on `filesDir` rather than on the downloads directory, which may
     * not exist yet; they are the same volume either way.
     */
    private fun volume(): StatFs? = try {
        StatFs(applicationContext.filesDir.path)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val DirectoryName = "downloads"
    }
}
