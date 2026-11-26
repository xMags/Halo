package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPathPolicyTest {

    private fun decide(
        directAllowed: Boolean = true,
        directFailure: String? = null,
        subtitleSelected: Boolean = false,
        subtitleCodec: String? = null,
        keepScriptStyling: Boolean = false,
    ) = VideoPathPolicy.decide(directAllowed, directFailure, subtitleSelected, subtitleCodec, keepScriptStyling)

    @Test
    fun aProfileWithoutDirectVideoIsAlwaysComposed() {
        assertEquals(VideoPath.Composed, decide(directAllowed = false).path)
        assertEquals(VideoPath.Composed, decide(directAllowed = false, subtitleSelected = true, subtitleCodec = "subrip").path)
    }

    @Test
    fun noSubtitleStaysDirect() {
        assertEquals(VideoPathDecision(VideoPath.Direct, "no subtitle"), decide())
    }

    @Test
    fun textSubtitlesStayDirectWithAppDrawnText() {
        for (codec in listOf("subrip", "srt", "mov_text", "webvtt", "text")) {
            val decision = decide(subtitleSelected = true, subtitleCodec = codec, keepScriptStyling = true)
            assertEquals(VideoPath.Direct, decision.path, codec)
            assertEquals("text subtitle $codec", decision.reason)
        }
    }

    @Test
    fun pictureSubtitlesNeedTheEngine() {
        for (codec in listOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "dvb_teletext", "xsub")) {
            val decision = decide(subtitleSelected = true, subtitleCodec = codec)
            assertEquals(VideoPathDecision(VideoPath.Composed, "picture subtitle $codec"), decision)
        }
    }

    @Test
    fun anUnknownOrMissingCodecIsTreatedAsAPicture() {
        assertEquals(VideoPath.Composed, decide(subtitleSelected = true, subtitleCodec = "some_new_bitmap_sub").path)
        assertEquals(VideoPath.Composed, decide(subtitleSelected = true, subtitleCodec = null).path)
    }

    @Test
    fun aScriptKeepsDirectOnlyWhenItsStylingIsReplaced() {
        for (codec in listOf("ass", "ssa")) {
            assertEquals(VideoPath.Direct, decide(subtitleSelected = true, subtitleCodec = codec, keepScriptStyling = false).path)
            val kept = decide(subtitleSelected = true, subtitleCodec = codec, keepScriptStyling = true)
            assertEquals(VideoPath.Composed, kept.path)
            assertEquals("styled subtitle $codec with its own styling kept", kept.reason)
        }
    }

    @Test
    fun codecNamesAreMatchedWithoutCase() {
        assertEquals(VideoPath.Direct, decide(subtitleSelected = true, subtitleCodec = "SubRip").path)
        assertEquals(VideoPath.Composed, decide(subtitleSelected = true, subtitleCodec = "HDMV_PGS_SUBTITLE").path)
    }

    @Test
    fun aDecoderFailureHoldsComposedWhateverTheSubtitle() {
        val decision = decide(directFailure = "hardware decoder not used, hwdec-current no", subtitleSelected = true, subtitleCodec = "subrip")
        assertEquals(VideoPathDecision(VideoPath.Composed, "hardware decoder not used, hwdec-current no"), decision)
    }

    @Test
    fun theDecisionDescribesItselfForTheLog() {
        assertEquals(
            "composed (picture subtitle hdmv_pgs_subtitle)",
            decide(subtitleSelected = true, subtitleCodec = "hdmv_pgs_subtitle").describe(),
        )
    }

    @Test
    fun onlyTheSurfaceDecoderCountsAsDirect() {
        assertNull(VideoPathPolicy.directFailure("mediacodec", videoSelected = true, hasVideoTrack = true))
        assertNotNull(VideoPathPolicy.directFailure("no", videoSelected = true, hasVideoTrack = true))
        assertNotNull(VideoPathPolicy.directFailure("mediacodec-copy", videoSelected = true, hasVideoTrack = true))
    }

    @Test
    fun aDroppedVideoTrackIsAFailureButAudioOnlyIsNot() {
        assertNotNull(VideoPathPolicy.directFailure(null, videoSelected = false, hasVideoTrack = true))
        assertNull(VideoPathPolicy.directFailure(null, videoSelected = false, hasVideoTrack = false))
        // A selected track whose decoder is not up yet says nothing.
        assertNull(VideoPathPolicy.directFailure(null, videoSelected = true, hasVideoTrack = true))
    }
}

