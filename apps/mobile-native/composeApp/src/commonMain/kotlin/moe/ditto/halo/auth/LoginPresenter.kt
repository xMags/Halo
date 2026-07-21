package moe.ditto.halo.auth

import kotlinx.coroutines.CancellationException

sealed interface LoginPhase {
    data object Server : LoginPhase
    data object Discovering : LoginPhase
    data class LocalCredentials(val serverUrl: String) : LoginPhase

    /** Local credentials handed to the session controller; awaiting the login response. */
    data class LocalSubmitting(val serverUrl: String) : LoginPhase
    data class LocalSignedIn(val serverUrl: String) : LoginPhase

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
    val isBusy: Boolean = phase == LoginPhase.Discovering || phase is LoginPhase.LocalSubmitting
    val showsCredentials: Boolean = phase is LoginPhase.LocalCredentials || phase is LoginPhase.LocalSubmitting
    val canContinue: Boolean = serverUrl.isNotBlank() && !isBusy
    val canSubmitCredentials: Boolean =
        phase is LoginPhase.LocalCredentials && username.isNotBlank() && password.isNotBlank()
}

class LoginPresenter(
    private val authConfigSource: AuthConfigSource,
    private val nativeHostRequests: NativeHostRequests,
    private val localAuthenticator: LocalAuthenticator,
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
     * Exchanges the entered credentials for a persisted session. Failure keeps
     * the form intact so the user can correct and resubmit: a server rejection
     * surfaces the server's own message ("invalid credentials", the rate-limit
     * notice), while a transport failure reads as a connection problem.
     */
    suspend fun submitLocalCredentials() {
        val phase = state.phase as? LoginPhase.LocalCredentials ?: return
        if (!state.canSubmitCredentials) return

        // Identity (not equality) so a late outcome is dropped if the user
        // edited the server URL mid-flight and reset the form — the same rule
        // onAuthEvent applies to late OIDC events.
        val submitting = LoginPhase.LocalSubmitting(phase.serverUrl)
        state = state.copy(phase = submitting, error = null)
        val failure = try {
            localAuthenticator.signIn(phase.serverUrl, state.username.trim(), state.password)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
        if (state.phase !== submitting) return
        state = when {
            failure == null -> state.copy(phase = LoginPhase.LocalSignedIn(phase.serverUrl), password = "")
            failure is LocalAuthException -> state.copy(phase = phase, error = failure.message)
            else -> state.copy(phase = phase, error = failure.message ?: "Could not connect")
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
