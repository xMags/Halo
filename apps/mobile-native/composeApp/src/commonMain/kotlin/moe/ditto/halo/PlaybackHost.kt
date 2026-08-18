package moe.ditto.halo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerPresenter
import moe.ditto.halo.player.PlayerState
import moe.ditto.halo.player.SubtitleStyle

/**
 * The app's single playback owner: one presenter over the one native core the
 * platform host holds.
 *
 * There is exactly one because there is exactly one core — a second presenter
 * would issue commands to the same engine while believing it owned it. It
 * therefore outlives every screen, which is also why native events can be
 * collected for the app's whole lifetime rather than only while a player is on
 * screen.
 *
 * [state] exists because [PlayerPresenter] holds a plain mutable field: it is
 * driven from callbacks off the main thread and predates Compose entirely, so
 * this republishes it as something a screen can collect.
 */
internal class PlaybackHost(private val playerPort: PlayerPort) {
    val playerPresenter = PlayerPresenter(playerPort)

    private val _state = MutableStateFlow(playerPresenter.state)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val startMutex = Mutex()

    /** Republishes what the presenter now holds, after a caller has driven it. */
    fun publish() {
        _state.value = playerPresenter.state
    }

    suspend fun onEvent(event: PlayerEvent) {
        playerPresenter.onEvent(event)
        publish()
    }

    /**
     * Starts [item], replacing whatever was playing.
     *
     * Deliberately not a teardown-then-load: `teardown` is terminal on the
     * presenter and, on iOS, terminal on the core as well, so a player screen
     * that tore down on the way out would work exactly once per launch.
     */
    suspend fun play(item: MediaItem) {
        playerPresenter.start(item)
        // `pause` belongs to the core, not to the file: whatever paused the
        // previous source is still in force when the next one loads, and the
        // iOS core additionally loads paused on purpose so automation is not
        // racing wall-clock playback. Choosing something to watch says watch
        // it, on both platforms, rather than trusting a load to arrive
        // unpaused.
        playerPresenter.setPaused(false)
        publish()
    }

    /**
     * Stops the picture and the sound without ending the session, which is what
     * leaving a player screen means as long as [play] is the only way back in.
     */
    suspend fun pause() = setPaused(true)

    /**
     * Transport controls, here rather than on the presenter directly, so that a
     * screen never reaches past the owner to the engine: the presenter's state
     * has to be republished after every command, and a caller that drove it
     * itself would have to remember to.
     */
    suspend fun setPaused(paused: Boolean) {
        playerPresenter.setPaused(paused)
        publish()
    }

    suspend fun seekTo(positionSeconds: Double) {
        playerPresenter.seekTo(positionSeconds)
        publish()
    }

    /**
     * Track selection and subtitle styling. All of these apply to the running
     * core without reloading it, which is what makes tuning them while watching
     * possible at all.
     */
    suspend fun selectAudioTrack(id: String?) {
        playerPresenter.selectAudioTrack(id)
        publish()
    }

    suspend fun selectSubtitleTrack(id: String?) {
        playerPresenter.selectSubtitleTrack(id)
        publish()
    }

    suspend fun setPlaybackRate(rate: Double) {
        playerPresenter.setPlaybackRate(rate)
        publish()
    }

    suspend fun setVideoFillsScreen(fills: Boolean) {
        playerPresenter.setVideoFillsScreen(fills)
        publish()
    }

    suspend fun setAudioDelay(seconds: Double) {
        playerPresenter.setAudioDelay(seconds)
        publish()
    }

    suspend fun setSubtitleScale(scale: Double) {
        playerPresenter.setSubtitleScale(scale)
        publish()
    }

    suspend fun setSubtitleDelay(seconds: Double) {
        playerPresenter.setSubtitleDelay(seconds)
        publish()
    }

    suspend fun setSubtitleFont(font: String?) {
        playerPresenter.setSubtitleFont(font)
        publish()
    }

    /**
     * The stored caption appearance, applied as one step. Callers restore this
     * before starting a source so the first caption is already right, rather
     * than correcting itself a moment after it appears.
     */
    suspend fun applySubtitleStyle(style: SubtitleStyle) {
        playerPresenter.setSubtitleScale(style.scale)
        playerPresenter.setSubtitleFont(style.font)
        playerPresenter.setSubtitleOutline(style.outlineWidthPixels)
        playerPresenter.setSubtitleShadow(style.shadowOffsetPixels)
        publish()
    }

    suspend fun setSubtitleTrackStyling(keepScript: Boolean) {
        playerPresenter.setSubtitleTrackStyling(keepScript)
        publish()
    }

    suspend fun setSubtitleOutline(widthPixels: Double) {
        playerPresenter.setSubtitleOutline(widthPixels)
        publish()
    }

    suspend fun setSubtitleShadow(offsetPixels: Double) {
        playerPresenter.setSubtitleShadow(offsetPixels)
        publish()
    }

    suspend fun setSubtitleLift(percent: Int) {
        playerPresenter.setSubtitleLift(percent)
        publish()
    }

    suspend fun addSubtitle(url: String) {
        playerPresenter.addSubtitle(url)
        publish()
    }

    /**
     * Winds playback down far enough that the screen showing it can be taken
     * apart: sound stops, and the video decoder is destroyed and confirmed gone.
     *
     * This must be awaited *before* the player screen leaves, not as it leaves.
     * The render surface dies with that screen, and a decoder still holding
     * buffers cannot answer the surface owner's request to release it — the two
     * wait on each other on the main thread, which the system reports as the app
     * having stopped responding.
     */
    suspend fun windDownForExit() {
        playerPresenter.setPaused(true)
        playerPort.releaseVideoOutput()
        publish()
    }

    /**
     * Loads only if nothing is playing yet — the diagnostics harness re-enters
     * its screen repeatedly and must not restart the core each time.
     */
    suspend fun ensurePlayerStarted(current: MediaItem) {
        startMutex.withLock {
            if (playerPresenter.state.status != PlaybackStatus.Idle) return
            playerPresenter.start(current)
            publish()
        }
    }
}
