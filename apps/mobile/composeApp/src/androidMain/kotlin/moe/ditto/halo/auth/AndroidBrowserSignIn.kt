package moe.ditto.halo.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's browser step for a sign-in add-on: a Custom Tab, so the browser's
 * own session with the provider carries over, with the callback arriving at
 * [MainActivity] as an intent. The manifest routes the add-on's callback path
 * there (`signInAddonCallbackPath`, see build.gradle.kts).
 *
 * The rest of the sign-in (PKCE, state, code exchange) lives in common code and
 * only in memory, so a sign-in the system kills the app during simply has to
 * be started again.
 */
internal class AndroidBrowserSignIn(
    private val activity: Activity,
) : BrowserSignInPort {
    override val platform: String = "android"

    private val lock = Any()
    private var pending: CompletableDeferred<String>? = null

    /** The callback URL the latest sign-in asked for; claimed even once nothing waits on it. */
    private var callbackUrl: String? = null

    override suspend fun authorize(url: String, callbackUrl: String): String {
        val waiter = CompletableDeferred<String>()
        synchronized(lock) {
            pending?.completeExceptionally(SignInAddonException("A newer sign-in replaced this one"))
            pending = waiter
            this.callbackUrl = callbackUrl
        }
        try {
            withContext(Dispatchers.Main) { open(url) }
            return waiter.await()
        } finally {
            synchronized(lock) { if (pending === waiter) pending = null }
        }
    }

    /**
     * Claims an add-on callback intent. True for any URL under the latest
     * sign-in's callback, waited for or not, so a stale one never reaches the
     * OIDC host.
     */
    fun handleIntent(intent: Intent?): Boolean {
        val received = intent?.dataString ?: return false
        synchronized(lock) {
            val expected = callbackUrl ?: return false
            if (received != expected && !received.startsWith("$expected?")) return false
            pending?.complete(received)
        }
        return true
    }

    private fun open(url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                throw SignInAddonException("No browser is installed for sign-in")
            }
        }
    }
}
