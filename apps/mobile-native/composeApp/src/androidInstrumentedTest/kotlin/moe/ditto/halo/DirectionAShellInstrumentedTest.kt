package moe.ditto.halo

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectionAShellInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        MainActivity::class.java,
    ).apply {
        putExtra("serverUrl", FixtureServerUrl)
        putExtra("resetSession", true)
    }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @Test
    fun signedInShellOpensDirectionAScreens() {
        composeRule.onNodeWithContentDescription("Continue").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Local mode discovered")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Username") and hasSetTextAction()).performTextInput("fixture-user")
        composeRule.onNode(hasText("Password") and hasSetTextAction()).performTextInput("fixture-pass")
        composeRule.onNodeWithContentDescription("Sign In").performClick()

        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasText("Downloads") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(
                hasText(
                    "Downloads live here. Pick a source on any title and tap the download icon, " +
                        "then it plays with no network at all.",
                ),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasText("Settings") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("ADDONS")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val FixtureServerUrl = "http://127.0.0.1:18788"
    }
}
