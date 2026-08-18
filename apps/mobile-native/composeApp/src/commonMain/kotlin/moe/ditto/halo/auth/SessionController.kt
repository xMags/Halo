package moe.ditto.halo.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Two arms, matching the server's deployment-exclusive auth modes. The local
 * arm lives entirely here ([LocalSessionManager]); the OIDC arm's wire and
 * persistence live with the native host behind [OidcSessionPort], and this
 * controller only folds its outcomes into state. Both arms share the
 * invariant: a session ends ONLY on the server's definitive rejection (local
 * refresh 401 / OIDC `invalid_grant`), never on a transport failure.
 */
class SessionController(
    private val storage: SecureStorage,
    private val gateway: LocalAuthGateway,
    clock: EpochClock,
    private val scope: CoroutineScope,
    private val oidcPort: OidcSessionPort = NoOidcSessionPort,
) : LocalAuthenticator {

    val localSessions = LocalSessionManager(
        storage = storage,
        gateway = gateway,
        clock = clock,
        scope = scope,
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state

    private val _sessionGeneration = MutableStateFlow(0)

    /**
     * Counts session writes, so "a different session is current" is observable
     * even when [state] cannot express it.
     *
     * [SessionState.SignedIn] is a data class in a [StateFlow], which conflates
     * equal values: signing in as a *different user on the same server* emits
     * nothing at all. Anything keyed on [state] alone would keep serving the
     * previous user's cached library to the new one. Consumers that own
     * per-session resources must key on this as well.
     */
    val sessionGeneration: StateFlow<Int> = _sessionGeneration

    private val _loginNotice = MutableStateFlow<String?>(null)
    val loginNotice: StateFlow<String?> = _loginNotice

    /** Concurrent 401s may all arrive together, but only one may end a session. */
    private val rejectionGuard = Mutex()

    /**
     * The one place session state is written. The counter moves first, so a
     * consumer reacting to [state] never observes a new session under the old
     * generation.
     */
    private fun publish(next: SessionState) {
        _sessionGeneration.value += 1
        _state.value = next
    }

    /**
     * Serves whichever arm is signed in. The dispatch happens per call, not at
     * construction, so a sign-out or mode switch mid-session can never leave a
     * caller holding the wrong arm's provider.
     */
    val tokenProvider: TokenProvider = object : TokenProvider {
        override suspend fun accessToken(): String? = when (currentKind()) {
            SessionKind.Local -> localSessions.accessToken()
            SessionKind.Oidc -> oidcPort.accessToken(forceRefresh = false)
            null -> null
        }

        override suspend fun refreshAccessToken(): String? = when (currentKind()) {
            SessionKind.Local -> localSessions.refreshAccessToken()
            SessionKind.Oidc -> oidcPort.accessToken(forceRefresh = true)
            null -> null
        }
    }

    /**
     * Restore reads only device storage — a persisted session boots straight
     * to signed-in with no network, so an offline launch still reaches the
     * app. Token validity is tested lazily by the first API call. The arms
     * are deployment-exclusive server-side, so at most one can hold a
     * persisted session; local is checked first only for determinism.
     */
    fun restore() {
        _loginNotice.value = null
        val local = localSessions.restore()
        if (local != null) {
            publish(SessionState.SignedIn(SessionKind.Local, local.serverUrl))
            return
        }
        val oidcServerUrl = oidcPort.restoreSession()
        publish(
            when (oidcServerUrl) {
                null -> SessionState.SignedOut
                else -> SessionState.SignedIn(SessionKind.Oidc, oidcServerUrl)
            },
        )
    }

    override suspend fun signIn(serverUrl: String, username: String, password: String) {
        val issued = gateway.login(serverUrl, username, password)
        localSessions.establish(LocalSessionData(serverUrl = serverUrl, token = issued.token, expiresAt = issued.expiresAt))
        // Survives sign-out on purpose: the login form prefills the last server.
        storage.write(AuthStorageKeys.ServerUrl, serverUrl)
        _loginNotice.value = null
        publish(SessionState.SignedIn(SessionKind.Local, serverUrl))
    }

    /**
     * Folds native-host auth outcomes into session state. Success means the
     * host already persisted the session — this only mirrors it into state
     * (and the server prefill). Invalidation is honoured only while an OIDC
     * session is current: a stale event from a superseded session must not
     * sign out whatever replaced it.
     */
    fun onAuthEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.OidcSucceeded -> {
                storage.write(AuthStorageKeys.ServerUrl, event.serverUrl)
                _loginNotice.value = null
                publish(SessionState.SignedIn(SessionKind.Oidc, event.serverUrl))
            }
            AuthEvent.OidcSessionInvalidated -> {
                if (currentKind() == SessionKind.Oidc) {
                    // The native port emits this only after it has cleared its
                    // persisted session. Publishing synchronously preserves
                    // event ordering with a later OIDC success on the same
                    // channel; a concurrent client callback then sees the
                    // advanced generation and becomes a no-op.
                    _loginNotice.value = RejectionNotice
                    publish(SessionState.SignedOut)
                }
            }
            is AuthEvent.OidcFailed -> Unit
        }
    }

    /**
     * Clears both arms unconditionally — this backs the explicit sign-out
     * button and the automation reset hatch, and neither may leave the other
     * arm restorable. The OIDC side goes through its port off-path (revoke is
     * network best-effort and must not block the state flip).
     */
    fun signOut() {
        localSessions.clear()
        scope.launch { runCatching { oidcPort.signOut(endIdpSession = true) } }
        _loginNotice.value = null
        publish(SessionState.SignedOut)
    }

    /**
     * Ends only the session that issued a definitively rejected request.
     *
     * The graph captures [expectedGeneration] when it is created. A callback
     * from that graph is stale as soon as sign-out or another sign-in advances
     * the generation, so it cannot clear the replacement session. The mutex
     * also folds a burst of concurrent 401s into one persisted clear and one
     * state transition. Persistence is cleared before SignedOut is published.
     */
    suspend fun rejectSession(expectedGeneration: Int) {
        rejectionGuard.withLock {
            if (expectedGeneration != _sessionGeneration.value) return
            val signedIn = _state.value as? SessionState.SignedIn ?: return
            when (signedIn.kind) {
                SessionKind.Local -> localSessions.clear()
                SessionKind.Oidc -> oidcPort.signOut(endIdpSession = false)
            }
            // OIDC clearing is suspendable. A newer session may have landed
            // while it completed, and its state must win.
            if (expectedGeneration != _sessionGeneration.value) return
            _loginNotice.value = RejectionNotice
            publish(SessionState.SignedOut)
        }
    }

    /**
     * The automation reset hatch's variant of [signOut]: awaits the OIDC
     * clear, because the hatch is immediately followed by [restore] and a
     * fire-and-forget clear could lose that race and resurrect the session
     * it was meant to wipe. (The native side keeps this fast: its storage
     * clear is synchronous and only the revoke request is fire-and-forget.)
     */
    suspend fun resetPersistedSessions() {
        localSessions.clear()
        runCatching { oidcPort.signOut(endIdpSession = false) }
        _loginNotice.value = null
        publish(SessionState.SignedOut)
    }

    /** Last server a sign-in succeeded against; prefill only, never trusted as a session. */
    fun storedServerUrl(): String? = storage.read(AuthStorageKeys.ServerUrl)

    private fun currentKind(): SessionKind? = (_state.value as? SessionState.SignedIn)?.kind

    companion object {
        const val RejectionNotice = "Your session is no longer valid. Sign in again."
    }
}
