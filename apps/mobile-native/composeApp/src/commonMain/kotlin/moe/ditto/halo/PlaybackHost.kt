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
internal class PlaybackHost(playerPort: PlayerPort) {
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
        publish()
    }

    /**
     * Stops the picture and the sound without ending the session, which is what
     * leaving a player screen means as long as [play] is the only way back in.
     */
    suspend fun pause() {
        playerPresenter.setPaused(true)
        publish()
    }

    /**
     * Loads only if nothing is playing yet — the diagnostics harness re-enters
     * its screen repeatedly and must not restart the core each time.
     */
    suspend fun ensurePlayerStarted(current: MediaItem, next: MediaItem?) {
        startMutex.withLock {
            if (playerPresenter.state.status != PlaybackStatus.Idle) return
            playerPresenter.start(current, next)
            publish()
        }
    }
}
