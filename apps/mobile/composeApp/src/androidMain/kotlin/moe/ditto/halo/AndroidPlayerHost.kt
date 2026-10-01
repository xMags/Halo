package moe.ditto.halo

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.MpvCore
import moe.ditto.halo.player.PlayerBuffering
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerTracks

/**
 * Android-side owner of one libmpv core and one render [SurfaceView], kept above
 * the Compose navigation so screen changes and recomposition attach/detach the
 * surface without resetting playback.
 *
 * Every synchronous MpvCore call is made by [coreExecutor]. In particular,
 * SurfaceHolder callbacks only enqueue work and return to Android immediately.
 */
internal class AndroidMpvPlayerHost(
    private val appContext: Context,
) {

    private var coreSequence = 0
    private var coreGeneration = 0L
    @Volatile private var surfaceEpoch = 0L

    private val acceptingWork = AtomicBoolean(true)
    private val coreExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "halo-mpv-core").apply { isDaemon = true }
    }

    @Volatile private var core: MpvCore? = null
    @Volatile private var activeCoreGeneration = 0L
    @Volatile private var currentCoreId = "pending"
    @Volatile private var cachedMutedForTest: Boolean? = null
    @Volatile private var surfaceView: SurfaceView? = null
    @Volatile private var currentSurface: android.view.Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    // Diagnostics use volatile values because the core executor updates them.
    @Volatile var coreCreationCount = 0L; private set
    @Volatile var coreDestructionCount = 0L; private set
    @Volatile var playerViewCreationCount = 0L; private set
    @Volatile var attachCount = 0L; private set
    @Volatile var resizeCount = 0L; private set
    @Volatile var detachCount = 0L; private set
    @Volatile var loadCount = 0L; private set
    @Volatile var teardownCount = 0L; private set

    val instanceId: String get() = currentCoreId
    val viewInstanceId: String get() = surfaceView?.let { "view-$viewSequence" } ?: "none"
    val mutedForTest: Boolean? get() = cachedMutedForTest

    private var viewSequence = 0
    private val channel = Channel<PlayerEvent>(Channel.UNLIMITED)
    val playerEvents: Flow<PlayerEvent> = channel.receiveAsFlow()

    init {
        coreExecutor.execute {
            if (acceptingWork.get()) installFreshCore()
        }
    }

    private fun installFreshCore(): MpvCore? {
        if (!acceptingWork.get()) return null
        val generation = coreGeneration + 1
        val created = MpvCore.create(appContext, "core-${++coreSequence}", coreExecutor)
        coreCreationCount += 1
        core = created
        activeCoreGeneration = generation
        coreGeneration = generation
        currentCoreId = created.id
        created.setListener(listenerFor(generation))
        // This property read is deliberately performed on the executor. The
        // instrumentation seam returns only this cached result on the caller.
        cachedMutedForTest = created.isMutedForTest()
        return created
    }

    private fun listenerFor(generation: Long): MpvCore.Listener = object : MpvCore.Listener {
        override fun onReady(durationSeconds: Double?) =
            emitFromCore(generation, PlayerEvent.Ready(durationSeconds))

        override fun onPosition(positionSeconds: Double) =
            emitFromCore(generation, PlayerEvent.PositionChanged(positionSeconds))

        override fun onPauseChanged(paused: Boolean) =
            emitFromCore(generation, PlayerEvent.PauseChanged(paused))

        override fun onTracks(tracks: PlayerTracks) =
            emitFromCore(generation, PlayerEvent.TracksChanged(tracks))

        override fun onBuffering(buffering: PlayerBuffering?) = emitFromCore(
            generation,
            PlayerEvent.BufferingChanged(
                active = buffering != null,
                percent = buffering?.percent,
                bytesPerSecond = buffering?.bytesPerSecond,
                cachedSeconds = buffering?.cachedSeconds,
            ),
        )

        override fun onBufferedPosition(positionSeconds: Double) =
            emitFromCore(generation, PlayerEvent.BufferedPositionChanged(positionSeconds))

        override fun onEnded() = emitFromCore(generation, PlayerEvent.NaturalEnd)
        override fun onError(message: String) = emitFromCore(generation, PlayerEvent.Error(message))
    }

    private fun emitFromCore(generation: Long, event: PlayerEvent) {
        if (!acceptingWork.get() || activeCoreGeneration != generation) return
        channel.trySend(event)
    }

    private fun enqueueAsync(operation: String, action: (MpvCore) -> Unit) {
        if (!acceptingWork.get()) return
        try {
            coreExecutor.execute {
                if (!acceptingWork.get()) return@execute
                val target = core ?: run {
                    android.util.Log.w(LOG_TAG, "$operation skipped because the core is not ready")
                    return@execute
                }
                runCatching { action(target) }
                    .onFailure { android.util.Log.w(LOG_TAG, "$operation failed", it) }
            }
        } catch (_: RejectedExecutionException) {
            android.util.Log.w(LOG_TAG, "$operation rejected after player shutdown")
        }
    }

    /**
     * Runs [action] on the core executor and waits up to [timeoutMs] for it.
     *
     * Only surface teardown needs this. Android takes the buffer queue away as
     * soon as `surfaceDestroyed` returns, and a producer still drawing into an
     * abandoned queue is outside the platform contract, so the detach has to
     * have happened by then. Doing it on the caller's thread instead is the
     * deadlock this host already learned about: mpv's teardown blocks until the
     * video chain acknowledges, and a hardware decoder stuck in the middle of a
     * buffer never will.
     *
     * Hence the bound: a sick core costs one stalled callback and a dropped
     * surface rather than a frozen UI thread. It is a ceiling, not a target —
     * with the decoder already released by the exit path the wait is normally
     * over in microseconds.
     */
    private fun enqueueAwaiting(operation: String, timeoutMs: Long, action: (MpvCore) -> Unit) {
        if (!acceptingWork.get()) return
        val finished = CountDownLatch(1)
        try {
            coreExecutor.execute {
                try {
                    if (!acceptingWork.get()) return@execute
                    val target = core ?: run {
                        android.util.Log.w(LOG_TAG, "$operation skipped because the core is not ready")
                        return@execute
                    }
                    runCatching { action(target) }
                        .onFailure { android.util.Log.w(LOG_TAG, "$operation failed", it) }
                } finally {
                    // Every early return above still has to release the caller,
                    // or a shutdown race spends the whole timeout waiting for
                    // work that was never going to run.
                    finished.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            android.util.Log.w(LOG_TAG, "$operation rejected after player shutdown")
            return
        }
        if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            android.util.Log.w(LOG_TAG, "$operation did not finish within ${timeoutMs}ms")
        }
    }

    private suspend fun enqueue(operation: String, action: (MpvCore) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            if (!acceptingWork.get()) {
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            try {
                coreExecutor.execute {
                    if (!acceptingWork.get()) {
                        if (continuation.isActive) continuation.resume(Unit)
                        return@execute
                    }
                    val target = core
                    if (target == null) {
                        android.util.Log.w(LOG_TAG, "$operation skipped because the core is not ready")
                    } else {
                        runCatching { action(target) }
                            .onFailure { android.util.Log.w(LOG_TAG, "$operation failed", it) }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            } catch (_: RejectedExecutionException) {
                android.util.Log.w(LOG_TAG, "$operation rejected after player shutdown")
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

    // --- Player control surface, all suspendable so callers await queue order ---
    suspend fun load(item: MediaItem) = enqueue("load") {
        loadCount += 1
        it.setVideoEnabled(true)
        it.load(item.url)
    }

    /**
     * Releases the video decoder while the surface is still alive. The wait is
     * bounded so a sick native core cannot strand navigation, and the native
     * operation itself remains serialized on the executor after a timeout.
     */
    suspend fun releaseVideoBlocking(timeoutMs: Long = RELEASE_TIMEOUT_MS) {
        val completed = withTimeoutOrNull(timeoutMs) {
            enqueue("release-video") { it.setVideoEnabled(false) }
            true
        } ?: false
        if (!completed) {
            android.util.Log.w(LOG_TAG, "video release timed out after ${timeoutMs}ms")
        }
    }

    suspend fun setPaused(paused: Boolean) = enqueue("pause") { it.setPaused(paused) }
    suspend fun seekTo(positionSeconds: Double) = enqueue("seek") { it.seekTo(positionSeconds) }
    suspend fun selectAudioTrack(id: String?) = enqueue("audio-track") { it.selectAudioTrack(id) }
    suspend fun selectSubtitleTrack(id: String?) = enqueue("subtitle-track") { it.selectSubtitleTrack(id) }
    suspend fun setPlaybackRate(rate: Double) = enqueue("playback-rate") { it.setPlaybackRate(rate) }
    suspend fun setAudioDelay(seconds: Double) = enqueue("audio-delay") { it.setAudioDelay(seconds) }
    suspend fun setVideoFillsScreen(fills: Boolean) = enqueue("video-fill") { it.setVideoFillsScreen(fills) }
    suspend fun setSubtitleDelay(seconds: Double) = enqueue("subtitle-delay") { it.setSubtitleDelay(seconds) }
    suspend fun setSubtitleScale(scale: Double) = enqueue("subtitle-scale") { it.setSubtitleScale(scale) }
    suspend fun setSubtitleFont(font: String?) = enqueue("subtitle-font") { it.setSubtitleFont(font) }
    suspend fun setSubtitleTrackStyling(keepScript: Boolean) =
        enqueue("subtitle-style") { it.setSubtitleTrackStyling(keepScript) }

    suspend fun setSubtitleOutline(widthPixels: Double) =
        enqueue("subtitle-outline") { it.setSubtitleOutline(widthPixels) }

    suspend fun setSubtitleShadow(offsetPixels: Double) =
        enqueue("subtitle-shadow") { it.setSubtitleShadow(offsetPixels) }

    suspend fun setSubtitleLift(percent: Int) = enqueue("subtitle-lift") { it.setSubtitleLift(percent) }
    suspend fun addSubtitle(url: String) = enqueue("subtitle-add") { it.addSubtitle(url) }

    suspend fun teardown() = enqueue("teardown") {
        teardownCount += 1
        it.stop()
    }

    /**
     * Diagnostic-only core replacement. Creation, surface handoff, and old
     * core destruction stay on the same serialized executor. Listener
     * generations prevent late events from the old core reaching the shell.
     */
    fun destroyAndRecreateCore() {
        val surface = currentSurface
        val capturedSurfaceEpoch = surfaceEpoch
        val width = surfaceWidth
        val height = surfaceHeight
        enqueueAsync("recreate") { old ->
            old.setListener(null)
            if (surface != null) old.detachSurface()

            val fresh = installFreshCore() ?: return@enqueueAsync
            if (
                surface != null &&
                surfaceEpoch == capturedSurfaceEpoch &&
                currentSurface == surface &&
                surface.isValid
            ) {
                fresh.attachSurface(surface, width, height)
                attachCount += 1
            }
            old.destroy()
            coreDestructionCount += 1
        }
    }

    /**
     * Idempotent activity-lifecycle shutdown. It only schedules native work,
     * so Activity.onDestroy never waits for mpv's render and event threads.
     */
    fun close() {
        if (!acceptingWork.compareAndSet(true, false)) return
        try {
            coreExecutor.execute {
                val target = core
                if (target != null) {
                    target.setListener(null)
                    target.destroy()
                    core = null
                    coreDestructionCount += 1
                }
                currentCoreId = "closed"
                cachedMutedForTest = null
                coreExecutor.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            coreExecutor.shutdownNow()
        }
    }

    // --- Surface plumbing, owned here so the view outlives recomposition ---
    fun composeSurface(): SurfaceView {
        surfaceView?.let { return it }
        val view = SurfaceView(appContext)
        viewSequence += 1
        playerViewCreationCount += 1
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val epoch = ++surfaceEpoch
                val surface = holder.surface
                currentSurface = surface
                surfaceWidth = view.width
                surfaceHeight = view.height
                enqueueAsync("surface-attach") {
                    if (surfaceEpoch != epoch || currentSurface != surface || !surface.isValid) return@enqueueAsync
                    it.attachSurface(surface, surfaceWidth, surfaceHeight)
                    // The counterpart to the detach dropping the video track:
                    // without it the picture never comes back after the surface
                    // does, and playback continues as sound over a black screen.
                    // Only [load] used to turn video on, which is why returning
                    // to a still-playing episode used to lose it.
                    it.setVideoEnabled(true)
                    attachCount += 1
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceWidth = width
                surfaceHeight = height
                val surface = holder.surface
                enqueueAsync("surface-resize") {
                    if (currentSurface != surface) return@enqueueAsync
                    it.setSurfaceSize(width, height)
                    resizeCount += 1
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                ++surfaceEpoch
                currentSurface = null
                detachCount += 1
                // Awaited rather than fire-and-forget: the buffer queue behind
                // this surface is abandoned the moment we return, so the detach
                // has to be done by then. The order is mpv's own and must not
                // be rearranged — force-window keeps a video output alive with
                // no video, so dropping the track alone does not free anything.
                enqueueAwaiting("surface-destroy", DETACH_TIMEOUT_MS) {
                    it.setPaused(true)
                    it.setVideoEnabled(false)
                    it.detachSurface()
                }
            }
        })
        surfaceView = view
        return view
    }

    companion object {
        private const val LOG_TAG = "HALO_MPV"
        private const val RELEASE_TIMEOUT_MS = 1_500L

        /**
         * Emergency ceiling for the surface detach, not an expected cost: half a
         * second is roughly thirty frames, and a wait that routinely gets near
         * it means the decoder is not being released before the screen leaves.
         * Raise or lower it from measured teardown latency, not by feel.
         */
        private const val DETACH_TIMEOUT_MS = 500L
    }
}

internal class AndroidPlayerPort(
    private val host: AndroidMpvPlayerHost,
) : PlayerPort {
    override suspend fun load(item: MediaItem) = host.load(item)
    override suspend fun setPaused(paused: Boolean) = host.setPaused(paused)
    override suspend fun seekTo(positionSeconds: Double) = host.seekTo(positionSeconds)
    override suspend fun selectAudioTrack(id: String?) = host.selectAudioTrack(id)
    override suspend fun selectSubtitleTrack(id: String?) = host.selectSubtitleTrack(id)
    override suspend fun setPlaybackRate(rate: Double) = host.setPlaybackRate(rate)
    override suspend fun setAudioDelay(seconds: Double) = host.setAudioDelay(seconds)
    override suspend fun setVideoFillsScreen(fills: Boolean) = host.setVideoFillsScreen(fills)
    override suspend fun setSubtitleDelay(seconds: Double) = host.setSubtitleDelay(seconds)
    override suspend fun setSubtitleScale(scale: Double) = host.setSubtitleScale(scale)
    override suspend fun setSubtitleFont(font: String?) = host.setSubtitleFont(font)
    override suspend fun setSubtitleTrackStyling(keepScript: Boolean) = host.setSubtitleTrackStyling(keepScript)
    override suspend fun setSubtitleOutline(widthPixels: Double) = host.setSubtitleOutline(widthPixels)
    override suspend fun setSubtitleShadow(offsetPixels: Double) = host.setSubtitleShadow(offsetPixels)
    override suspend fun setSubtitleLift(percent: Int) = host.setSubtitleLift(percent)
    override suspend fun addSubtitle(url: String) = host.addSubtitle(url)
    override suspend fun releaseVideoOutput() = host.releaseVideoBlocking()
    override suspend fun teardown() = host.teardown()
}

internal class AndroidNativePlayerSurface(
    private val host: AndroidMpvPlayerHost,
) : NativePlayerSurface {
    @Composable
    override fun Content(modifier: Modifier) {
        AndroidView(
            factory = {
                host.composeSurface().also { view ->
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            },
            modifier = modifier,
        )
    }
}

internal class AndroidNativeHostDiagnostics(
    private val authHost: AndroidOidcAuthHost,
    private val host: AndroidMpvPlayerHost,
) : NativeHostDiagnostics {
    override fun snapshot(): NativeHostSnapshot = NativeHostSnapshot(
        authHostId = authHost.hostId,
        playerHostId = "android-mpv-host",
        playerInstanceId = host.instanceId,
        playerViewInstanceId = host.viewInstanceId,
        coreCreationCount = host.coreCreationCount,
        coreDestructionCount = host.coreDestructionCount,
        playerViewCreationCount = host.playerViewCreationCount,
        attachCount = host.attachCount,
        resizeCount = host.resizeCount,
        detachCount = host.detachCount,
        loadCount = host.loadCount,
        teardownCount = host.teardownCount,
        oidcRequestCount = authHost.oidcRequestCount,
    )

    override fun destroyAndRecreatePlayerCore() {
        host.destroyAndRecreateCore()
    }
}
