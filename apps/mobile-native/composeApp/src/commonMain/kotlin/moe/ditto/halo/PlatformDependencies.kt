package moe.ditto.halo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import moe.ditto.halo.auth.AuthConfigSource
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.SecureStorage
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort

internal data class PlatformDependencies(
    val authConfigSource: AuthConfigSource,
    val nativeHostRequests: NativeHostRequests,
    val secureStorage: SecureStorage,
    val playerPort: PlayerPort,
    val playerEvents: Flow<PlayerEvent> = emptyFlow(),
    val authEvents: Flow<AuthEvent> = emptyFlow(),
    val nativePlayerSurface: NativePlayerSurface,
    val nativeHostDiagnostics: NativeHostDiagnostics,
    /**
     * Prefilled into the login form; the OIDC test points this at the fixture.
     * When this is exactly [DefaultServerUrl] (no host-side override), the last
     * successfully signed-in server takes precedence as the prefill.
     */
    val initialServerUrl: String = DefaultServerUrl,
    /**
     * Prefilled media bases for the player shell's harness fields. The HTTP
     * default is the fixture-server convention (simulator loopback / adb
     * reverse both reach it); the local base has no portable default, so hosts
     * supply it from their launch environment and it stays blank otherwise.
     */
    val mediaHttpBase: String = "http://127.0.0.1:18787/media",
    val mediaLocalBase: String = "",
    /**
     * Wipes any persisted session before restore. Automation escape hatch:
     * a session persisted by a manual sign-in survives reinstalls (Keychain)
     * and would otherwise strand every UI suite that expects the login form.
     */
    val resetPersistedSession: Boolean = false,
) {
    companion object {
        const val DefaultServerUrl = "https://halo.ditto.moe"
    }
}
