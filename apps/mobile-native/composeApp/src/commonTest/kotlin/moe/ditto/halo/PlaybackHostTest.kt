package moe.ditto.halo

import kotlinx.coroutines.test.runTest
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHostTest {
    private val current = MediaItem("one", "Episode one", "https://example.test/one.mp4")
    private val next = MediaItem("two", "Episode two", "https://example.test/two.mp4")

    @Test
    fun repeatedScreenEntryDoesNotReloadOrReplaceThePlayerCore() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.ensurePlayerStarted(current, next)
        host.ensurePlayerStarted(current, next)

        assertEquals(listOf(current), port.loads)
        assertEquals(current, host.playerPresenter.state.current)
        assertEquals(next, host.playerPresenter.state.queuedNext)
    }

    @Test
    fun playingASecondSourceReloadsWithoutTearingTheCoreDown() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.play(current)
        host.play(next)

        // Teardown is terminal — on the presenter, and on the iOS core, which
        // shuts libmpv down for good. Leaving a player screen must therefore
        // never reach for it, or the next source of the session plays nothing.
        assertEquals(listOf(current, next), port.loads)
        assertEquals(0, port.teardownCount)
        assertEquals(next, host.state.value.current)
    }

    @Test
    fun stateFollowsThePresenterWithoutAScreenAskingItTo() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.play(current)
        host.onEvent(PlayerEvent.Ready(durationSeconds = 120.0))

        assertEquals(PlaybackStatus.Playing, host.state.value.status)
        assertEquals(120.0, host.state.value.durationSeconds)
    }

    private class RecordingPlayerPort : PlayerPort {
        val loads = mutableListOf<MediaItem>()
        var teardownCount = 0
            private set

        override suspend fun load(item: MediaItem) {
            loads += item
        }

        override suspend fun setPaused(paused: Boolean) = Unit
        override suspend fun seekTo(positionSeconds: Double) = Unit
        override suspend fun selectAudioTrack(id: String?) = Unit
        override suspend fun selectSubtitleTrack(id: String?) = Unit
        override suspend fun setSubtitleDelay(seconds: Double) = Unit
        override suspend fun setSubtitleScale(scale: Double) = Unit
        override suspend fun setSubtitleFont(font: String?) = Unit
        override suspend fun addSubtitle(url: String) = Unit

        override suspend fun teardown() {
            teardownCount += 1
        }
    }
}
