package moe.ditto.halo

import kotlinx.coroutines.test.runTest
import moe.ditto.halo.player.MediaItem
import moe.ditto.halo.player.PlaybackStatus
import moe.ditto.halo.player.PlayerEvent
import moe.ditto.halo.player.PlayerPort
import moe.ditto.halo.player.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHostTest {
    private val current = MediaItem("one", "Episode one", "https://example.test/one.mp4")
    private val next = MediaItem("two", "Episode two", "https://example.test/two.mp4")

    @Test
    fun repeatedScreenEntryDoesNotReloadOrReplaceThePlayerCore() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.ensurePlayerStarted(current)
        host.ensurePlayerStarted(current)

        assertEquals(listOf(current), port.loads)
        assertEquals(current, host.playerPresenter.state.current)
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
    fun playingUnpausesWhatALeavingScreenPaused() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.play(current)
        host.windDownForExit()
        host.play(next)

        // `pause` lives on the core, not on the file, and the iOS core loads
        // paused deliberately — so a source that is merely loaded stays
        // stopped, which reads as the second source in a session being broken.
        assertEquals(listOf(false, true, false), port.pauses)
    }

    @Test
    fun playbackRatePublishesThePresenterEcho() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)
        host.play(current)

        host.setPlaybackRate(1.25)

        assertEquals(listOf(1.25), port.playbackRates)
        assertEquals(1.25, host.state.value.playbackRate)
    }

    @Test
    fun storedSubtitleAppearanceReachesTheCoreAsOneStep() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)
        host.play(current)

        host.applySubtitleStyle(
            SubtitleStyle(scale = 1.5, font = "JetBrains Mono", outlineWidthPixels = 1.0, shadowOffsetPixels = 2.0),
        )

        assertEquals(
            listOf("scale=1.5", "font=JetBrains Mono", "outline=1.0", "shadow=2.0"),
            port.subtitleCalls,
        )
        assertEquals(1.5, host.state.value.subtitleScale)
        assertEquals("JetBrains Mono", host.state.value.subtitleFont)
    }

    @Test
    fun captionLiftIsClampedBeforeItReachesTheCore() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)
        host.play(current)

        host.setSubtitleLift(12)
        host.setSubtitleLift(0)
        // A value written by another client, or a future one: the caption must
        // not be sent off the top of the frame.
        host.setSubtitleLift(400)
        host.setSubtitleLift(-10)

        assertEquals(listOf("lift=12", "lift=0", "lift=50", "lift=0"), port.subtitleCalls)
    }

    @Test
    fun leavingReleasesTheVideoChainBeforeTheScreenCanGoAway() = runTest {
        val port = RecordingPlayerPort()
        val host = PlaybackHost(port)

        host.play(current)
        host.windDownForExit()

        // Without this the render surface is torn down while a hardware
        // decoder still holds it, and the two deadlock on the main thread.
        assertEquals(1, port.videoReleases)
        assertEquals(0, port.teardownCount)
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
        val pauses = mutableListOf<Boolean>()
        val playbackRates = mutableListOf<Double>()
        val subtitleCalls = mutableListOf<String>()
        var teardownCount = 0
            private set
        var videoReleases = 0
            private set

        override suspend fun load(item: MediaItem) {
            loads += item
        }

        override suspend fun setPaused(paused: Boolean) {
            pauses += paused
        }
        override suspend fun seekTo(positionSeconds: Double) = Unit
        override suspend fun selectAudioTrack(id: String?) = Unit
        override suspend fun selectSubtitleTrack(id: String?) = Unit
        override suspend fun setPlaybackRate(rate: Double) {
            playbackRates += rate
        }
        override suspend fun setAudioDelay(seconds: Double) = Unit
        override suspend fun setVideoFillsScreen(fills: Boolean) = Unit
        override suspend fun setSubtitleDelay(seconds: Double) = Unit

        // Recorded as an ordered log rather than four lists: applying the
        // stored appearance is one step whose order is the thing worth
        // asserting.
        override suspend fun setSubtitleScale(scale: Double) {
            subtitleCalls += "scale=$scale"
        }

        override suspend fun setSubtitleFont(font: String?) {
            subtitleCalls += "font=$font"
        }

        override suspend fun setSubtitleTrackStyling(keepScript: Boolean) {
            subtitleCalls += "trackStyling=$keepScript"
        }

        override suspend fun setSubtitleOutline(widthPixels: Double) {
            subtitleCalls += "outline=$widthPixels"
        }

        override suspend fun setSubtitleShadow(offsetPixels: Double) {
            subtitleCalls += "shadow=$offsetPixels"
        }

        override suspend fun setSubtitleLift(percent: Int) {
            subtitleCalls += "lift=$percent"
        }

        override suspend fun addSubtitle(url: String) = Unit

        override suspend fun releaseVideoOutput() {
            videoReleases += 1
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }
}
