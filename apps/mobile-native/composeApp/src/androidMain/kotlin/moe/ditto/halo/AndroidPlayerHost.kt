package moe.ditto.halo

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.MpvCore
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerTracks

/**
 * Android-side owner of one libmpv core and one render [SurfaceView], kept above
 * the Compose navigation so screen changes and recomposition attach/detach the
 * surface without resetting playback — the Android mirror of the Swift-owned
 * host on iOS. It satisfies the same platform-neutral boundary the common shell
 * consumes, so `commonMain` needs no Android-specific code.
 */
internal class AndroidMpvPlayerHost(
    private val appContext: Context,
) : MpvCore.Listener {

    private var coreSeq = 0
    private var viewSeq = 0

    // Serial executor for the heavy libmpv lifecycle (create/init/terminate).
    // mpv_terminate_destroy blocks until the render + event threads join, so it
    // must never run on the UI thread (it ANRs). Surface attach/detach stay on
    // the main thread — they are fast and must finish inside surfaceDestroyed.
    private val coreExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var core: MpvCore = newCore()
    private var surfaceView: SurfaceView? = null
    @Volatile private var currentSurface: android.view.Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    // Diagnostics — same fields the common NativeHostSnapshot renders. @Volatile
    // because the core lifecycle updates some of them off the main thread.
    @Volatile var coreCreationCount = 0L; private set
    @Volatile var coreDestructionCount = 0L; private set
    @Volatile var playerViewCreationCount = 0L; private set
    @Volatile var attachCount = 0L; private set
    @Volatile var resizeCount = 0L; private set
    @Volatile var detachCount = 0L; private set
    @Volatile var loadCount = 0L; private set
    @Volatile var teardownCount = 0L; private set

    val instanceId: String get() = core.id
    val viewInstanceId: String get() = surfaceView?.let { "view-$viewSeq" } ?: "none"

    private val channel = Channel<PlayerEvent>(Channel.UNLIMITED)
    val playerEvents: Flow<PlayerEvent> = channel.receiveAsFlow()

    init {
        core.setListener(this)
    }

    private fun newCore(): MpvCore =
        MpvCore.create(appContext, "core-${++coreSeq}").also { coreCreationCount += 1 }

    // --- MpvCore.Listener: translate to the neutral event stream ---
    override fun onReady(durationSeconds: Double?) = emit(PlayerEvent.Ready(durationSeconds))
    override fun onPosition(positionSeconds: Double) = emit(PlayerEvent.PositionChanged(positionSeconds))
    override fun onPauseChanged(paused: Boolean) = emit(PlayerEvent.PauseChanged(paused))
    override fun onTracks(tracks: PlayerTracks) = emit(PlayerEvent.TracksChanged(tracks))
    override fun onEnded() = emit(PlayerEvent.NaturalEnd)
    override fun onError(message: String) = emit(PlayerEvent.Error(message))

    private fun emit(event: PlayerEvent) {
        channel.trySend(event)
    }

    // --- Player control surface (called by AndroidPlayerPort) ---
    fun load(item: MediaItem) {
        loadCount += 1
        core.load(item.url)
    }

    fun setPaused(paused: Boolean) = core.setPaused(paused)
    fun seekTo(positionSeconds: Double) = core.seekTo(positionSeconds)
    fun selectAudioTrack(id: String?) = core.selectAudioTrack(id)
    fun selectSubtitleTrack(id: String?) = core.selectSubtitleTrack(id)
    fun setSubtitleDelay(seconds: Double) = core.setSubtitleDelay(seconds)
    fun setSubtitleScale(scale: Double) = core.setSubtitleScale(scale)
    fun setSubtitleFont(font: String?) = core.setSubtitleFont(font)
    fun addSubtitle(url: String) = core.addSubtitle(url)

    fun teardown() {
        teardownCount += 1
        core.stop() // stop playback; the core and its view stay alive
    }

    /**
     * The explicit "recreate core" control: build a fresh core, reattach the SAME
     * surface, and tear the old core down. The SurfaceView identity (view id) must
     * stay stable — that is the ownership property under test.
     *
     * Ordering is **create-before-destroy**, on [coreExecutor] rather than the
     * caller's (main) thread, for two reasons:
     *  1. mpv_terminate_destroy blocks while it joins mpv's render/decode threads.
     *     On the emulator's software-GL + emulated-codec path it can block
     *     indefinitely. Running it on the UI thread ANRs; running it *before* the
     *     swap would gate the whole recreate on a call that may never return.
     *  2. So the new core is created and swapped in first (the swap is then
     *     observable immediately), and the old core is torn down on a detached
     *     daemon thread where a slow/hung join harms nothing. There is never a
     *     window with zero cores.
     *
     * The snapshot updates after the tap returns; the shell's "Refresh counters"
     * re-reads it.
     */
    fun destroyAndRecreateCore() {
        val surface = currentSurface
        val width = surfaceWidth
        val height = surfaceHeight
        val old = core
        android.util.Log.i("HALO_MPV", "recreate: enqueue (old=${old.id})")
        coreExecutor.execute {
            old.setListener(null)
            if (surface != null) old.detachSurface()

            val fresh = newCore()
            fresh.setListener(this)
            if (surface != null && surface.isValid) {
                fresh.attachSurface(surface, width, height)
                attachCount += 1
            }
            core = fresh
            android.util.Log.i("HALO_MPV", "recreate: swapped to ${fresh.id}, tearing down ${old.id}")

            // Old-core teardown off the serial executor: a slow/blocked
            // terminate_destroy must never wedge future core operations. On a real
            // device this completes promptly (destroy count increments); on the
            // emulator it may hang here forever — harmless on a leaked daemon.
            Thread {
                old.destroy()
                coreDestructionCount += 1
                android.util.Log.i("HALO_MPV", "recreate: old core ${old.id} torn down")
            }.apply { isDaemon = true; name = "mpv-teardown-${old.id}" }.start()
        }
    }

    // --- Surface plumbing, owned here so the view outlives recomposition ---
    fun composeSurface(): SurfaceView {
        surfaceView?.let { return it }
        val view = SurfaceView(appContext)
        viewSeq += 1
        playerViewCreationCount += 1
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentSurface = holder.surface
                surfaceWidth = view.width
                surfaceHeight = view.height
                core.attachSurface(holder.surface, surfaceWidth, surfaceHeight)
                attachCount += 1
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceWidth = width
                surfaceHeight = height
                core.setSurfaceSize(width, height)
                resizeCount += 1
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                core.detachSurface()
                currentSurface = null
                detachCount += 1
            }
        })
        surfaceView = view
        return view
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
    override suspend fun setSubtitleDelay(seconds: Double) = host.setSubtitleDelay(seconds)
    override suspend fun setSubtitleScale(scale: Double) = host.setSubtitleScale(scale)
    override suspend fun setSubtitleFont(font: String?) = host.setSubtitleFont(font)
    override suspend fun addSubtitle(url: String) = host.addSubtitle(url)
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
    private val authHost: AndroidStubAuthHost,
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
