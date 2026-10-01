package moe.ditto.halo.storage

import moe.ditto.halo.auth.EpochClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class StepClock(var now: Long = 1_000) : EpochClock {
    override fun nowMs(): Long = now
}

class SubtitleChoiceStoreTest {
    private val english = SubtitleChoice(
        kind = SubtitleChoiceKind.External,
        lang = "eng",
        subId = "os-12345",
        fileName = "episode.eng.srt",
    )

    @Test
    fun restoresTheExactChoiceForTheSameVideo() {
        val store = SubtitleChoiceStore(RecordingStore(), StepClock())

        store.remember("tt0944947:1:1", "series:tt0944947", english)

        val restored = store.choiceFor("tt0944947:1:1", "series:tt0944947")
        assertEquals(SubtitleChoiceKind.External, restored?.kind)
        assertEquals("os-12345", restored?.subId)
        assertEquals("episode.eng.srt", restored?.fileName)
    }

    @Test
    fun carriesTheChoiceOverToTheNextEpisode() {
        // The next episode is a different video with no choice of its own, but
        // the viewer's intent for the series still applies.
        val store = SubtitleChoiceStore(RecordingStore(), StepClock())
        store.remember("tt0944947:1:1", "series:tt0944947", english)

        val carried = store.choiceFor("tt0944947:1:2", "series:tt0944947")

        assertEquals("eng", carried?.lang)
    }

    @Test
    fun anExactChoiceBeatsTheSeriesCarryover() {
        val store = SubtitleChoiceStore(RecordingStore(), StepClock())
        store.remember("tt0944947:1:1", "series:tt0944947", english)
        store.remember("tt0944947:1:2", "series:tt0944947", SubtitleChoice(kind = SubtitleChoiceKind.Off))

        assertEquals(SubtitleChoiceKind.Off, store.choiceFor("tt0944947:1:2", "series:tt0944947")?.kind)
        // The first episode keeps what was chosen for it.
        assertEquals(SubtitleChoiceKind.External, store.choiceFor("tt0944947:1:1", "series:tt0944947")?.kind)
    }

    @Test
    fun theStoreStampsTheTime() {
        val clock = StepClock(now = 4_242)
        val store = SubtitleChoiceStore(RecordingStore(), clock)

        val stamped = store.remember("v", "i", english.copy(updatedAt = 1))

        assertEquals(4_242, stamped.updatedAt)
        assertEquals(4_242, store.choiceFor("v", "i")?.updatedAt)
    }

    @Test
    fun rememberedChoicesSurviveRestart() {
        val backing = RecordingStore()
        SubtitleChoiceStore(backing, StepClock()).remember("v", "i", english)

        assertNotNull(SubtitleChoiceStore(backing, StepClock()).choiceFor("v", "i"))
    }

    @Test
    fun forgetsTheOldestChoicesPastTheCap() {
        val clock = StepClock()
        val store = SubtitleChoiceStore(RecordingStore(), clock)
        // Each choice writes a video key and an item key, so 160 videos with
        // distinct items is comfortably past the cap.
        repeat(160) { index ->
            clock.now += 1_000
            store.remember("video-$index", "item-$index", english)
        }

        assertNull(store.choiceFor("video-0", "item-0"))
        assertNotNull(store.choiceFor("video-159", "item-159"))
    }

    @Test
    fun oneUnreadableRecordDoesNotDiscardTheRest() {
        // Losing every remembered choice because a single entry was written by
        // a different build would be a silent, total regression.
        val backing = RecordingStore(
            mapOf(
                StorageKeys.SubtitleChoices to """
                {"video:good":{"kind":"external","lang":"eng","updatedAt":5},
                 "video:broken":{"kind":{"unexpected":"shape"},"updatedAt":6}}
                """.trimIndent(),
            ),
        )
        val store = SubtitleChoiceStore(backing, StepClock())

        assertEquals("eng", store.choiceFor("good", "none")?.lang)
        assertNull(store.choiceFor("broken", "none"))
    }

    @Test
    fun unreadableStorageStartsEmptyRatherThanFailing() {
        val backing = RecordingStore(mapOf(StorageKeys.SubtitleChoices to "not json at all"))
        val store = SubtitleChoiceStore(backing, StepClock())

        assertNull(store.choiceFor("v", "i"))
        store.remember("v", "i", english)
        assertNotNull(store.choiceFor("v", "i"))
    }

    @Test
    fun anUnknownKindDegradesInsteadOfLosingTheRecord() {
        // A build that adds a kind must not make older builds unable to read
        // anything at all.
        val backing = RecordingStore(
            mapOf(
                StorageKeys.SubtitleChoices to
                    """{"video:v":{"kind":"holographic","lang":"eng","updatedAt":5}}""",
            ),
        )
        val store = SubtitleChoiceStore(backing, StepClock())

        val choice = store.choiceFor("v", "none")

        assertNotNull(choice)
        assertEquals(SubtitleChoiceKind.Off, choice.kind)
        assertEquals("eng", choice.lang)
    }
}
