package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRedactionTest {
    @Test
    fun aLinkKeepsOnlyItsSchemeAndHost() {
        assertEquals(
            "Playing: https://cdn.debrid.test/…",
            redactLinks("Playing: https://cdn.debrid.test/dl/SECRETTOKEN/movie.mkv?key=abc"),
        )
    }

    @Test
    fun credentialsInFrontOfTheHostGoToo() {
        assertEquals("Failed to open http://host.test/…", redactLinks("Failed to open http://user:pass@host.test/a.mkv."))
    }

    @Test
    fun aLineWithoutALinkIsLeftAsItIs() {
        assertEquals("VO: [gpu] 1920x1080 yuv420p", redactLinks("VO: [gpu] 1920x1080 yuv420p"))
    }
}
