package moe.ditto.halo.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SessionKind { Local, Oidc }

sealed interface SessionState {
    /** Persisted-session lookup has not completed; render nothing auth-dependent yet. */
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val kind: SessionKind, val serverUrl: String) : SessionState
}

/** The login form's local-mode submit target; throws on failure, [LocalAuthException] for server rejections. */
fun interface LocalAuthenticator {
    suspend fun signIn(serverUrl: String, username: String, password: String)
}

/**
 * App-level session authority: restore on launch, sign-in, sign-out, and the
 * [TokenProvider] the API client draws bearer tokens from.
 *
 * Currently local-mode only. The OIDC session lives with the native OIDC host
 * (the token wire format is owned there); it joins this controller through its
 * own provider once the host grows persistence and refresh.
 */
class SessionController(
    private val storage: SecureStorage,
    private val gateway: LocalAuthGateway,
    clock: EpochClock,
    scope: CoroutineScope,
) : LocalAuthenticator {

    val localSessions = LocalSessionManager(
        storage = storage,
        gateway = gateway,
        clock = clock,
        scope = scope,
        onSessionInvalidated = { _state.value = SessionState.SignedOut },
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state

    val tokenProvider: TokenProvider get() = localSessions

    /**
     * Restore reads only device storage — a persisted session boots straight
     * to signed-in with no network, so an offline launch still reaches the
     * app. Token validity is tested lazily by the first API call.
     */
    fun restore() {
        val local = localSessions.restore()
        _state.value = when (local) {
            null -> SessionState.SignedOut
            else -> SessionState.SignedIn(SessionKind.Local, local.serverUrl)
        }
    }

    override suspend fun signIn(serverUrl: String, username: String, password: String) {
        val issued = gateway.login(serverUrl, username, password)
        localSessions.establish(LocalSessionData(serverUrl = serverUrl, token = issued.token, expiresAt = issued.expiresAt))
        // Survives sign-out on purpose: the login form prefills the last server.
        storage.write(AuthStorageKeys.ServerUrl, serverUrl)
        _state.value = SessionState.SignedIn(SessionKind.Local, serverUrl)
    }

    fun signOut() {
        localSessions.clear()
        _state.value = SessionState.SignedOut
    }

    /** Last server a sign-in succeeded against; prefill only, never trusted as a session. */
    fun storedServerUrl(): String? = storage.read(AuthStorageKeys.ServerUrl)
}
