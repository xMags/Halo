package moe.ditto.halo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import moe.ditto.halo.auth.AuthConfigSource
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.NoOidcSessionPort
import moe.ditto.halo.auth.OidcSessionPort
import moe.ditto.halo.auth.SecureStorage
import moe.ditto.halo.downloads.DownloadStoragePort
import moe.ditto.halo.downloads.DeviceDownloadRuntime
import moe.ditto.halo.downloads.NoDownloadStorage
import moe.ditto.halo.storage.KeyValueStore
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.NoPlayerSystemPort
import moe.ditto.halo.player.NoVideoFrameSource
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.player.VideoFrameSource

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
    /** App-private, purgeable storage for authenticated external subtitles. */
    val subtitleCacheDirectory: String,
    /**
     * Where downloaded media is kept, and how much room is left for more. Not a
     * plain path like the two caches above, because the answer can be "this
     * platform has nowhere to put them"; see [DownloadStoragePort]. Defaults to
     * exactly that, which is what a platform without an implementation should
     * say rather than write files somewhere the system may reclaim.
     */
    val downloadStorage: DownloadStoragePort = NoDownloadStorage,
    /** Application-scoped owner of durable platform download jobs. */
    val downloadRuntime: DeviceDownloadRuntime,
    /**
     * Native OIDC session owner; [NoOidcSessionPort] where the platform has
     * no OIDC host yet (Android until its port, fakes in tests).
     */
    val oidcSessionPort: OidcSessionPort = NoOidcSessionPort,
    val playerPort: PlayerPort,
    /**
     * Brightness, volume, orientation and the sleep timer. Separate from
     * [playerPort] because none of it is the media engine; see
     * [PlayerSystemPort]. Defaults to doing nothing, which is what a platform
     * without an implementation should do rather than pretend.
     */
    val playerSystemPort: PlayerSystemPort = NoPlayerSystemPort,
    /**
     * Frames for the scrub preview, read out of band from playback; see
     * [VideoFrameSource]. Separate from [playerPort] for the same reason
     * [playerSystemPort] is, and defaulting to no frames at all, which leaves
     * the scrub card exactly as it ships without one.
     */
    val videoFrameSource: VideoFrameSource = NoVideoFrameSource,
    /**
     * Subtitle typefaces the platform actually ships to the caption renderer.
     *
     * Naming a family the device does not have is not an error anywhere: the
     * renderer quietly substitutes, so a font control would appear to work and
     * change nothing. Knowing which names are real is what lets the screen say
     * so. Empty means the platform bundles none and every choice is a request
     * the system may or may not honour.
     */
    val bundledSubtitleFonts: Set<String> = emptySet(),
    val playerEvents: Flow<PlayerEvent> = emptyFlow(),
    val authEvents: Flow<AuthEvent> = emptyFlow(),
    /** Notification taps and other host requests to reveal Downloads. */
    val openDownloadsEvents: Flow<Unit> = emptyFlow(),
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
    val mediaHttpBase: String = DefaultMediaHttpBase,
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
        const val DefaultMediaHttpBase = "http://127.0.0.1:18787/media"
    }
}
