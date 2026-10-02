@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.ditto.halo.auth

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, count.convert(), pinned.addressOf(0))
    }
    // A sign-in must never proceed on predictable bytes.
    check(status == errSecSuccess) { "The system random source failed ($status)" }
    return bytes
}
