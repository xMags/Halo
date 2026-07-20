package moe.ditto.halo

import kotlinx.coroutines.test.runTest
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlayerPort
import kotlin.test.Test
import kotlin.test.assertEquals

class GateSessionTest {
    private val current = MediaItem("one", "Episode one", "https://example.test/one.mp4")
    private val next = MediaItem("two", "Episode two", "https://example.test/two.mp4")

    @Test
    fun repeatedScreenEntryDoesNotReloadOrReplaceThePlayerCore() = runTest {
        val port = RecordingPlayerPort()
        val session = GateSession(port)

        session.ensurePlayerStarted(current, next)
        session.ensurePlayerStarted(current, next)

        assertEquals(listOf(current), port.loads)
        assertEquals(current, session.playerPresenter.state.current)
        assertEquals(next, session.playerPresenter.state.queuedNext)
    }

    private class RecordingPlayerPort : PlayerPort {
        val loads = mutableListOf<MediaItem>()

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
        override suspend fun teardown() = Unit
    }
}
