package moe.ditto.halo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import moe.ditto.halo.auth.AuthConfigSource
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.NoOidcSessionPort
import moe.ditto.halo.auth.OidcSessionPort
import moe.ditto.halo.auth.SecureStorage
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort

internal data class PlatformDependencies(
    val authConfigSource: AuthConfigSource,
    val nativeHostRequests: NativeHostRequests,
    val secureStorage: SecureStorage,
    /** Non-secret device state; kept apart from [secureStorage] deliberately. */
    val keyValueStore: KeyValueStore,
    /**
     * Where the on-disk image cache lives. Platform-owned because the right
     * location differs per OS, and both platforms' choices share the property
     * that matters: the system may purge it, and it is excluded from backups.
     * Poster art is re-fetchable, so it must never occupy backed-up storage.
     */
    val imageCacheDirectory: String,
    /**
     * Native OIDC session owner; [NoOidcSessionPort] where the platform has
     * no OIDC host yet (Android until its port, fakes in tests).
     */
    val oidcSessionPort: OidcSessionPort = NoOidcSessionPort,
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
     * Whether the diagnostics harness is reachable — the gate screen, its host
     * counters, and the login screen's shortcut into it.
     *
     * Set from the build's own debuggable flag rather than from a launch
     * argument, so a shipped build cannot be talked into exposing it.
     */
    val diagnosticsEnabled: Boolean = false,
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