class VideoPathTrackerTest {

    @Test
    fun aDirectProfileStartsDirectAndTheOtherStartsComposed() {
        assertEquals(VideoPath.Direct, VideoPathTracker(directAllowed = true).path)
        assertEquals(VideoPath.Composed, VideoPathTracker(directAllowed = false).path)
    }

    @Test
    fun aPictureSubtitleWantsComposedAndATextOneWantsDirectBack() {
        val tracker = VideoPathTracker(directAllowed = true)
        assertTrue(tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle"))
        assertTrue(tracker.wantsSwitch())
        tracker.adopt(tracker.decision().path)
        assertEquals(VideoPath.Composed, tracker.path)

        assertTrue(tracker.onSubtitleSelected(selected = true, codec = "subrip"))
        assertEquals(VideoPath.Direct, tracker.decision().path)
    }

    @Test
    fun turningTheSubtitleOffReturnsToDirect() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "dvd_subtitle")
        tracker.adopt(VideoPath.Composed)
        assertTrue(tracker.onSubtitleSelected(selected = false, codec = "dvd_subtitle"))
        assertEquals(VideoPathDecision(VideoPath.Direct, "no subtitle"), tracker.decision())
    }

    @Test
    fun anUnchangedSelectionDoesNotRestartTheDebounce() {
        val tracker = VideoPathTracker(directAllowed = true)
        assertTrue(tracker.onSubtitleSelected(selected = true, codec = "subrip"))
        assertFalse(tracker.onSubtitleSelected(selected = true, codec = "subrip"))
        // With nothing selected the codec of the last row is irrelevant.
        assertTrue(tracker.onSubtitleSelected(selected = false, codec = "subrip"))
        assertFalse(tracker.onSubtitleSelected(selected = false, codec = null))
    }

    @Test
    fun theStylingSettingMovesAScriptBetweenPaths() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "ass")
        // The player starts by keeping a script's own styling.
        assertEquals(VideoPath.Composed, tracker.decision().path)
        assertTrue(tracker.onTrackStyling(keepScript = false))
        assertEquals(VideoPath.Direct, tracker.decision().path)
        assertFalse(tracker.onTrackStyling(keepScript = false))
    }

    @Test
    fun aDecoderFailureCountsOnceAndHoldsUntilTheNextFile() {
        val tracker = VideoPathTracker(directAllowed = true)
        assertTrue(tracker.onDirectFailed("hardware decoder not used, hwdec-current no"))
        assertFalse(tracker.onDirectFailed("video chain dropped the picture"))
        tracker.adopt(tracker.decision().path)

        // A text subtitle, or none, would suit the direct path, but not this file.
        tracker.onSubtitleSelected(selected = true, codec = "subrip")
        assertEquals(VideoPath.Composed, tracker.decision().path)
        assertFalse(tracker.wantsSwitch())

        val next = tracker.onLoadRequested()
        assertEquals(VideoPathDecision(VideoPath.Direct, "new file"), next)
        assertEquals(VideoPathDecision(VideoPath.Direct, "no subtitle"), tracker.decision())
    }

    @Test
    fun aProfileWithoutDirectVideoNeverRecordsAFailureOrSwitches() {
        val tracker = VideoPathTracker(directAllowed = false)
        assertFalse(tracker.onDirectFailed("anything"))
        tracker.onSubtitleSelected(selected = true, codec = "subrip")
        assertFalse(tracker.wantsSwitch())
        assertEquals(VideoPath.Composed, tracker.onLoadRequested().path)
    }

    @Test
    fun mpvsOwnPickSettlesLongAndTheAppsChoiceSettlesShort() {
        val tracker = VideoPathTracker(directAllowed = true)
        assertTrue(tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle"))
        assertEquals(SubtitleSettle.Provisional, tracker.settle())
        tracker.onSubtitleSelectedByApp()
        assertEquals(SubtitleSettle.Deliberate, tracker.settle())
    }

    @Test
    fun aProvisionalPictureTrackReplacedByTheAppsTextTrackNeverSwitches() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        // Due only after the long wait, which the app's choice interrupts.
        assertTrue(tracker.wantsSwitch())
        assertEquals(SubtitleSettle.Provisional, tracker.settle())

        tracker.onSubtitleSelectedByApp()
        tracker.onSubtitleSelected(selected = true, codec = "subrip")
        assertFalse(tracker.wantsSwitch())
        assertEquals(VideoPath.Direct, tracker.path)
    }

    @Test
    fun aDeliberatePictureTrackSwitchesUnderTheShortSettle() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelectedByApp()
        tracker.onSubtitleSelected(selected = true, codec = "dvd_subtitle")
        assertTrue(tracker.wantsSwitch())
        assertEquals(SubtitleSettle.Deliberate, tracker.settle())
        assertEquals(VideoPath.Composed, tracker.decision().path)
    }

    @Test
    fun theChoiceIsForgottenOnLoadAndOnStop() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelectedByApp()
        tracker.onLoadRequested()
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        assertEquals(SubtitleSettle.Provisional, tracker.settle())

        tracker.onSubtitleSelectedByApp()
        tracker.onStopped()
        tracker.onSubtitleSelected(selected = true, codec = "dvb_subtitle")
        assertEquals(SubtitleSettle.Provisional, tracker.settle())
    }

    @Test
    fun aStylingChangeSettlesShortWithoutMakingTheSelectionDeliberate() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "ass")
        assertTrue(tracker.onTrackStyling(keepScript = false))
        assertEquals(SubtitleSettle.Deliberate, tracker.settle())
        // The next pick by mpv itself is still provisional.
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        assertEquals(SubtitleSettle.Provisional, tracker.settle())
    }

    @Test
    fun anAddedSubtitleSettlesLongUntilTheSelectionChangesThenShort() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        tracker.onSubtitleAdded()
        // The picture track is a leftover until mpv selects the added one.
        assertEquals(SubtitleSettle.Provisional, tracker.settle())
        assertTrue(tracker.onSubtitleSelected(selected = true, codec = "subrip"))
        assertEquals(SubtitleSettle.Deliberate, tracker.settle())
    }

    @Test
    fun theAddedTextTrackOnceSelectedWantsNoSwitch() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        tracker.onSubtitleAdded()
        tracker.onSubtitleSelected(selected = true, codec = "subrip")
        assertFalse(tracker.wantsSwitch())
        assertEquals(VideoPathDecision(VideoPath.Direct, "text subtitle subrip"), tracker.decision())
    }

    @Test
    fun waitingForAnAddedTrackEndsOnLoadStopOrAnExplicitSelect() {
        val loaded = VideoPathTracker(directAllowed = true)
        loaded.onSubtitleAdded()
        loaded.onLoadRequested()
        loaded.onSubtitleSelectedByApp()
        assertEquals(SubtitleSettle.Deliberate, loaded.settle())

        val stopped = VideoPathTracker(directAllowed = true)
        stopped.onSubtitleAdded()
        stopped.onStopped()
        stopped.onSubtitleSelectedByApp()
        assertEquals(SubtitleSettle.Deliberate, stopped.settle())

        val selected = VideoPathTracker(directAllowed = true)
        selected.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        selected.onSubtitleAdded()
        selected.onSubtitleSelectedByApp()
        assertEquals(SubtitleSettle.Deliberate, selected.settle())
    }

    @Test
    fun anAddedTrackThatNeverArrivesLeavesTheLeftoverOnTheLongSettle() {
        val tracker = VideoPathTracker(directAllowed = true)
        tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle")
        tracker.onSubtitleAdded()
        // An unchanged report does not end the wait.
        assertFalse(tracker.onSubtitleSelected(selected = true, codec = "hdmv_pgs_subtitle"))
        assertEquals(SubtitleSettle.Provisional, tracker.settle())
        assertTrue(tracker.wantsSwitch())
        assertEquals(VideoPath.Composed, tracker.decision().path)
    }

    @Test
    fun theFileCountsAsLoadedOnlyBetweenFileLoadedAndTheNextLoadOrStop() {
        val tracker = VideoPathTracker(directAllowed = true)
        assertFalse(tracker.fileLoaded)
        tracker.onFileLoaded()
        assertTrue(tracker.fileLoaded)
        tracker.onStopped()
        assertFalse(tracker.fileLoaded)
        tracker.onFileLoaded()
        tracker.onLoadRequested()
        assertFalse(tracker.fileLoaded)
    }
}
