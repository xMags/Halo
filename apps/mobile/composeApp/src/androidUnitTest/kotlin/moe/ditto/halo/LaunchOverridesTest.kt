package moe.ditto.halo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchOverridesTest {
    @Test
    fun releaseBuildIgnoresEveryExtra() {
        // Any app can start the exported launcher activity, so a release build
        // must not let it reset the session or choose the prefilled server.
        val launch = LaunchOverrides.resolve(
            diagnosticsEnabled = false,
            serverUrl = "https://attacker.example",
            mediaHttpBase = "https://attacker.example/media",
            resetSession = true,
        )

        assertEquals(PlatformDependencies.DefaultServerUrl, launch.serverUrl)
        assertEquals(PlatformDependencies.DefaultMediaHttpBase, launch.mediaHttpBase)
        assertFalse(launch.resetSession)
    }

    @Test
    fun debugBuildHonoursAutomationExtras() {
        val launch = LaunchOverrides.resolve(
            diagnosticsEnabled = true,
            serverUrl = "http://127.0.0.1:18788",
            mediaHttpBase = "  http://127.0.0.1:18788/media  ",
            resetSession = true,
        )

        assertEquals("http://127.0.0.1:18788", launch.serverUrl)
        assertEquals("http://127.0.0.1:18788/media", launch.mediaHttpBase)
        assertTrue(launch.resetSession)
    }

    @Test
    fun debugBuildWithoutExtrasUsesDefaults() {
        val launch = LaunchOverrides.resolve(
            diagnosticsEnabled = true,
            serverUrl = null,
            mediaHttpBase = null,
            resetSession = false,
        )

        assertEquals(PlatformDependencies.DefaultServerUrl, launch.serverUrl)
        assertEquals(PlatformDependencies.DefaultMediaHttpBase, launch.mediaHttpBase)
        assertFalse(launch.resetSession)
    }

    @Test
    fun debugBuildTreatsABlankMediaBaseAsMissing() {
        val launch = LaunchOverrides.resolve(
            diagnosticsEnabled = true,
            serverUrl = null,
            mediaHttpBase = "   ",
            resetSession = false,
        )

        assertEquals(PlatformDependencies.DefaultMediaHttpBase, launch.mediaHttpBase)
    }
}
