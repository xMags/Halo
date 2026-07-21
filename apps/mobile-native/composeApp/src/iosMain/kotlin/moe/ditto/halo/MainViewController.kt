package moe.ditto.halo

import androidx.compose.ui.window.ComposeUIViewController
import moe.ditto.halo.auth.IosKeychainStorage
import platform.UIKit.UIViewController

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
