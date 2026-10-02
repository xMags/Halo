package moe.ditto.halo.auth

import kotlinx.serialization.json.JsonObject

/** A sign-in add-on that signs straight in, or fails, and records what it was asked. */
class FakeSignInAddon(
    override val mode: String = "example",
    var token: String = "addon-session",
    var extras: Map<String, String> = emptyMap(),
    var failure: Throwable? = null,
) : SignInAddon {
    override val description: String = "This server uses Example accounts."
    override val buttonLabel: String = "Sign in with Example"
    override val waitingText: String = "Finish signing in with Example in your browser."

    data class SignIn(val serverUrl: String, val config: JsonObject, val browser: BrowserSignInPort)

    val signIns = mutableListOf<SignIn>()
    val signOuts = mutableListOf<AddonSession>()

    override suspend fun signIn(serverUrl: String, config: JsonObject, browser: BrowserSignInPort): AddonSignedIn {
        signIns += SignIn(serverUrl, config, browser)
        failure?.let { throw it }
        return AddonSignedIn(token, extras)
    }

    override suspend fun signOut(session: AddonSession) {
        signOuts += session
    }
}

/** Records every post-401 check and answers with [answer]. */
class FakeSessionRecheck(
    var answer: suspend () -> SessionRecheck = { SessionRecheck.Alive },
) : HaloSessionRecheck {
    val checks = mutableListOf<Pair<String, String>>()

    override suspend fun recheck(serverUrl: String, token: String): SessionRecheck {
        checks += serverUrl to token
        return answer()
    }
}
