package moe.ditto.halo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import moe.ditto.halo.AndroidActivityHosts
import moe.ditto.halo.downloads.NoDownloadStorage
import moe.ditto.halo.player.MpvProfile
import moe.ditto.halo.player.NoVideoFrameSource
import moe.ditto.halo.tv.player.TvAudioOutput

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
        hosts = AndroidActivityHosts(this, playerProfile = MpvProfile.Tv)
        val dependencies = hosts.dependencies(
            intent = intent,
            downloadStorage = NoDownloadStorage,
            videoFrameSource = NoVideoFrameSource,
        )
        // Debug-only, like every launch extra: any app can start this activity.
        val showKitGallery = hosts.diagnosticsEnabled && intent?.getBooleanExtra("tvKitGallery", false) == true
        val audioOutput = TvAudioOutput(applicationContext, hosts.playerHost)
        setContent {
            TvApp(dependencies, audioOutput = audioOutput, onExitApp = { finish() }, showKitGallery = showKitGallery)
        }
    }

    override fun onDestroy() {
        if (::hosts.isInitialized) hosts.close()
        super.onDestroy()
    }
}
