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

    /** The server's mode is a sign-in add-on's; waiting for the user to start its sign-in. */
    data class AddonReady(val serverUrl: String, val config: AuthConfig.Addon) : LoginPhase

    /** The add-on's sign-in is running, its browser step included. */
    data class AddonSigningIn(val serverUrl: String, val config: AuthConfig.Addon) : LoginPhase
    data class AddonSignedIn(val serverUrl: String) : LoginPhase
}

data class LoginState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val phase: LoginPhase = LoginPhase.Server,
    val error: String? = null,
) {
    val isBusy: Boolean =
        phase == LoginPhase.Discovering || phase is LoginPhase.LocalSubmitting || phase is LoginPhase.AddonSigningIn
    val showsCredentials: Boolean = phase is LoginPhase.LocalCredentials || phase is LoginPhase.LocalSubmitting
    val canContinue: Boolean = serverUrl.isNotBlank() && !isBusy
    val canSubmitCredentials: Boolean =
        phase is LoginPhase.LocalCredentials && username.isNotBlank() && password.isNotBlank()
}

data class LoginCredentialsPrefill(
    val serverUrl: String,
    val username: String,
    val password: String,
)

class LoginPresenter(
    private val authConfigSource: AuthConfigSource,
    private val nativeHostRequests: NativeHostRequests,
    private val localAuthenticator: LocalAuthenticator,
    private val localCredentialsPrefill: LoginCredentialsPrefill? = null,
    private val addonAuthenticator: AddonAuthenticator = AddonAuthenticator { _, _ ->
        throw SignInAddonException("This device cannot sign in this way")
    },
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
                val matchingPrefill = localCredentialsPrefill?.takeIf {
                    it.serverUrl.trim().trimEnd('/') == normalizedUrl
                }
                state = state.copy(
                    phase = LoginPhase.LocalCredentials(normalizedUrl),
                    username = matchingPrefill?.username.orEmpty(),
                    password = matchingPrefill?.password.orEmpty(),
                )
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
            is AuthConfig.Addon -> {
                // Unlike OIDC the browser does not open by itself: the user
                // starts it, so the screen can first say which account opens.
                state = state.copy(phase = LoginPhase.AddonReady(normalizedUrl, config))
            }
        }
    }

    /**
     * Starts the add-on sign-in. Returns the in-flight phase for the caller to
     * render before awaiting [completeAddon] with it; null when there is
     * nothing to start.
     */
    fun beginAddon(): LoginPhase.AddonSigningIn? {
        val ready = state.phase as? LoginPhase.AddonReady ?: return null
        val signingIn = LoginPhase.AddonSigningIn(ready.serverUrl, ready.config)
        state = state.copy(phase = signingIn, error = null)
        return signingIn
    }

    /**
     * Runs the sign-in [beginAddon] started. A failure returns to
     * [LoginPhase.AddonReady] with its message; an outcome that lands after
     * the user cancelled, retried or edited the server URL is dropped by
     * identity, the same rule as the local form.
     */
    suspend fun completeAddon(attempt: LoginPhase.AddonSigningIn) {
        val failure = try {
            addonAuthenticator.signInWithAddon(attempt.serverUrl, attempt.config)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
        if (state.phase !== attempt) return
        state = when {
            failure == null -> state.copy(phase = LoginPhase.AddonSignedIn(attempt.serverUrl))
            failure is SignInAddonException -> state.copy(
                phase = LoginPhase.AddonReady(attempt.serverUrl, attempt.config),
                error = failure.message,
            )
            else -> state.copy(
                phase = LoginPhase.AddonReady(attempt.serverUrl, attempt.config),
                error = failure.message ?: "Could not reach the sign-in service",
            )
        }
    }

    /** Abandons an in-flight add-on sign-in, for when the browser was closed without finishing. */
    fun cancelAddon() {
        val attempt = state.phase as? LoginPhase.AddonSigningIn ?: return
        state = state.copy(phase = LoginPhase.AddonReady(attempt.serverUrl, attempt.config), error = null)
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
            // Session lifecycle, not sign-in outcome — SessionController owns it.
            AuthEvent.OidcSessionInvalidated -> state
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
