package moe.ditto.halo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.Manifest
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.Me
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.screens.AddonOwnership
import moe.ditto.halo.screens.SettingsContent
import moe.ditto.halo.screens.SettingsLanguageKind
import moe.ditto.halo.screens.SettingsUiState
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DirectionAUiInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        MainActivity::class.java,
    )

    @get:Rule(order = 0)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @Test
    fun settingsRendersRealStateAndDispatchesActions() {
        var added: AddonOwnership? = null
        var toggled: Pair<String, AddonOwnership>? = null
        var autoplay = true
        var signedOut = false

        val addon = AddonEntry(
            id = "fixture-catalogs",
            transportUrl = "https://fixture.example/manifest.json",
            manifest = Manifest(
                id = "fixture-catalogs",
                version = "1.0.0",
                name = "Fixture Catalogs",
                description = "Movies and series",
                catalogs = listOf(ManifestCatalog(type = "movie", id = "popular", name = "Popular")),
            ),
            position = 0,
        )
        val state = SettingsUiState(
            serverUrl = "https://halo.example",
            addons = AddonsResponse(user = listOf(addon)),
            account = Me("user-1", "dev", isAdmin = false, createdAt = 17),
            settings = UserSettings.Empty.withAutoplayNextEpisode(true),
        )

        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HaloTheme {
                    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
                        SettingsContent(
                            state = state,
                            userAddonUrl = "",
                            globalAddonUrl = "",
                            onUserAddonUrlChange = {},
                            onGlobalAddonUrlChange = {},
                            onAddAddon = { added = it },
                            onToggleCatalogs = { entry, ownership -> toggled = entry.id to ownership },
                            onRemoveAddon = { _, _ -> },
                            onOpenLanguage = { kind ->
                                assertTrue(
                                    kind == SettingsLanguageKind.Audio || kind == SettingsLanguageKind.Subtitles,
                                )
                            },
                            onAutoplayChange = { autoplay = it },
                            onSignOut = { signedOut = true },
                            onOpenDebugGate = null,
                        )
                    }
                }
            }
        }

        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("Fixture Catalogs").assertExists()
        rule.onNodeWithContentDescription("Add Personal addon URL").performClick()
        rule.onNodeWithContentDescription("Hide Fixture Catalogs catalogs").performClick()

        assertTrue(added == AddonOwnership.User)
        assertTrue(toggled == ("fixture-catalogs" to AddonOwnership.User))

        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Autoplay next episode"))
        rule.onNodeWithContentDescription("Autoplay next episode").performClick()
        assertFalse(autoplay)

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Sign Out"))
        rule.onNodeWithText("Sign Out").performClick()
        assertTrue(signedOut)

        writeScreenshot("direction-a-settings.png")
    }

    private fun writeScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), name)
        output.outputStream().use { stream ->
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(output.length() > 0L)
    }
}
