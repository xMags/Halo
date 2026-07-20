package moe.ditto.halo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.PlayerPresenter

internal class GateSession(playerPort: PlayerPort) {
    val playerPresenter = PlayerPresenter(playerPort)
    private val startMutex = Mutex()

    suspend fun ensurePlayerStarted(current: MediaItem, next: MediaItem?) {
        startMutex.withLock {
            if (playerPresenter.state.status != PlaybackStatus.Idle) return
            playerPresenter.start(current, next)
        }
    }
}
