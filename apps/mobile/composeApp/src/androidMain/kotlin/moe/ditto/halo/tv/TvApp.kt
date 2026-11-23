package moe.ditto.halo.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import moe.ditto.halo.PlatformDependencies
import moe.ditto.halo.rememberAppSession
import moe.ditto.halo.tv.kit.LocalTvFocusTracker
import moe.ditto.halo.tv.kit.TvFocusTracker
import moe.ditto.halo.tv.kit.TvKitGallery
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloTheme
import moe.ditto.halo.ui.haloImageLoader

private enum class TvScreen { SignIn, Shell }

/** The TV app's root: the shared session root under TV screens. */
@Composable
internal fun TvApp(
    dependencies: PlatformDependencies,
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
            if (showKitGallery) TvKitGallery() else TvRoot(dependencies)
        }
    }
}

@Composable
private fun TvRoot(dependencies: PlatformDependencies) {
    var screen by remember { mutableStateOf(TvScreen.SignIn) }
    val session = rememberAppSession(
        dependencies = dependencies,
        isShowingSignIn = { screen == TvScreen.SignIn },
        onEnterApp = { screen = TvScreen.Shell },
        onLeaveApp = { screen = TvScreen.SignIn },
    )
    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        if (!session.restored) return@Box
        when (screen) {
            TvScreen.SignIn -> Text("TV sign-in", color = HaloColors.Text, modifier = Modifier.align(Alignment.Center))
            TvScreen.Shell -> Text("TV shell", color = HaloColors.Text, modifier = Modifier.align(Alignment.Center))
        }
    }
}
