package moe.ditto.halo.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import moe.ditto.halo.PlatformDependencies
import moe.ditto.halo.auth.builtInServerUrl
import moe.ditto.halo.rememberAppSession
import moe.ditto.halo.tv.signin.TvSignInScreen
import moe.ditto.halo.tv.kit.LocalTvFocusTracker
import moe.ditto.halo.tv.kit.TvFocusTracker
import moe.ditto.halo.tv.kit.TvKitGallery
import moe.ditto.halo.tv.player.TvAudioOutput
import moe.ditto.halo.tv.player.TvPlaybackPreferences
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloTheme
import moe.ditto.halo.ui.haloImageLoader

private enum class TvScreen { SignIn, Shell }

/** The TV app's root: the shared session root under TV screens. */
@Composable
internal fun TvApp(
    dependencies: PlatformDependencies,
    /** The TV's sound output, for surround passthrough. */
    audioOutput: TvAudioOutput,
    /** Closes the app; the TV root confirms first. */
    onExitApp: () -> Unit,
    /** Debug builds only: opens the TV kit gallery instead of the app. */
    showKitGallery: Boolean = false,
) {
    // Installed above every screen: Coil resolves the singleton loader lazily
    // on the first request, so this only has to run before any art is composed.
    setSingletonImageLoaderFactory { context ->
        haloImageLoader(context, dependencies.imageCacheDirectory)
    }
    val focusTracker = remember { TvFocusTracker() }
    HaloTheme {
        CompositionLocalProvider(LocalTvFocusTracker provides focusTracker) {
            if (showKitGallery) TvKitGallery() else TvRoot(dependencies, audioOutput, onExitApp)
        }
    }
}

@Composable
private fun TvRoot(dependencies: PlatformDependencies, audioOutput: TvAudioOutput, onExitApp: () -> Unit) {
    var screen by remember { mutableStateOf(TvScreen.SignIn) }
    // The device's, not the account's: it outlives every session.
    val playbackPreferences = remember { TvPlaybackPreferences(dependencies.keyValueStore) }
    val session = rememberAppSession(
        dependencies = dependencies,
        isShowingSignIn = { screen == TvScreen.SignIn },
        onEnterApp = { screen = TvScreen.Shell },
        onLeaveApp = { screen = TvScreen.SignIn },
    )
    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        if (!session.restored) return@Box
        when (screen) {
            TvScreen.SignIn -> {
                val controller = session.sessionController
                val serverUrl = remember(controller) {
                    dependencies.initialServerUrl.takeIf { it != PlatformDependencies.DefaultServerUrl }
                        ?: controller.storedServerUrl()
                        ?: builtInServerUrl
                        ?: ""
                }
                TvSignInScreen(
                    dependencies = dependencies,
                    localAuthenticator = controller,
                    serverUrl = serverUrl,
                    notice = session.loginNotice,
                    onDeviceSession = controller::establishDeviceSession,
                    onContinueWithoutAccount = { controller.continueWithoutAccount() },
                )
            }
            // Null for the frame between a sign-out landing in session state
            // and the collector moving the screen to sign-in.
            TvScreen.Shell -> session.graph?.let { graph ->
                TvShell(
                    graph = graph,
                    playback = session.playback,
                    playerSurface = dependencies.nativePlayerSurface,
                    playerSystem = dependencies.playerSystemPort,
                    playbackPreferences = playbackPreferences,
                    audioOutput = audioOutput,
                    onSignOut = { session.sessionController.signOut() },
                    onSignIn = { session.sessionController.startSignIn() },
                    onExitApp = onExitApp,
                )
            }
        }
    }
}
