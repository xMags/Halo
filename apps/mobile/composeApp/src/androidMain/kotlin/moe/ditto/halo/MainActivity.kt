package moe.ditto.halo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import moe.ditto.halo.auth.AndroidBrowserSignIn
import moe.ditto.halo.downloads.AndroidDownloadNotifications
import moe.ditto.halo.player.AndroidVideoFrameSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Android entry point. Assembles the exact same [PlatformDependencies] the iOS
 * `MainViewController` builds — only the concrete host implementations differ
 * (libmpv-on-SurfaceView here, MPVKit-on-UIView there). If the common shell
 * needed any Android-specific change to run, that would be a finding; it does
 * not.
 *
 * The host is created once and held across configuration changes (the manifest
 * declares configChanges so rotation does not recreate the activity), which is
 * how the mpv core survives rotation the way iOS's Swift-owned host does.
 */
class MainActivity : ComponentActivity() {

    private lateinit var hosts: AndroidActivityHosts
    private lateinit var browserSignIn: AndroidBrowserSignIn
    private val openDownloads = Channel<Unit>(Channel.UNLIMITED)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* A denied prompt does not block WorkManager. */ }

    /** Instrumentation seam that reads the initialized core, not an option list. */
    internal fun playerMutedForTest(): Boolean? = hosts.playerHost.mutedForTest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hosts = AndroidActivityHosts(this)
        browserSignIn = AndroidBrowserSignIn(this)
        val haloApplication = application as HaloApplication
        haloApplication.backgroundDownloadPort.attachNotificationPermissionRequester {
            requestNotificationPermission()
        }

        val dependencies = hosts.dependencies(
            intent = intent,
            browserSignIn = browserSignIn,
            // filesDir, not cacheDir: the system may reclaim a cache at any
            // time, and a downloaded film is the one thing here that cannot be
            // fetched again on demand.
            downloadStorage = haloApplication.downloadStorage,
            videoFrameSource = AndroidVideoFrameSource(),
            openDownloadsEvents = openDownloads.receiveAsFlow(),
        )

        setContent {
            HaloApp(dependencies)
        }

        handleAuthIntent(intent)
        handleDownloadNavigation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
        handleDownloadNavigation(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::hosts.isInitialized) {
            hosts.playerSystemPort.onPictureInPictureModeChanged(isInPictureInPictureMode)
        }
    }

    override fun onDestroy() {
        (application as? HaloApplication)?.backgroundDownloadPort
            ?.attachNotificationPermissionRequester(null)
        if (::hosts.isInitialized) hosts.close()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Add-on sign-in first: its callback path is its own, and the OIDC host need never see it. */
    private fun handleAuthIntent(intent: Intent?) {
        if (!browserSignIn.handleIntent(intent)) hosts.authHost.handleIntent(intent)
    }

    private fun handleDownloadNavigation(intent: Intent?) {
        if (intent?.action == AndroidDownloadNotifications.OpenDownloadsAction) {
            openDownloads.trySend(Unit)
            intent.action = null
        }
    }
}
