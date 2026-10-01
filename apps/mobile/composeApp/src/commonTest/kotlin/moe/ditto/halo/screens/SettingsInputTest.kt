package moe.ditto.halo.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsInputTest {
    @Test
    fun acceptsOnlyCompleteHttpManifestUrls() {
        assertEquals(
            "https://addon.example/manifest.json",
            normalizedAddonUrl("  https://addon.example/manifest.json  "),
        )
        assertEquals(
            "http://192.168.1.5:7000/manifest.json",
            normalizedAddonUrl("http://192.168.1.5:7000/manifest.json"),
        )
        assertNull(normalizedAddonUrl(""))
        assertNull(normalizedAddonUrl("manifest.json"))
        assertNull(normalizedAddonUrl("ftp://addon.example/manifest.json"))
    }
}
