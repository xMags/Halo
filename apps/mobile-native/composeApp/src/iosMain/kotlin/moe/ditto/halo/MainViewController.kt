package moe.ditto.halo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(
    authHost: HaloIosAuthHost,
    playerHost: HaloIosPlayerHost,
    initialServerUrl: String,
    mediaHttpBase: String,
    mediaLocalBase: String,
): UIViewController {
    val authAdapter = IosAuthHostAdapter(authHost)
    val playerEventBridge = IosPlayerEventBridge()
    playerHost.setEventSink(playerEventBridge)
    val authEventBridge = IosAuthEventBridge()
    authHost.setAuthEventSink(authEventBridge)
    val dependencies = PlatformDependencies(
        authConfigSource = authAdapter,
        nativeHostRequests = authAdapter,
        playerPort = IosPlayerHostAdapter(playerHost),
        playerEvents = playerEventBridge.events,
        authEvents = authEventBridge.events,
        nativePlayerSurface = IosNativePlayerSurface(playerHost),
        nativeHostDiagnostics = IosNativeHostDiagnostics(authHost, playerHost),
        initialServerUrl = initialServerUrl,
        mediaHttpBase = mediaHttpBase,
        mediaLocalBase = mediaLocalBase,
    )
    return ComposeUIViewController {
        HaloGateApp(dependencies = dependencies)
    }
}
