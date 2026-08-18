package moe.ditto.halo.player

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The typefaces libass is allowed to use, unpacked to a directory it can read.
 *
 * libass resolves font names through fontconfig, which on Android sees only the
 * system families. A name the device does not ship resolves to whatever the
 * fallback is, silently, so a font chip would appear to work while changing
 * nothing. Handing mpv its own directory is what makes the choice real: a
 * family placed here is found by name, and one that is not is honestly absent.
 *
 * Fonts ship as app assets and are copied out once per version, because
 * fontconfig scans a filesystem path and cannot read an asset. The copy is
 * keyed on the file already existing at the right size, so a reinstall with a
 * changed font replaces it and an ordinary launch does no work.
 */
internal object SubtitleFontLibrary {

    /**
     * Asset path to family name. The names are the ones the app's font chips
     * and the synced `subtitleFontFamily` setting use, so they have to match
     * the family recorded inside the file, not the file name.
     */
    private val BundledFonts = mapOf(
        "composeResources/moe.ditto.halo.resources/font/inter_regular.otf" to "Inter",
        "composeResources/moe.ditto.halo.resources/font/inter_bold.otf" to "Inter",
        "composeResources/moe.ditto.halo.resources/font/sourceserif4_regular.otf" to "Source Serif 4",
        "composeResources/moe.ditto.halo.resources/font/sourceserif4_bold.otf" to "Source Serif 4",
        "composeResources/moe.ditto.halo.resources/font/jetbrainsmono_regular.ttf" to "JetBrains Mono",
    )

    private const val DirectoryName = "subtitle-fonts"

    /**
     * Unpacks the bundled fonts and returns the directory, or null when none
     * could be unpacked, in which case the caller leaves mpv on the system
     * fonts rather than pointing it at an empty directory.
     */
    fun prepare(context: Context): String? {
        val directory = File(context.filesDir, DirectoryName)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(MpvCore.LOG_TAG, "subtitle fonts: cannot create $directory")
            return null
        }

        var unpacked = 0
        for (assetPath in BundledFonts.keys) {
            if (copyAsset(context, assetPath, File(directory, assetPath.substringAfterLast('/')))) unpacked += 1
        }
        if (unpacked == 0) return null
        return directory.absolutePath
    }

    /** The families [prepare] can actually supply, for the UI to offer honestly. */
    fun bundledFamilies(): Set<String> = BundledFonts.values.toSet()

    private fun copyAsset(context: Context, assetPath: String, target: File): Boolean = try {
        context.assets.open(assetPath).use { source ->
            // Re-copying an identical file would rewrite it on every launch;
            // the asset is immutable within a build, so equal size means equal
            // file here.
            val expected = source.available().toLong()
            if (target.isFile && target.length() == expected) {
                true
            } else {
                target.outputStream().use { destination -> source.copyTo(destination) }
                true
            }
        }
    } catch (failure: java.io.IOException) {
        // A missing or unreadable font is a lost typeface, not a lost player.
        Log.w(MpvCore.LOG_TAG, "subtitle fonts: $assetPath unavailable (${failure.message})")
        false
    }
}
