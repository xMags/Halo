package moe.ditto.halo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import moe.ditto.halo.AndroidActivityHosts
import moe.ditto.halo.downloads.NoDownloadStorage
import moe.ditto.halo.player.MpvProfile
import moe.ditto.halo.player.NoVideoFrameSource
import moe.ditto.halo.storage.AndroidPreferencesStore
import moe.ditto.halo.tv.player.TvAudioOutput
import moe.ditto.halo.tv.player.TvDolbyVisionSources
import moe.ditto.halo.tv.player.TvPlaybackPreferences

/**
 * The TV entry point, listed by TV launchers (`LEANBACK_LAUNCHER`) while phone
 * launchers list [moe.ditto.halo.MainActivity]. It builds the same dependencies
 * as the phone with three differences: no browser sign-in (a TV signs in with
 * a code or a password), no download storage (the TV has no Downloads), and no
 * scrub-preview frames (a second decoder is a risk on TV chips).
 *
 * Like the phone activity it declares configChanges in the manifest, so the
 * mpv core survives a configuration change instead of being recreated.
 */
class TvActivity : ComponentActivity() {

    private lateinit var hosts: AndroidActivityHosts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The player is built here, before any screen, so the decoding
        // setting is read straight from the device's store.
        val deviceStore = AndroidPreferencesStore(applicationContext)
        val hardwareDecoding = TvPlaybackPreferences.hardwareDecodingIn(deviceStore)
        hosts = AndroidActivityHosts(this, playerProfile = if (hardwareDecoding) MpvProfile.Tv else MpvProfile.TvSoftware)
        val dependencies = hosts.dependencies(
            intent = intent,
            downloadStorage = NoDownloadStorage,
            videoFrameSource = NoVideoFrameSource,
        )
        val dolbyVision = TvDolbyVisionSources(
            store = deviceStore,
            setup = dolbyVisionSetup(hardwareDecoding),
            showAsIs = { hosts.playerHost.showDolbyVisionAsIs() },
        )

        // Debug-only, like every launch extra: any app can start this activity.
        val showKitGallery = hosts.diagnosticsEnabled && intent?.getBooleanExtra("tvKitGallery", false) == true
        // The same device-local store as the setting above: what this TV's
        // sound output failed to start is never synced.
        val audioOutput = TvAudioOutput(applicationContext, hosts.playerHost, deviceStore)
        // Before any screen, so every load asks it for its passthrough list.
        hosts.playerHost.passthroughForLoad = audioOutput
        setContent {
            TvApp(dependencies, audioOutput = audioOutput, dolbyVision = dolbyVision, onExitApp = { finish() }, showKitGallery = showKitGallery)
        }
    }

    override fun onDestroy() {
        if (::hosts.isInitialized) hosts.close()
        super.onDestroy()
    }

    /**
     * What decides whether the engine can process a Dolby Vision picture
     * here: this install of the app (its time changes with every update) and
     * whether the video chip decodes. A change of either may change the
     * answer, so what was learned under the old one is forgotten.
     */
    private fun dolbyVisionSetup(hardwareDecoding: Boolean): String {
        val installed = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "app:$installed;hardware:$hardwareDecoding"
    }
}
