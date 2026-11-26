package moe.ditto.halo

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import moe.ditto.halo.auth.AndroidSecureStorage
import moe.ditto.halo.auth.BrowserSignInPort
import moe.ditto.halo.auth.KtorAndroidOidcWire
import moe.ditto.halo.auth.KtorAuthConfigSource
import moe.ditto.halo.auth.NoBrowserSignIn
import moe.ditto.halo.downloads.DownloadStoragePort
import moe.ditto.halo.player.AndroidPlayerSystemPort
import moe.ditto.halo.player.MpvProfile
import moe.ditto.halo.player.SubtitleFontLibrary
import moe.ditto.halo.player.VideoFrameSource
import moe.ditto.halo.storage.AndroidPreferencesStore
import moe.ditto.halo.storage.LineSpeedStore
import java.io.File

/**
 * The native hosts an activity owns, and the [PlatformDependencies] built over
 * them. The phone activity ([MainActivity]) and the TV activity build the same
 * object and differ only in the few ports [dependencies] takes, so there is one
 * copy of how the app is assembled on Android.
 *
 * Create it in `onCreate` (the OIDC host registers for activity results) and
 * [close] it in `onDestroy`.
 */
internal class AndroidActivityHosts(
    activity: ComponentActivity,
    /** The phone's engine options unless the activity serves another kind of screen. */
    playerProfile: MpvProfile = MpvProfile.Phone,
) {
    val authHttpClient: HttpClient = HttpClient(OkHttp)
    private val secureStorage = AndroidSecureStorage(activity.applicationContext)
    val authHost = AndroidOidcAuthHost(
        activity = activity,
        storage = secureStorage,
        wire = KtorAndroidOidcWire(authHttpClient),
    )
    // The same store and key the sources pages read through the session
    // graph, so the phone and the TV rank by what this player measured.
    val playerHost = AndroidMpvPlayerHost(
        activity.applicationContext,
        playerProfile,
        lineSpeed = LineSpeedStore(AndroidPreferencesStore(activity.applicationContext)),
    )
    val playerSystemPort = AndroidPlayerSystemPort(activity)
    private val application = activity.application as HaloApplication
    private val applicationContext = activity.applicationContext

    /**
     * The manifest's own debuggable flag, so a release build cannot reach the
     * diagnostics harness. Read from ApplicationInfo rather than BuildConfig,
     * which this module does not generate.
     */
    val diagnosticsEnabled: Boolean =
        (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Builds the dependencies for this activity's launch [intent]. Automation
     * may point at a fixture and reset the session; a release build ignores
     * those extras, since any app can start an exported activity.
     */
    fun dependencies(
        intent: Intent?,
        browserSignIn: BrowserSignInPort = NoBrowserSignIn,
        downloadStorage: DownloadStoragePort,
        videoFrameSource: VideoFrameSource,
        openDownloadsEvents: Flow<Unit> = emptyFlow(),
    ): PlatformDependencies {
        val serverUrlExtra = intent?.getStringExtra("serverUrl")
        val resetSessionExtra = intent?.getBooleanExtra("resetSession", false) ?: false
        val launch = LaunchOverrides.resolve(
            diagnosticsEnabled = diagnosticsEnabled,
            serverUrl = serverUrlExtra,
            mediaHttpBase = intent?.getStringExtra("mediaHttpBase"),
            resetSession = resetSessionExtra,
        )
        // Every instrumented suite launches with a fixture server or a reset.
        val automationLaunch = diagnosticsEnabled && (serverUrlExtra != null || resetSessionExtra)
        return PlatformDependencies(
            authConfigSource = KtorAuthConfigSource(authHttpClient),
            nativeHostRequests = authHost,
            secureStorage = secureStorage,
            keyValueStore = AndroidPreferencesStore(applicationContext),
            // cacheDir, not filesDir: Android's auto-backup skips it and the
            // system may reclaim it, which suits re-fetchable poster art.
            imageCacheDirectory = File(applicationContext.cacheDir, "halo-images").path,
            subtitleCacheDirectory = File(applicationContext.cacheDir, "halo-subtitles").path,
            downloadStorage = downloadStorage,
            downloadRuntime = application.downloadRuntime,
            diagnosticsEnabled = diagnosticsEnabled,
            playerPort = AndroidPlayerPort(playerHost),
            playerSystemPort = playerSystemPort,
            videoFrameSource = videoFrameSource,
            bundledSubtitleFonts = SubtitleFontLibrary.bundledFamilies(),
            playerEvents = playerHost.playerEvents,
            oidcSessionPort = authHost,
            browserSignIn = browserSignIn,
            authEvents = authHost.events,
            openDownloadsEvents = openDownloadsEvents,
            nativePlayerSurface = AndroidNativePlayerSurface(playerHost),
            nativeHostDiagnostics = AndroidNativeHostDiagnostics(authHost, playerHost),
            initialServerUrl = launch.serverUrl,
            mediaHttpBase = launch.mediaHttpBase,
            resetPersistedSession = launch.resetSession,
            automationLaunch = automationLaunch,
        )
    }

    fun close() {
        playerHost.close()
        authHost.close()
        authHttpClient.close()
    }
}
