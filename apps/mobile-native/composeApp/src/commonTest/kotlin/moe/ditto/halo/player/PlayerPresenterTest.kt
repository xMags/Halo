package moe.ditto.halo.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerPresenterTest {
    private val first = MediaItem("one", "Episode one", "https://example.test/one.mp4")
    private val second = MediaItem("two", "Episode two", "https://example.test/two.mp4")

    @Test
    fun naturalEndLoadsNextOnSameCore() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first, second)

        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(listOf(first, second), port.loads)
        assertEquals(0, port.teardownCount)
        assertEquals(second, presenter.state.current)
        assertEquals(PlaybackStatus.Loading, presenter.state.status)
        assertNull(presenter.state.queuedNext)
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
        presenter.start(first, second)

        presenter.onEvent(PlayerEvent.Error("decoder failed"))

        assertEquals(PlaybackStatus.Failed, presenter.state.status)
        assertEquals("decoder failed", presenter.state.error)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun lateNaturalEndAfterErrorCannotTriggerAutoplay() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first, second)

        presenter.onEvent(PlayerEvent.Error("decoder failed"))
        presenter.onEvent(PlayerEvent.NaturalEnd)

        assertEquals(PlaybackStatus.Failed, presenter.state.status)
        assertEquals(listOf(first), port.loads)
    }

    @Test
    fun teardownReleasesCoreWithoutAutoplay() = runTest {
        val port = RecordingPlayerPort()
        val presenter = PlayerPresenter(port)
        presenter.start(first, second)

        presenter.close()

        assertEquals(1, port.teardownCount)
        assertEquals(PlaybackStatus.Released, presenter.state.status)
        assertEquals(listOf(first), port.loads)
        assertNull(presenter.state.queuedNext)
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
        val subtitleDelays = mutableListOf<Double>()
        val subtitleScales = mutableListOf<Double>()
        val subtitleFonts = mutableListOf<String?>()
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

        override suspend fun setSubtitleDelay(seconds: Double) {
            subtitleDelays += seconds
        }

        override suspend fun setSubtitleScale(scale: Double) {
            subtitleScales += scale
        }

        override suspend fun setSubtitleFont(font: String?) {
            subtitleFonts += font
        }

        override suspend fun addSubtitle(url: String) {
            addedSubtitles += url
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }
}
