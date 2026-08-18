package moe.ditto.halo.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerPresenterTest {
    private val first = MediaItem("one", "Episode one", "https://example.test/one.mp4")
    private val second = MediaItem("two", "Episode two", "https://example.test/two.mp4")

    @Test
    fun naturalEndNeverLoadsAnotherItem() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(listOf(first), port.loads)
        assertEquals(0, port.teardownCount)
        assertEquals(first, presenter.state.current)
        assertEquals(PlaybackStatus.Ended, presenter.state.status)
    }

    @Test
    fun naturalEndWithoutNextIsTerminalButDoesNotTeardown() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(PlaybackStatus.Ended, presenter.state.status)
        assertEquals(0, port.teardownCount)
    }

    @Test
    fun errorDoesNotMasqueradeAsNaturalEnd() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.onEvent(PlayerEvent.Error("decoder failed"))

        assertEquals(PlaybackStatus.Failed, presenter.state.status)
        assertEquals("decoder failed", presenter.state.error)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun lateNaturalEndAfterErrorCannotTriggerAutoplay() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.onEvent(PlayerEvent.Error("decoder failed"))
        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(PlaybackStatus.Failed, presenter.state.status)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun teardownReleasesCoreWithoutAutoplay() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.close()

        assertEquals(1, port.teardownCount)
        assertEquals(PlaybackStatus.Released, presenter.state.status)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun dynamicTracksReplacePreviousSnapshot() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)
        val tracks = PlayerTracks(
            audio = listOf(PlayerTrack("a1", "English")),
            subtitles = listOf(PlayerTrack("s1", "English ASS")),
            selectedAudioId = "a1",
        )

        presenter.onEvent(PlayerEvent.TracksChanged(tracks))

        assertEquals(tracks, presenter.state.tracks)
    }

    @Test
    fun initialCoreObservationsBeforeFirstLoadStayIdle() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)

        // A freshly created core reports its idle property values.
        presenter.onEvent(PlayerEvent.PauseChanged(paused = false))
        presenter.onEvent(PlayerEvent.PositionChanged(0.0))
        presenter.onEvent(PlayerEvent.Ready(durationSeconds = null))
        presenter.onEvent(PlayerEvent.TracksChanged(PlayerTracks()))

        assertEquals(PlaybackStatus.Idle, presenter.state.status)

        // A later real start must still work.
        presenter.start(first)
        assertEquals(PlaybackStatus.Loading, presenter.state.status)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun playbackRatePassesThroughAndEchoesIntoState() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.setPlaybackRate(1.5)

        assertEquals(listOf(1.5), port.playbackRates)
        assertEquals(1.5, presenter.state.playbackRate)
    }

    @Test
    fun invalidPlaybackRatesNeverReachTheCore() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.setPlaybackRate(0.0)
        presenter.setPlaybackRate(-1.0)
        presenter.setPlaybackRate(Double.NaN)
        presenter.setPlaybackRate(Double.POSITIVE_INFINITY)

        assertEquals(emptyList<Double>(), port.playbackRates)
        assertEquals(1.0, presenter.state.playbackRate)
    }

    @Test
    fun playbackRateAfterReleaseIsDropped() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)
        presenter.close()

        presenter.setPlaybackRate(1.5)

        assertEquals(emptyList<Double>(), port.playbackRates)
    }

    @Test
    fun bufferingFiguresArriveTogetherAndClearCompletely() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)

        presenter.onEvent(
            PlayerEvent.BufferingChanged(
                active = true,
                percent = 62,
                bytesPerSecond = 1_887_437,
                cachedSeconds = 12.0,
            ),
        )

        assertEquals(PlayerBuffering(62, 1_887_437, 12.0), presenter.state.buffering)

        // Clearing must drop the figures with it: a stale rate left behind the
        // "not buffering" flag is the one thing a later reader could misread.
        presenter.onEvent(PlayerEvent.BufferingChanged(active = false))

        assertNull(presenter.state.buffering)
    }

    @Test
    fun bufferingFiguresAHostCannotReportStayAbsent() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)

        presenter.onEvent(
            PlayerEvent.BufferingChanged(
                active = true,
                percent = 140,
                bytesPerSecond = -1,
                cachedSeconds = Double.NaN,
            ),
        )

        // The percentage is clamped because it is a real reading out of range;
        // the other two are dropped because there is no honest value to show.
        assertEquals(PlayerBuffering(100, null, null), presenter.state.buffering)
    }

    @Test
    fun bufferingOverlayCannotSurviveUnderneathTheErrorCard() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)
        presenter.onEvent(PlayerEvent.BufferingChanged(active = true, percent = 40))

        // A stall that never recovers is exactly how a stream reports failure.
        presenter.onEvent(PlayerEvent.Error("connection reset by peer"))

        assertEquals(PlaybackStatus.Failed, presenter.state.status)
        assertNull(presenter.state.buffering)
    }

    @Test
    fun bufferedPositionTracksTheCacheAndIgnoresJunkReadings() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)

        presenter.onEvent(PlayerEvent.BufferedPositionChanged(184.0))
        assertEquals(184.0, presenter.state.bufferedPositionSeconds)

        presenter.onEvent(PlayerEvent.BufferedPositionChanged(Double.NaN))
        presenter.onEvent(PlayerEvent.BufferedPositionChanged(-3.0))

        assertEquals(184.0, presenter.state.bufferedPositionSeconds)
    }

    @Test
    fun bufferingStateClearsAtNaturalEnd() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.start(first)
        presenter.onEvent(PlayerEvent.BufferingChanged(active = true, percent = 30))
        presenter.onEvent(PlayerEvent.BufferedPositionChanged(184.0))

        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(first, presenter.state.current)
        assertEquals(PlaybackStatus.Ended, presenter.state.status)
        assertNull(presenter.state.buffering)
        assertEquals(184.0, presenter.state.bufferedPositionSeconds)
    }

    @Test
    fun engineControlEchoesSurviveLoadingASource() = runTest {
        val presenter = PlayerPresenter(RecordingPlayerPort())
        presenter.setPlaybackRate(1.5)
        presenter.setVideoFillsScreen(true)
        presenter.setAudioDelay(-0.15)
        presenter.setSubtitleDelay(0.4)
        presenter.setSubtitleScale(1.25)
        presenter.setSubtitleFont("Inter")
        presenter.setSubtitleTrackStyling(false)

        presenter.start(first)

        assertEquals(1.5, presenter.state.playbackRate)
        assertEquals(true, presenter.state.videoFillsScreen)
        assertEquals(-0.15, presenter.state.audioDelaySeconds)
        assertEquals(0.4, presenter.state.subtitleDelaySeconds)
        assertEquals(1.25, presenter.state.subtitleScale)
        assertEquals("Inter", presenter.state.subtitleFont)
        assertEquals(false, presenter.state.subtitleTrackStyling)
    }

    @Test
    fun liveSubtitleControlsPassThroughAndEchoIntoState() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.setSubtitleDelay(1.5)
        presenter.setSubtitleScale(2.0)
        presenter.setSubtitleFont("Courier New")
        presenter.addSubtitle("http://127.0.0.1:18787/media/sample4k.ass")

        assertEquals(listOf(1.5), port.subtitleDelays)
        assertEquals(listOf(2.0), port.subtitleScales)
        assertEquals(listOf<String?>("Courier New"), port.subtitleFonts)
        assertEquals(listOf("http://127.0.0.1:18787/media/sample4k.ass"), port.addedSubtitles)
        assertEquals(1.5, presenter.state.subtitleDelaySeconds)
        assertEquals(2.0, presenter.state.subtitleScale)
        assertEquals("Courier New", presenter.state.subtitleFont)
    }

    @Test
    fun audioDelayPassesThroughInBothDirectionsAndEchoesIntoState() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        // Negative is the common case: sound that arrives late needs pulling
        // forward, so a delay that only went one way would fix half the desyncs.
        presenter.setAudioDelay(-0.15)
        presenter.setAudioDelay(0.25)
        presenter.setAudioDelay(Double.NaN)

        assertEquals(listOf(-0.15, 0.25), port.audioDelays)
        assertEquals(0.25, presenter.state.audioDelaySeconds)
    }

    @Test
    fun trackStylingPassesThroughAndEchoesIntoState() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        // The switch defaults on, so the first meaningful move is off.
        presenter.setSubtitleTrackStyling(false)

        assertEquals(listOf(false), port.trackStylings)
        assertEquals(false, presenter.state.subtitleTrackStyling)
    }

    @Test
    fun negativeOutlineAndShadowNeverReachTheCore() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.setSubtitleOutline(0.0)
        presenter.setSubtitleOutline(-1.0)
        presenter.setSubtitleOutline(Double.NaN)
        presenter.setSubtitleShadow(2.0)
        presenter.setSubtitleShadow(-2.0)

        // Zero is a real choice (no outline); a negative one is a bad reading.
        assertEquals(listOf(0.0), port.outlineWidths)
        assertEquals(listOf(2.0), port.shadowOffsets)
    }

    @Test
    fun subtitleStylingAfterReleaseIsDropped() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)
        presenter.close()

        presenter.setSubtitleTrackStyling(false)
        presenter.setSubtitleOutline(1.0)
        presenter.setSubtitleShadow(2.0)
        presenter.setSubtitleLift(12)

        assertEquals(emptyList<Boolean>(), port.trackStylings)
        assertEquals(emptyList<Double>(), port.outlineWidths)
        assertEquals(emptyList<Double>(), port.shadowOffsets)
        assertEquals(emptyList<Int>(), port.lifts)
    }

    @Test
    fun invalidSubtitleValuesNeverReachTheCore() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)

        presenter.setSubtitleDelay(Double.NaN)
        presenter.setSubtitleScale(0.0)
        presenter.setSubtitleScale(-1.0)
        presenter.addSubtitle("  ")

        assertEquals(emptyList<Double>(), port.subtitleDelays)
        assertEquals(emptyList<Double>(), port.subtitleScales)
        assertEquals(emptyList<String>(), port.addedSubtitles)
    }

    @Test
    fun subtitleControlsAfterReleaseAreDropped() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first)
        presenter.close()

        presenter.setSubtitleDelay(1.0)
        presenter.setSubtitleScale(1.5)
        presenter.setSubtitleFont("Courier New")
        presenter.addSubtitle("http://127.0.0.1:18787/media/sample4k.ass")

        assertEquals(emptyList<Double>(), port.subtitleDelays)
        assertEquals(emptyList<Double>(), port.subtitleScales)
        assertEquals(emptyList<String?>(), port.subtitleFonts)
        assertEquals(emptyList<String>(), port.addedSubtitles)
    }

    private class RecordingPlayerPort : PlayerPort {
        val loads = mutableListOf<MediaItem>()
        val playbackRates = mutableListOf<Double>()
        val audioDelays = mutableListOf<Double>()
        val videoFills = mutableListOf<Boolean>()
        val subtitleDelays = mutableListOf<Double>()
        val subtitleScales = mutableListOf<Double>()
        val subtitleFonts = mutableListOf<String?>()
        val trackStylings = mutableListOf<Boolean>()
        val outlineWidths = mutableListOf<Double>()
        val shadowOffsets = mutableListOf<Double>()
        val lifts = mutableListOf<Int>()
        val addedSubtitles = mutableListOf<String>()
        var teardownCount = 0
            private set

        override suspend fun load(item: MediaItem) {
            loads += item
        }

        override suspend fun setPaused(paused: Boolean) = Unit
        override suspend fun seekTo(positionSeconds: Double) = Unit
        override suspend fun selectAudioTrack(id: String?) = Unit
        override suspend fun selectSubtitleTrack(id: String?) = Unit

        override suspend fun setPlaybackRate(rate: Double) {
            playbackRates += rate
        }

        override suspend fun setAudioDelay(seconds: Double) {
            audioDelays += seconds
        }

        override suspend fun setVideoFillsScreen(fills: Boolean) {
            videoFills += fills
        }

        override suspend fun setSubtitleDelay(seconds: Double) {
            subtitleDelays += seconds
        }

        override suspend fun setSubtitleScale(scale: Double) {
            subtitleScales += scale
        }

        override suspend fun setSubtitleFont(font: String?) {
            subtitleFonts += font
        }

        override suspend fun setSubtitleTrackStyling(keepScript: Boolean) {
            trackStylings += keepScript
        }

        override suspend fun setSubtitleOutline(widthPixels: Double) {
            outlineWidths += widthPixels
        }

        override suspend fun setSubtitleShadow(offsetPixels: Double) {
            shadowOffsets += offsetPixels
        }

        override suspend fun setSubtitleLift(percent: Int) {
            lifts += percent
        }

        override suspend fun addSubtitle(url: String) {
            addedSubtitles += url
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }
}
