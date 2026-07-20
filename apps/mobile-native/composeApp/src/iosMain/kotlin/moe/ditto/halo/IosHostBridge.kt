@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.ditto.halo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import moe.ditto.halo.auth.AuthConfig
import moe.ditto.halo.auth.AuthConfigParser
import moe.ditto.halo.auth.AuthConfigSource
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.NativeHostRequests
import moe.ditto.halo.auth.OidcHostRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks
import platform.UIKit.UIView

interface HaloIosAuthHost {
    val hostId: String
    val oidcRequestCount: Long

    /**
     * Fetches `{serverUrl}/auth/config` and reports the raw JSON body through
     * [completion] as `(json, errorMessage)` with exactly one argument non-null.
     * Asynchronous on purpose: a real network GET must not block the Compose
     * thread the way the former synchronous `String` return would have.
     */
    fun fetchAuthConfig(serverUrl: String, completion: (String?, String?) -> Unit)

    fun requestOidc(
        serverUrl: String,
        issuer: String,
        clientId: String,
        scopes: String,
    )

    /** Registers the sink the host pushes the terminal OIDC outcome into. */
    fun setAuthEventSink(sink: HaloIosAuthEventSink?)
}

/**
 * Swift→Kotlin OIDC outcome. The Swift host runs discovery, the browser, and
 * the token exchange off the main thread, then reports exactly one terminal
 * result here. Mirrors [HaloIosPlayerEventSink]: the receiving bridge only does
 * a thread-safe, non-suspending channel send.
 */
interface HaloIosAuthEventSink {
    fun onOidcSucceeded(tokenProof: String)
    fun onOidcFailed(reason: String)
}

/**
 * Swift→Kotlin playback events. Swift may call these from any thread; the
 * receiving bridge only does a non-suspending, thread-safe channel send.
 *
 * Track lists cross the boundary as a neutral JSON document (see
 * [PlayerTracksJson]) built by Swift — mpv's own `track-list` schema must not
 * leak into common code.
 */
interface HaloIosPlayerEventSink {
    /** [durationSeconds] < 0 means unknown. */
    fun onReady(durationSeconds: Double)
    fun onPosition(positionSeconds: Double)
    fun onPauseChanged(paused: Boolean)
    fun onTracks(tracksJson: String)
    fun onEnded()
    fun onError(message: String)
}

interface HaloIosPlayerHost {
    val hostId: String
    val instanceId: String
    val playerViewInstanceId: String
    val coreCreationCount: Long
    val coreDestructionCount: Long
    val playerViewCreationCount: Long
    val attachCount: Long
    val resizeCount: Long
    val detachCount: Long
    val loadCount: Long
    val teardownCount: Long

    fun playerView(): UIView
    fun didAttachPlayerView()
    fun didResizePlayerView(widthPoints: Double, heightPoints: Double)
    fun didDetachPlayerView()

    fun setEventSink(sink: HaloIosPlayerEventSink?)

    fun load(id: String, title: String, url: String)
    fun setPaused(paused: Boolean)
    fun seekTo(positionSeconds: Double)
    fun selectAudioTrack(id: String?)
    fun selectSubtitleTrack(id: String?)
    fun setSubtitleDelay(seconds: Double)
    fun setSubtitleScale(scale: Double)
    fun setSubtitleFont(font: String?)
    fun addSubtitle(url: String)
    fun teardown()
    fun destroyAndRecreateCore()
}

@Serializable
internal data class PlayerTrackJson(
    val id: String,
    val label: String,
    val language: String? = null,
)

@Serializable
internal data class PlayerTracksJson(
    val audio: List<PlayerTrackJson> = emptyList(),
    val subtitles: List<PlayerTrackJson> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
)

/**
 * Buffers Swift-side playback events into a [Flow] the Compose shell collects.
 * `trySend` on an unlimited channel never suspends and is safe from the
 * background queue mpv events arrive on.
 */
internal class IosPlayerEventBridge : HaloIosPlayerEventSink {
    private val json = Json { ignoreUnknownKeys = true }
    private val channel = Channel<PlayerEvent>(Channel.UNLIMITED)

    val events: Flow<PlayerEvent> = channel.receiveAsFlow()

    override fun onReady(durationSeconds: Double) {
        channel.trySend(PlayerEvent.Ready(durationSeconds.takeIf { it >= 0.0 }))
    }

    override fun onPosition(positionSeconds: Double) {
        channel.trySend(PlayerEvent.PositionChanged(positionSeconds))
    }

    override fun onPauseChanged(paused: Boolean) {
        channel.trySend(PlayerEvent.PauseChanged(paused))
    }

    override fun onTracks(tracksJson: String) {
        val parsed = runCatching { json.decodeFromString<PlayerTracksJson>(tracksJson) }
            .getOrNull() ?: return
        channel.trySend(
            PlayerEvent.TracksChanged(
                PlayerTracks(
                    audio = parsed.audio.map { PlayerTrack(it.id, it.label, it.language) },
                    subtitles = parsed.subtitles.map { PlayerTrack(it.id, it.label, it.language) },
                    selectedAudioId = parsed.selectedAudioId,
                    selectedSubtitleId = parsed.selectedSubtitleId,
                ),
            ),
        )
    }

