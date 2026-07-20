package moe.ditto.halo.auth

import kotlinx.coroutines.CancellationException

sealed interface LoginPhase {
    data object Server : LoginPhase
    data object Discovering : LoginPhase
    data class LocalCredentials(val serverUrl: String) : LoginPhase

    /** Browser + token exchange handed to the native host; awaiting an [AuthEvent]. */
    data class OidcRequested(val request: OidcHostRequest) : LoginPhase
    data class OidcSucceeded(val request: OidcHostRequest, val tokenProof: String) : LoginPhase
    data class OidcFailed(val request: OidcHostRequest, val reason: String) : LoginPhase
}

data class LoginState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val phase: LoginPhase = LoginPhase.Server,
    val error: String? = null,
) {
    val isBusy: Boolean = phase == LoginPhase.Discovering
    val showsCredentials: Boolean = phase is LoginPhase.LocalCredentials
    val canContinue: Boolean = serverUrl.isNotBlank() && !isBusy
}

class LoginPresenter(
    private val authConfigSource: AuthConfigSource,
    private val nativeHostRequests: NativeHostRequests,
) {
    var state: LoginState = LoginState()
        private set

    fun editServerUrl(value: String) {
        state = LoginState(serverUrl = value)
    }

    fun editUsername(value: String) {
        if (state.phase !is LoginPhase.LocalCredentials) return
        state = state.copy(username = value, error = null)
    }

    fun editPassword(value: String) {
        if (state.phase !is LoginPhase.LocalCredentials) return
        state = state.copy(password = value, error = null)
    }

    suspend fun continueFromServer() {
        if (state.phase != LoginPhase.Server || state.serverUrl.isBlank()) return

        val normalizedUrl = state.serverUrl.trim().trimEnd('/')
        state = state.copy(serverUrl = normalizedUrl, phase = LoginPhase.Discovering, error = null)
        val config = try {
            authConfigSource.fetch(normalizedUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            state = state.copy(
                phase = LoginPhase.Server,
                error = error.message ?: "Could not connect",
            )
            return
        }

        when (config) {
            AuthConfig.Local -> {
                state = state.copy(phase = LoginPhase.LocalCredentials(normalizedUrl))
            }
            is AuthConfig.Oidc -> {
                val request = OidcHostRequest(
                    serverUrl = normalizedUrl,
                    issuer = config.issuer,
                    clientId = config.clientId,
                    scopes = config.scopes,
                )
                startOidc(request)
            }
        }
    }

    /**
     * Folds a native OIDC outcome into the state machine. Only honoured while a
     * request is in flight ([LoginPhase.OidcRequested]); a late event that lands
     * after the user edited the server URL or reached a terminal phase is
     * dropped so it cannot clobber a reset form.
     */
    fun onAuthEvent(event: AuthEvent) {
        val request = (state.phase as? LoginPhase.OidcRequested)?.request ?: return
        state = when (event) {
            is AuthEvent.OidcSucceeded ->
                state.copy(phase = LoginPhase.OidcSucceeded(request, event.tokenProof))
            is AuthEvent.OidcFailed ->
                state.copy(phase = LoginPhase.OidcFailed(request, event.reason))
        }
    }

    /** Re-runs the browser flow after a failure without re-discovering config. */
    fun retryOidc() {
        val request = (state.phase as? LoginPhase.OidcFailed)?.request ?: return
        startOidc(request)
    }

    private fun startOidc(request: OidcHostRequest) {
        state = state.copy(phase = LoginPhase.OidcRequested(request), error = null)
        nativeHostRequests.requestOidc(request)
    }
}
