package moe.ditto.halo.auth

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * PKCE (RFC 7636) for the sign-ins that run in common code. The OIDC hosts
 * keep their own native PKCE; this serves the sign-in add-ons, whose whole wire
 * runs in common code.
 */
object Pkce {
    /** 32 random bytes as base64url: a 43-character verifier, or an equally strong `state`. */
    fun randomToken(): String = base64UrlNoPadding(secureRandomBytes(32))

    /** The S256 challenge for [verifier]. */
    fun challenge(verifier: String): String = verifier.encodeUtf8().sha256().base64Url().trimEnd('=')

    private fun base64UrlNoPadding(bytes: ByteArray): String = bytes.toByteString().base64Url().trimEnd('=')
}

/** Bytes from the platform's cryptographically secure random source. */
internal expect fun secureRandomBytes(count: Int): ByteArray
