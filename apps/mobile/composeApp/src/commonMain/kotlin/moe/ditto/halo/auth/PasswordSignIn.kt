package moe.ditto.halo.auth

/** How a username and password sign-in against a typed server address ended. */
internal sealed interface PasswordSignInOutcome {
    data object SignedIn : PasswordSignInOutcome

    /** The server could not be reached or refused the credentials; [message] says which. */
    data class Failed(val message: String) : PasswordSignInOutcome

    /** The server signs in some other way (a web page or an add-on), not with a password. */
    data object NotPasswordServer : PasswordSignInOutcome
}

/**
 * Asks [serverUrl] how it signs in and, when it takes a username and password,
 * signs in with them. The presenter is reset to [serverUrl] first, so a form
 * can call this again after a failure. The session itself lands in the
 * presenter's authenticator, as every other sign-in does.
 */
internal suspend fun LoginPresenter.signInWithPassword(
    serverUrl: String,
    username: String,
    password: String,
): PasswordSignInOutcome {
    editServerUrl(serverUrl)
    continueFromServer(startOidc = false)
    return when (state.phase) {
        is LoginPhase.LocalCredentials -> {
            editUsername(username)
            editPassword(password)
            submitLocalCredentials()
            if (state.phase is LoginPhase.LocalSignedIn) {
                PasswordSignInOutcome.SignedIn
            } else {
                PasswordSignInOutcome.Failed(state.error ?: "Could not sign in.")
            }
        }
        LoginPhase.Server -> PasswordSignInOutcome.Failed(state.error ?: "Could not reach that server.")
        else -> PasswordSignInOutcome.NotPasswordServer
    }
}
