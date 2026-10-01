package moe.ditto.halo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.ditto.halo.auth.AndroidBeginLoginResult
import moe.ditto.halo.auth.AndroidClaimCallbackResult
import moe.ditto.halo.auth.AndroidCompleteLoginResult
import moe.ditto.halo.auth.AndroidOidcLoginManager
import moe.ditto.halo.auth.AndroidOidcProtocol
import moe.ditto.halo.auth.AndroidOidcSessionManager
import moe.ditto.halo.auth.AndroidOidcWire
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.OidcHostRequest
import moe.ditto.halo.auth.OidcSessionPort
import moe.ditto.halo.auth.SecureStorage

/**
 * Android's native OIDC owner. The browser sees credentials, while the app
 * owns PKCE, encrypted token persistence, refresh, and callback validation.
 */
internal class AndroidOidcAuthHost(
    private val activity: Activity,
    storage: SecureStorage,
    private val wire: AndroidOidcWire,
) : NativeHostRequests, OidcSessionPort, AutoCloseable {
    val hostId: String = UUID.randomUUID().toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventChannel = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = eventChannel.receiveAsFlow()

    private val sessions = AndroidOidcSessionManager(
        storage = storage,
        wire = wire,
        scope = scope,
        onInvalidated = { eventChannel.trySend(AuthEvent.OidcSessionInvalidated) },
    )
    private val logins = AndroidOidcLoginManager(storage, wire, sessions)
    private var loginJob: Job? = null
    private var destroyed = false

    var oidcRequestCount: Long = 0L
        private set

    override fun requestOidc(request: OidcHostRequest) {
        oidcRequestCount += 1
        val attempt = logins.reserveAttempt()
        loginJob?.cancel()
        loginJob = scope.launch {
            when (val result = logins.begin(attempt, request)) {
                is AndroidBeginLoginResult.OpenBrowser -> activity.runOnUiThread {
                    if (!logins.isCurrent(result.attempt)) return@runOnUiThread
                    try {
                        CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                            .build()
                            .launchUrl(activity, Uri.parse(result.url))
                    } catch (_: ActivityNotFoundException) {
                        openSystemBrowser(result.attempt, result.url)
                    } catch (error: RuntimeException) {
                        failCurrentAttempt(result.attempt, "Could not open the sign-in browser: ${error.message ?: "unknown error"}")
                    }
                }
                is AndroidBeginLoginResult.Failure -> eventChannel.send(AuthEvent.OidcFailed(result.reason))
                AndroidBeginLoginResult.Superseded -> Unit
            }
        }
    }

    fun handleIntent(intent: Intent?): Boolean {
        val callbackUrl = intent?.dataString ?: return false
        return when (val result = logins.claimCallback(callbackUrl)) {
            AndroidClaimCallbackResult.NotCallback -> false
            AndroidClaimCallbackResult.Ignored -> true
            is AndroidClaimCallbackResult.Failure -> {
                eventChannel.trySend(AuthEvent.OidcFailed(result.reason))
                true
            }
            is AndroidClaimCallbackResult.Exchange -> {
                loginJob?.cancel()
                loginJob = scope.launch {
                    when (val completed = logins.exchange(result.claim)) {
                        is AndroidCompleteLoginResult.Success -> eventChannel.send(
                            AuthEvent.OidcSucceeded(completed.serverUrl, completed.accessToken),
                        )
                        is AndroidCompleteLoginResult.Failure -> eventChannel.send(
                            AuthEvent.OidcFailed(completed.reason),
                        )
                        AndroidCompleteLoginResult.Superseded -> Unit
                    }
                }
                true
            }
        }
    }

    override fun restoreSession(): String? = sessions.restoreSession()

    override suspend fun accessToken(forceRefresh: Boolean): String? =
        sessions.accessToken(forceRefresh)

    override suspend fun signOut(endIdpSession: Boolean) {
        loginJob?.cancel()
        logins.supersedeSilently()
        val signedOut = sessions.clearAndTake() ?: return

        signedOut.revocationEndpoint?.let { endpoint ->
            scope.launch {
                runCatching {
                    wire.revoke(
                        endpoint,
                        AndroidOidcProtocol.revokeForm(signedOut.clientId, signedOut.refreshToken),
                    )
                }
            }
        }
        if (endIdpSession) openEndSession(signedOut.endSessionEndpoint, signedOut.idToken)
    }

    override fun close() {
        destroyed = true
        loginJob?.cancel()
        eventChannel.close()
        scope.cancel()
    }

    private fun openSystemBrowser(attempt: Long, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {
            failCurrentAttempt(attempt, "No browser is installed for sign-in")
        }
    }

    private fun failCurrentAttempt(attempt: Long, reason: String) {
        if (logins.cancelAttempt(attempt)) eventChannel.trySend(AuthEvent.OidcFailed(reason))
    }

    private fun openEndSession(endpoint: String?, idToken: String?) {
        if (endpoint == null) return
        val url = if (idToken == null) {
            endpoint
        } else {
            AndroidOidcProtocol.appendQuery(
                endpoint,
                listOf(
                    "post_logout_redirect_uri" to AndroidOidcProtocol.LogoutRedirectUri,
                    "id_token_hint" to idToken,
                ),
            )
        }
        activity.runOnUiThread {
            if (destroyed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            runCatching {
                CustomTabsIntent.Builder()
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .build()
                    .launchUrl(activity, Uri.parse(url))
            }.recoverCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }
}
