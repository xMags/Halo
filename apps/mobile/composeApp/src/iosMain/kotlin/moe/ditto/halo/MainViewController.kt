package moe.ditto.halo

import androidx.compose.ui.window.ComposeUIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.downloads.DeviceDownloadRuntime
import moe.ditto.halo.downloads.DownloadIndex
import moe.ditto.halo.downloads.HaloIosBackgroundDownloadHost
import moe.ditto.halo.downloads.IosBackgroundDownloadPort
import moe.ditto.halo.downloads.IosDownloadRequestVault
import moe.ditto.halo.auth.IosKeychainStorage
import moe.ditto.halo.downloads.IosDownloadStorage
import moe.ditto.halo.player.IosVideoFrameSource
import moe.ditto.halo.storage.IosUserDefaultsStore
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * Caches, not Documents: iOS excludes this directory from iCloud/iTunes backup
 * and may purge it under storage pressure, which is exactly right for
 * re-fetchable poster art.
 */
private fun imageCacheDirectory(): String {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .first() as String
    return "$caches/halo-images"
}

private fun subtitleCacheDirectory(): String {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .first() as String
    return "$caches/halo-subtitles"
}

@OptIn(ExperimentalNativeApi::class)
fun MainViewController(
    authHost: HaloIosAuthHost,
    playerHost: HaloIosPlayerHost,
    playerSystemHost: HaloIosPlayerSystemHost,
    backgroundDownloadHost: HaloIosBackgroundDownloadHost,
    initialServerUrl: String,
    mediaHttpBase: String,
    mediaLocalBase: String,
    resetPersistedSession: Boolean,
): UIViewController {
    val authAdapter = IosAuthHostAdapter(authHost)
    val playerEventBridge = IosPlayerEventBridge()
    playerHost.setEventSink(playerEventBridge)
    val authEventBridge = IosAuthEventBridge()
    authHost.setAuthEventSink(authEventBridge)
    val keyValueStore = IosUserDefaultsStore()
    val downloadStorage = IosDownloadStorage()
    val downloadVault = IosDownloadRequestVault(backgroundDownloadHost)
    val downloadRuntime = DeviceDownloadRuntime(
        index = DownloadIndex(keyValueStore, downloadVault),
        storage = downloadStorage,
        port = IosBackgroundDownloadPort(backgroundDownloadHost),
        vault = downloadVault,
        clock = SystemEpochClock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val dependencies = PlatformDependencies(
        authConfigSource = authAdapter,
        nativeHostRequests = authAdapter,
        secureStorage = IosKeychainStorage(),
        keyValueStore = keyValueStore,
        imageCacheDirectory = imageCacheDirectory(),
        subtitleCacheDirectory = subtitleCacheDirectory(),
        // Documents, not Caches: the system purges Caches, and downloaded media
        // is the one thing here that cannot be fetched again on demand. The
        // port marks the directory as excluded from backup for the same reason.
        downloadStorage = downloadStorage,
        downloadRuntime = downloadRuntime,
        // Whether the Kotlin framework itself was linked debug. Taken from the
        // binary rather than plumbed down from Swift so the host cannot pass
        // the wrong answer, and so a release framework has no way to say yes.
        diagnosticsEnabled = Platform.isDebugBinary,
        oidcSessionPort = IosOidcSessionPort(authHost),
        playerPort = IosPlayerHostAdapter(playerHost),
        playerSystemPort = IosPlayerSystemPort(playerSystemHost),
        videoFrameSource = IosVideoFrameSource,
        playerEvents = playerEventBridge.events,
        authEvents = authEventBridge.events,
        nativePlayerSurface = IosNativePlayerSurface(playerHost),
        nativeHostDiagnostics = IosNativeHostDiagnostics(authHost, playerHost),
        initialServerUrl = initialServerUrl,
        mediaHttpBase = mediaHttpBase,
        mediaLocalBase = mediaLocalBase,
        resetPersistedSession = resetPersistedSession,
    )
    return ComposeUIViewController {
        HaloApp(dependencies = dependencies)
    }
}
