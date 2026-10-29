package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleAddCommandTest {
    private val url = "/cache/subtitles/0f3a.srt"

    @Test
    fun noTitleOrLanguageLeavesBothOff() {
        assertEquals(listOf("sub-add", url, "cached"), subAddArguments(url, null, null))
    }

    @Test
    fun titleAndLanguageFollowTheFlag() {
        assertEquals(
            listOf("sub-add", url, "cached", "opensubs:42", "eng"),
            subAddArguments(url, "opensubs:42", "eng"),
        )
    }

    @Test
    fun titleAloneLeavesTheLanguageOff() {
        assertEquals(
            listOf("sub-add", url, "cached", "Downloaded subtitle"),
            subAddArguments(url, "Downloaded subtitle", null),
        )
    }

    @Test
    fun languageAloneTakesAnEmptyTitleInFrontOfIt() {
        assertEquals(listOf("sub-add", url, "cached", "", "eng"), subAddArguments(url, null, "eng"))
    }

    @Test
    fun blankValuesCountAsAbsent() {
        assertEquals(listOf("sub-add", url, "cached"), subAddArguments(url, " ", ""))
        assertEquals(listOf("sub-add", url, "cached", "", "eng"), subAddArguments(url, "  ", "eng"))
    }
}
