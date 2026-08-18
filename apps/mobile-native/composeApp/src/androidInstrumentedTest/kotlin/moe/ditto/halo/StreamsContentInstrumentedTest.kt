package moe.ditto.halo

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.StreamsContent
import moe.ditto.halo.ui.HaloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamsContentInstrumentedTest {
    private val launchIntent = Intent(
        ApplicationProvider.getApplicationContext<Context>(),
        MainActivity::class.java,
    )

    @get:Rule(order = 0)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @Test
    fun allAddonFailuresExposeSafeReasonsAndDisableBusyRetry() {
        val state = mutableStateOf(
            QueryState(
                value = StreamsResult(
                    errors = listOf(
                        AddonError(
                            id = "opaque-addon-id",
                            message = "raw https://secret.test/token",
                            name = "Slow addon",
                            code = "timeout",
                        ),
                    ),
                ),
            ),
        )
        var retries = 0
        render(state, onRetry = { retries += 1 })

        rule.onNodeWithText("No addons could load sources.").assertExists()
        rule.onNodeWithText("Slow addon timed out.").assertExists()
        rule.onNodeWithText("raw https://secret.test/token").assertDoesNotExist()
        rule.onNodeWithText("opaque-addon-id").assertDoesNotExist()
        rule.onNodeWithContentDescription("Retry sources").performClick()
        assertEquals(1, retries)

        activityRule.scenario.onActivity {
            state.value = state.value.copy(isFetching = true)
        }
        rule.onNodeWithContentDescription("Retrying sources").assertExists().assertIsNotEnabled()
    }

    @Test
    fun partialFailureKeepsThePlayableSourceAndANonblockingRetry() {
        val state = mutableStateOf(
            QueryState(
                value = StreamsResult(
                    results = listOf(
                        AddonStreams(
                            addon = AddonSource("working-id", "Working addon"),
                            streams = listOf(Stream(url = "https://cdn.test/video.mkv", name = "4K source")),
                        ),
                    ),
                    errors = listOf(
                        AddonError(
                            id = "failed-id",
                            message = "safe legacy compatibility text",
                            name = "Broken addon",
                            code = "invalid_response",
                        ),
                    ),
                ),
            ),
        )
        render(state)

        rule.onNodeWithText("Some addons could not load sources.").assertExists()
        rule.onNodeWithText("Broken addon returned invalid data.").assertExists()
        rule.onNodeWithText("4K source").assertExists()
        rule.onNodeWithContentDescription("Retry sources").assertExists()
    }

    @Test
    fun outerFailureAndValidEmptyAnswerRemainDistinct() {
        val state = mutableStateOf<QueryState<StreamsResult>>(
            QueryState(error = HaloApiException(502, "raw gateway body with token")),
        )
        render(state)

        rule.onNodeWithText("Halo could not load sources right now.").assertExists()
        rule.onNodeWithText("raw gateway body with token").assertDoesNotExist()
        rule.onNodeWithContentDescription("Retry sources").assertExists()

        activityRule.scenario.onActivity {
            state.value = QueryState(value = StreamsResult())
        }
        rule.onNodeWithText(
            "No playable sources. Install a stream addon (e.g. a debrid-backed one) in Settings.",
        ).assertExists()
    }

    private fun render(
        state: androidx.compose.runtime.MutableState<QueryState<StreamsResult>>,
        onRetry: () -> Unit = {},
    ) {
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HaloTheme {
                    StreamsContent(
                        query = state.value,
                        onRetry = onRetry,
                        onPlay = { _, _ -> },
                    )
                }
            }
        }
    }
}
