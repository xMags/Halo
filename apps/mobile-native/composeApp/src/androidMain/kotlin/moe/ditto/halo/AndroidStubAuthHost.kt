package moe.ditto.halo

import moe.ditto.halo.auth.AuthConfig
import moe.ditto.halo.auth.AuthConfigSource
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.OidcHostRequest

/**
 * Hermetic auth stub: Android's native OIDC host (Custom Tabs) is not built
 * yet, so every server resolves to local mode and the shell can walk
 * Login → Gate → Player without a real IdP. iOS's `OidcAuthHost` owns the
 * real flow until the Android shim lands.
 */
internal class AndroidStubAuthHost : AuthConfigSource, NativeHostRequests {
    val hostId: String = "android-stub-auth"

    var oidcRequestCount: Long = 0L
        private set

    override suspend fun fetch(serverUrl: String): AuthConfig = AuthConfig.Local

    override fun requestOidc(request: OidcHostRequest) {
        oidcRequestCount += 1
    }
}
