package moe.ditto.halo.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A sign-in method a local build adds for a server auth mode the app does not
 * know itself (it knows OIDC and local accounts).
 *
 * A public build installs none: [installedSignInAddons] comes from
 * `src/signInAddons/kotlin`, an empty list. A local build replaces that source
 * folder with the git-ignored `local/mobile` folder at the repository root,
 * which declares its own list (see build.gradle.kts).
 *
 * An add-on runs its own sign-in, browser step included, and hands back a
 * bearer token for the Halo server. The app owns the session from there:
 * persistence, the post-401 check ([AddonSessionManager]) and the rule that
 * only the server's definitive refusal ends it.
 */
interface SignInAddon {
    /** The `/auth/config` mode this add-on signs in for. */
    val mode: String

    /** The login screen's line about the server, before and during the sign-in. */
    val description: String

    /** The button that starts the sign-in. */
    val buttonLabel: String

    /** Shown while the browser is open. */
    val waitingText: String

    /**
     * The whole sign-in for the server at [serverUrl], whose `/auth/config`
     * answered [config]: the browser step through [browser], then whatever
     * exchange yields a session. Throws [SignInAddonException] with a message
     * for the user when the provider refused or the user gave up.
     */
    suspend fun signIn(serverUrl: String, config: JsonObject, browser: BrowserSignInPort): AddonSignedIn

    /** Ends [session] with the provider, best-effort. The app has already forgotten it. */
    suspend fun signOut(session: AddonSession)
}

/** A finished add-on sign-in: the bearer token, and whatever sign-out needs later. */
data class AddonSignedIn(
    val token: String,
    val extras: Map<String, String> = emptyMap(),
)

/** The persisted add-on session. */
@Serializable
data class AddonSession(
    /** The add-on that signed in, by its mode. */
    val mode: String,
    /** The Halo server that accepted this session; the post-401 check asks it. */
    val serverUrl: String,
    val token: String,
    val extras: Map<String, String> = emptyMap(),
)

/** A sign-in the provider refused or the user abandoned; the message is meant for the user. */
open class SignInAddonException(message: String) : Exception(message)

/**
 * The platform's browser step for add-on sign-ins: open a URL in a browser the
 * user trusts and hand back where the provider sent it. A Custom Tab on
 * Android, an ASWebAuthenticationSession sheet on iOS.
 */
interface BrowserSignInPort {
    /** `android` or `ios`: providers register one client per platform. */
    val platform: String

    /**
     * Opens [url] and resolves with the full callback URL once the provider
     * sends the browser to [callbackUrl] (Android matches it as a prefix, iOS
     * by its scheme). Throws [SignInAddonException] when the browser cannot
     * open, or the user closed it and the platform can tell.
     */
    suspend fun authorize(url: String, callbackUrl: String): String
}

/** Platforms without a browser step (tests, the fake hosts). */
object NoBrowserSignIn : BrowserSignInPort {
    override val platform: String = "none"

    override suspend fun authorize(url: String, callbackUrl: String): String =
        throw SignInAddonException("This device cannot open a browser sign-in")
}

/** The login screen's add-on submit target; throws on failure, [SignInAddonException] for refusals. */
fun interface AddonAuthenticator {
    suspend fun signInWithAddon(serverUrl: String, config: AuthConfig.Addon)
}