    override fun onEnded() {
        channel.trySend(PlayerEvent.NaturalEnd)
    }

    override fun onError(message: String) {
        channel.trySend(PlayerEvent.Error(message))
    }
}

/**
 * Buffers Swift-side OIDC outcomes into a [Flow] the login screen collects.
 * `trySend` on an unlimited channel never suspends and is safe from the
 * background queue the token-exchange completion arrives on.
 */
internal class IosAuthEventBridge : HaloIosAuthEventSink {
    private val channel = Channel<AuthEvent>(Channel.UNLIMITED)

    val events: Flow<AuthEvent> = channel.receiveAsFlow()

    override fun onOidcSucceeded(tokenProof: String) {
        channel.trySend(AuthEvent.OidcSucceeded(tokenProof))
    }

    override fun onOidcFailed(reason: String) {
        channel.trySend(AuthEvent.OidcFailed(reason))
    }
}

internal class IosAuthHostAdapter(
    private val host: HaloIosAuthHost,
) : AuthConfigSource, NativeHostRequests {
    override suspend fun fetch(serverUrl: String): AuthConfig =
        suspendCancellableCoroutine { continuation ->
            host.fetchAuthConfig(serverUrl) { json, error ->
                when {
                    !continuation.isActive -> Unit
                    json != null -> runCatching { AuthConfigParser.parse(json) }.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                    else -> continuation.resumeWithException(
                        IllegalStateException(error ?: "Auth config request failed"),
                    )
                }
            }
        }

    override fun requestOidc(request: OidcHostRequest) {
        host.requestOidc(
            serverUrl = request.serverUrl,
            issuer = request.issuer,
            clientId = request.clientId,
            scopes = request.scopes.joinToString(" "),
        )
    }
}

internal class IosPlayerHostAdapter(
    private val host: HaloIosPlayerHost,
) : PlayerPort {
    override suspend fun load(item: MediaItem) {
        host.load(item.id, item.title, item.url)
    }

    override suspend fun setPaused(paused: Boolean) {
        host.setPaused(paused)
    }

    override suspend fun seekTo(positionSeconds: Double) {
        host.seekTo(positionSeconds)
    }

    override suspend fun selectAudioTrack(id: String?) {
        host.selectAudioTrack(id)
    }

    override suspend fun selectSubtitleTrack(id: String?) {
        host.selectSubtitleTrack(id)
    }

    override suspend fun setSubtitleDelay(seconds: Double) {
        host.setSubtitleDelay(seconds)
    }

    override suspend fun setSubtitleScale(scale: Double) {
        host.setSubtitleScale(scale)
    }

    override suspend fun setSubtitleFont(font: String?) {
        host.setSubtitleFont(font)
    }

    override suspend fun addSubtitle(url: String) {
        host.addSubtitle(url)
    }

    override suspend fun teardown() {
        host.teardown()
    }
}

internal class IosNativePlayerSurface(
    private val host: HaloIosPlayerHost,
) : NativePlayerSurface {
    @Composable
    override fun Content(modifier: Modifier) {
        val density = LocalDensity.current
        UIKitView(
            factory = {
                host.playerView().also { host.didAttachPlayerView() }
            },
            modifier = modifier.onSizeChanged { size ->
                host.didResizePlayerView(
                    widthPoints = size.width / density.density.toDouble(),
                    heightPoints = size.height / density.density.toDouble(),
                )
            },
            update = {},
            onRelease = { host.didDetachPlayerView() },
            properties = UIKitInteropProperties(
                isInteractive = false,
                isNativeAccessibilityEnabled = false,
            ),
        )
    }
}

internal class IosNativeHostDiagnostics(
    private val authHost: HaloIosAuthHost,
    private val playerHost: HaloIosPlayerHost,
) : NativeHostDiagnostics {
    override fun snapshot(): NativeHostSnapshot = NativeHostSnapshot(
        authHostId = authHost.hostId,
        playerHostId = playerHost.hostId,
        playerInstanceId = playerHost.instanceId,
        playerViewInstanceId = playerHost.playerViewInstanceId,
        coreCreationCount = playerHost.coreCreationCount,
        coreDestructionCount = playerHost.coreDestructionCount,
        playerViewCreationCount = playerHost.playerViewCreationCount,
        attachCount = playerHost.attachCount,
        resizeCount = playerHost.resizeCount,
        detachCount = playerHost.detachCount,
        loadCount = playerHost.loadCount,
        teardownCount = playerHost.teardownCount,
        oidcRequestCount = authHost.oidcRequestCount,
    )

    override fun destroyAndRecreatePlayerCore() {
        playerHost.destroyAndRecreateCore()
    }
}
