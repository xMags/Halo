package moe.ditto.halo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.KtorHaloSessionRecheck
import moe.ditto.halo.auth.KtorLocalAuthGateway
import moe.ditto.halo.auth.SessionController
import moe.ditto.halo.auth.SessionState
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.auth.installedSignInAddons
import moe.ditto.halo.device.PlatformDeviceStore
import moe.ditto.halo.people.TmdbKeys
import moe.ditto.halo.storage.StorageKeys

/**
 * Everything an app root needs below its screens: the player host, the
 * session controller and the per-session [SessionGraph]. The phone root
 * ([HaloApp]) and the TV root both build their screens over one of these, so
 * the session and sign-out rules exist once.
 */
internal class AppSession(
    val playback: PlaybackHost,
    val sessionController: SessionController,
    /**
     * Auth events re-fanned for a sign-in screen. The channel behind
     * [PlatformDependencies.authEvents] has a single consumer, which is the
     * session root itself.
     */
    val loginAuthEvents: SharedFlow<AuthEvent>,
    restored: State<Boolean>,
    sessionState: State<SessionState>,
    loginNotice: State<String?>,
    graph: State<SessionGraph?>,
) {
    /** False until a persisted session has been restored (or found absent). */
    val restored: Boolean by restored
    val sessionState: SessionState by sessionState
    val loginNotice: String? by loginNotice

    /** Null while signed out or restoring; one graph per session otherwise. */
    val graph: SessionGraph? by graph
}

/**
 * Builds the [AppSession] for [dependencies] and runs its app-lifetime
 * effects. The root tells it where it is through [isShowingSignIn] and moves
 * between its sign-in and in-app screens when [onEnterApp] and [onLeaveApp]
 * fire.
 *
 * Session restore gates the first render (a persisted session must boot to the
 * app, not flash the sign-in form), then the state collector owns both
 * directions: into the app on sign-in, restore or continuing without an
 * account, and out to sign-in on a live sign-out (the button, or
 * invalid_grant from a background refresh) or on leaving Halo without an
 * account to sign in. The leave fires only on a transition out of the app,
 * so the fake-host diagnostics suites, which walk to the gate with no
 * session at all, never bounce.
 */
@Composable
internal fun rememberAppSession(
    dependencies: PlatformDependencies,
    isShowingSignIn: () -> Boolean,
    onEnterApp: () -> Unit,
    onLeaveApp: () -> Unit,
): AppSession {
    val playback = remember(dependencies) { PlaybackHost(dependencies.playerPort) }
    val deviceStore = remember(dependencies) {
        PlatformDeviceStore(dependencies.secureStorage, dependencies.keyValueStore)
    }
    val tmdbKeys = remember(dependencies) { TmdbKeys(dependencies.secureStorage) }
    val sessionController = remember(dependencies) {
        val authHttpClient = HttpClient()
        SessionController(
            storage = dependencies.secureStorage,
            gateway = KtorLocalAuthGateway(authHttpClient),
            clock = SystemEpochClock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            oidcPort = dependencies.oidcSessionPort,
            addons = installedSignInAddons,
            browserSignIn = dependencies.browserSignIn,
            sessionRecheck = KtorHaloSessionRecheck(authHttpClient),
            deviceProfile = deviceStore,
            // The mirror stands in for the server while it cannot be
            // reached; another account's copy must never be sent as this one's.
            onSessionChanged = { dependencies.keyValueStore.delete(StorageKeys.SettingsMirror) },
        )
    }
    val restored = remember { mutableStateOf(false) }
    val showingSignIn by rememberUpdatedState(isShowingSignIn)
    val enterApp by rememberUpdatedState(onEnterApp)
    val leaveApp by rememberUpdatedState(onLeaveApp)

    LaunchedEffect(sessionController) {
        if (dependencies.resetPersistedSession) sessionController.resetPersistedSessions()
        sessionController.restore()
        restored.value = true
        var previous: SessionState? = null
        sessionController.state.collect { state ->
            val inApp = state is SessionState.SignedIn || state == SessionState.Device
            if (inApp && showingSignIn()) enterApp()
            if (state == SessionState.SignedOut &&
                previous is SessionState.SignedIn &&
                !showingSignIn()
            ) {
                // A rejected token and the visible sign-out button arrive
                // through this same transition. Both durably pause queued
                // and OS-owned work before the outgoing graph disappears.
                dependencies.downloadRuntime.pauseAllForSignOut()
                leaveApp()
            }
            // Going to sign in from Halo without an account is the same
            // person, so their downloads carry on.
            if (state == SessionState.SignedOut &&
                previous == SessionState.Device &&
                !showingSignIn()
            ) {
                leaveApp()
            }
            previous = state
        }
    }

    // Auth events fan out from here for the app's whole lifetime: the
    // channel behind dependencies.authEvents is single-consumer, but the
    // session controller must see lifecycle events even when the sign-in
    // screen is not composed (an invalid_grant can arrive on any screen).
    // The sign-in screen re-collects through this shared flow instead.
    val loginAuthEvents = remember(dependencies) { MutableSharedFlow<AuthEvent>(extraBufferCapacity = 16) }
    LaunchedEffect(sessionController) {
        dependencies.authEvents.collect { event ->
            sessionController.onAuthEvent(event)
            loginAuthEvents.emit(event)
        }
    }

    // Native playback events feed the presenter for the whole app lifetime,
    // not just while a player screen is composed; otherwise events that
    // arrive elsewhere would only apply after re-entry.
    LaunchedEffect(playback) {
        dependencies.playerEvents.collect { event -> playback.onEvent(event) }
    }

    val sessionState = sessionController.state.collectAsState()
    val loginNotice = sessionController.loginNotice.collectAsState()

    // One graph per session. Keyed on the generation as well as the state
    // because session state alone cannot distinguish two users on one
    // server; see SessionController.sessionGeneration. Signing out drops
    // it to null, which closes the outgoing one.
    val sessionGeneration by sessionController.sessionGeneration.collectAsState()
    val currentState = sessionState.value
    val graphValue = remember(sessionGeneration, currentState) {
        // Copied out of the state: the delegate would be read again each
        // time the callback runs, so an older session's client would name
        // whichever session is current by then and end that one instead.
        val issuedFor = sessionGeneration
        val mode = when (currentState) {
            is SessionState.SignedIn -> SessionMode.Account(
                serverUrl = currentState.serverUrl,
                tokens = sessionController.tokenProvider,
                onUnauthorized = { sessionController.rejectSession(issuedFor) },
            )
            SessionState.Device -> SessionMode.Device
            SessionState.SignedOut, SessionState.Restoring -> null
        }
        mode?.let {
            SessionGraph(
                mode = it,
                deviceStore = deviceStore,
                keyValueStore = dependencies.keyValueStore,
                subtitleCacheDirectory = dependencies.subtitleCacheDirectory,
                downloadRuntime = dependencies.downloadRuntime,
                downloadStorage = dependencies.downloadStorage,
                tmdbKeys = tmdbKeys,
            )
        }
    }
    DisposableEffect(graphValue) {
        onDispose { graphValue?.close() }
    }
    val graph = rememberUpdatedState(graphValue)

    return remember(playback, sessionController, loginAuthEvents) {
        AppSession(
            playback = playback,
            sessionController = sessionController,
            loginAuthEvents = loginAuthEvents,
            restored = restored,
            sessionState = sessionState,
            loginNotice = loginNotice,
            graph = graph,
        )
    }
}
