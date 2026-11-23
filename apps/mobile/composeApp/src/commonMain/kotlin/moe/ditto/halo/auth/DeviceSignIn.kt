package moe.ditto.halo.auth

import kotlinx.serialization.json.JsonObject

/**
 * Signing in on a device with no keyboard worth typing on: it shows a short
 * code and an address, the viewer approves it on a phone already signed in,
 * and the device asks the server until the answer comes.
 *
 * A [SignInAddon] may also implement this for its mode; local-mode servers
 * that offer it get a built-in one.
 */
interface DeviceSignIn {
    /**
     * Asks the server at [serverUrl] (whose `/auth/config` answered [config])
     * for a code to show. Throws [DeviceSignInException] when the server
     * refuses; any other exception is a transport failure.
     */
    suspend fun start(serverUrl: String, config: JsonObject): DeviceChallenge

    /**
     * Asks whether [challenge] has been approved. Throws
     * [DeviceSignInException] for a definitive refusal other than the ones
     * [DevicePollResult] names; any other exception is a transport failure,
     * after which asking again is right.
     */
    suspend fun poll(serverUrl: String, challenge: DeviceChallenge): DevicePollResult
}

/** What the server handed out to show on screen, and the secret to ask with. */
data class DeviceChallenge(
    /** The secret the device polls with. Never shown or logged. */
    val deviceCode: String,
    /** What the viewer compares on the phone, as shown (for example `BCDF-GHJK`). */
    val userCode: String,
    /** The address to open on the phone. */
    val verificationUrl: String,
    /** The same address with the code filled in, for the QR code; null when the server has none. */
    val verificationUrlComplete: String?,
    val expiresInSeconds: Int,
    /** How long to wait between polls, at least. */
    val intervalSeconds: Int,
    /** Whatever the method needs again when it polls (where it started, say). */
    val extras: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "DeviceChallenge(userCode=$userCode, verificationUrl=$verificationUrl, expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

/** The server's answer to one poll. */
sealed interface DevicePollResult {
    /** Not approved yet. */
    data object Pending : DevicePollResult

    /** Asking too often: wait longer between polls. */
    data object SlowDown : DevicePollResult

    /** The viewer said no on the phone. */
    data object Denied : DevicePollResult

    /** The code ran out before anyone approved it. */
    data object Expired : DevicePollResult

    /** Approved: the session to keep. */
    data class Approved(val session: DeviceSession) : DevicePollResult
}

/** A session a device sign-in produced, in the shape of the sign-in it stands in for. */
sealed interface DeviceSession {
    /** A local-mode server's own session, as its password sign-in would give. */
    data class Local(val token: String, val expiresAt: Long) : DeviceSession {
        override fun toString(): String = "Local(expiresAt=$expiresAt)"
    }

    /** A sign-in add-on's session, as its browser sign-in would give. */
    data class Addon(val mode: String, val signedIn: AddonSignedIn) : DeviceSession {
        override fun toString(): String = "Addon(mode=$mode)"
    }
}

/** A refusal the server made plain; the message is meant for the viewer. */
open class DeviceSignInException(message: String) : Exception(message)

/** A server's offer to sign in with a phone: the method, and the server's `/auth/config` it reads. */
class DeviceSignInOffer(val signIn: DeviceSignIn, val config: JsonObject)

/**
 * The phone sign-in a server's sign-in method offers, or null when it has
 * none. [local] is the built-in method for local-mode servers that offer it.
 */
fun AuthConfig.deviceSignInOffer(local: DeviceSignIn): DeviceSignInOffer? = when (this) {
    is AuthConfig.Addon -> (addon as? DeviceSignIn)?.let { DeviceSignInOffer(it, fields) }
    is AuthConfig.Local -> if (deviceSignIn) DeviceSignInOffer(local, JsonObject(emptyMap())) else null
    is AuthConfig.Oidc -> null
}
