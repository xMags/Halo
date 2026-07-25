package moe.ditto.halo

import androidx.compose.ui.window.ComposeUIViewController
import moe.ditto.halo.auth.IosKeychainStorage
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

fun MainViewController(
    authHost: HaloIosAuthHost,
    playerHost: HaloIosPlayerHost,
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
    val dependencies = PlatformDependencies(
        authConfigSource = authAdapter,
        nativeHostRequests = authAdapter,
        secureStorage = IosKeychainStorage(),
        keyValueStore = IosUserDefaultsStore(),
        imageCacheDirectory = imageCacheDirectory(),
        oidcSessionPort = IosOidcSessionPort(authHost),
        playerPort = IosPlayerHostAdapter(playerHost),
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
        HaloGateApp(dependencies = dependencies)
    }
}
