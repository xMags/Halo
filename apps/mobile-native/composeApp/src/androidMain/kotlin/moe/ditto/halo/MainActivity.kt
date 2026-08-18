package moe.ditto.halo

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import moe.ditto.halo.auth.AndroidSecureStorage
import moe.ditto.halo.auth.KtorAndroidOidcWire
import moe.ditto.halo.auth.KtorAuthConfigSource
import moe.ditto.halo.downloads.AndroidDownloadStorage
import moe.ditto.halo.downloads.AndroidDownloadNotifications
import moe.ditto.halo.player.AndroidPlayerSystemPort
import moe.ditto.halo.player.AndroidVideoFrameSource
import moe.ditto.halo.player.SubtitleFontLibrary
import moe.ditto.halo.storage.AndroidPreferencesStore
import java.io.File
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

    private lateinit var playerHost: AndroidMpvPlayerHost
    private lateinit var playerSystemPort: AndroidPlayerSystemPort
    private lateinit var authHost: AndroidOidcAuthHost
    private lateinit var authHttpClient: HttpClient
    private val openDownloads = Channel<Unit>(Channel.UNLIMITED)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* A denied prompt does not block WorkManager. */ }

    /** Instrumentation seam that reads the initialized core, not an option list. */
    internal fun playerMutedForTest(): Boolean? = playerHost.mutedForTest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authHttpClient = HttpClient(OkHttp)
        val secureStorage = AndroidSecureStorage(applicationContext)
        authHost = AndroidOidcAuthHost(
            activity = this,
            storage = secureStorage,
            wire = KtorAndroidOidcWire(authHttpClient),
        )
        playerHost = AndroidMpvPlayerHost(applicationContext)
        playerSystemPort = AndroidPlayerSystemPort(this)
        val haloApplication = application as HaloApplication
        haloApplication.backgroundDownloadPort.attachNotificationPermissionRequester {
            requestNotificationPermission()
        }

        val diagnosticsEnabled =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Automation may point at a fixture. With no override, using the shared
        // default preserves the last-successful-server prefill rule in HaloApp.
        val serverUrl = intent?.getStringExtra("serverUrl")
            ?: PlatformDependencies.DefaultServerUrl
        // The media base is an internal debug/test seam. A release build always
        // keeps the normal default, even if a caller supplies this extra.
        val mediaHttpBase = if (diagnosticsEnabled) {
            intent?.getStringExtra("mediaHttpBase")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: PlatformDependencies.DefaultMediaHttpBase
        } else {
            PlatformDependencies.DefaultMediaHttpBase
        }

        val dependencies = PlatformDependencies(
            authConfigSource = KtorAuthConfigSource(authHttpClient),
            nativeHostRequests = authHost,
            secureStorage = secureStorage,
            keyValueStore = AndroidPreferencesStore(applicationContext),
            // cacheDir, not filesDir: Android's auto-backup skips it and the
            // system may reclaim it, which suits re-fetchable poster art.
            imageCacheDirectory = File(applicationContext.cacheDir, "halo-images").path,
            subtitleCacheDirectory = File(applicationContext.cacheDir, "halo-subtitles").path,
            // filesDir, not cacheDir: the system may reclaim a cache at any
            // time, and a downloaded film is the one thing here that cannot be
            // fetched again on demand.
            downloadStorage = haloApplication.downloadStorage,
            downloadRuntime = haloApplication.downloadRuntime,
            // The manifest's own debuggable flag, so a release build cannot
            // reach the diagnostics harness. Read from ApplicationInfo rather
            // than BuildConfig, which this module does not generate.
            diagnosticsEnabled = diagnosticsEnabled,
            playerPort = AndroidPlayerPort(playerHost),
            playerSystemPort = playerSystemPort,
            videoFrameSource = AndroidVideoFrameSource(),
            bundledSubtitleFonts = SubtitleFontLibrary.bundledFamilies(),
            playerEvents = playerHost.playerEvents,
            oidcSessionPort = authHost,
            authEvents = authHost.events,
            openDownloadsEvents = openDownloads.receiveAsFlow(),
            nativePlayerSurface = AndroidNativePlayerSurface(playerHost),
            nativeHostDiagnostics = AndroidNativeHostDiagnostics(authHost, playerHost),
            initialServerUrl = serverUrl,
            mediaHttpBase = mediaHttpBase,
            resetPersistedSession = intent?.getBooleanExtra("resetSession", false) ?: false,
        )

        setContent {
            HaloApp(dependencies)
        }

        authHost.handleIntent(intent)
        handleDownloadNavigation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authHost.handleIntent(intent)
        handleDownloadNavigation(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::playerSystemPort.isInitialized) {
            playerSystemPort.onPictureInPictureModeChanged(isInPictureInPictureMode)
        }
    }

    override fun onDestroy() {
        (application as? HaloApplication)?.backgroundDownloadPort
            ?.attachNotificationPermissionRequester(null)
        if (::playerHost.isInitialized) playerHost.close()
        authHost.close()
        authHttpClient.close()
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

    private fun handleDownloadNavigation(intent: Intent?) {
        if (intent?.action == AndroidDownloadNotifications.OpenDownloadsAction) {
            openDownloads.trySend(Unit)
            intent.action = null
        }
    }
}
