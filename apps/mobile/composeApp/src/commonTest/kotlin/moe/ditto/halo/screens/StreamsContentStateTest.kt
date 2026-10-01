package moe.ditto.halo.screens

import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.MalformedResponseException
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamsResult
import moe.ditto.halo.cache.QueryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamsContentStateTest {
    private val playable = AddonStreams(
        addon = AddonSource("opaque-success", "Working addon"),
        streams = listOf(Stream(url = "https://cdn.test/video.mkv")),
    )
    private val timeout = AddonError(
        id = "opaque-failure",
        message = "legacy raw https://secret.test/token",
        name = "Slow addon",
        code = "timeout",
    )

    @Test
    fun wholeRequestFailuresUseSafeCategoryMessagesAndKeepRetryBusyState() {
        val serverFailure = assertIs<StreamsContentState.RequestFailure>(
            streamsContentState(
                QueryState(
                    isFetching = true,
                    error = HaloApiException(502, "raw body https://secret.test/token"),
                ),
            ),
        )
        assertEquals("Halo could not load sources right now.", serverFailure.message)
        assertTrue(serverFailure.isFetching)

        val malformed = assertIs<StreamsContentState.RequestFailure>(
            streamsContentState(QueryState(error = MalformedResponseException())),
        )
        assertEquals("Halo returned an invalid source response.", malformed.message)
    }

    @Test
    fun allAddonFailureIsDifferentFromAValidEmptyAnswer() {
        val failed = assertIs<StreamsContentState.AllAddonsFailed>(
            streamsContentState(QueryState(value = StreamsResult(errors = listOf(timeout)))),
        )
        assertEquals(listOf(timeout), failed.failures)

        assertIs<StreamsContentState.NoSources>(
            streamsContentState(QueryState(value = StreamsResult())),
        )
    }

    @Test
    fun partialFailureKeepsPlayableSourcesAndTheWarning() {
        val state = assertIs<StreamsContentState.Sources>(
            streamsContentState(
                QueryState(
                    value = StreamsResult(results = listOf(playable), errors = listOf(timeout)),
                    isFetching = true,
                ),
            ),
        )

        assertEquals(listOf(playable), state.groups)
        assertEquals(listOf(timeout), state.failures)
        assertTrue(state.isFetching)
    }

    @Test
    fun addonFailureFormattingNeverUsesOpaqueIdsOrLegacyMessages() {
        val text = safeAddonFailure(timeout)

        assertEquals("Slow addon timed out.", text)
        assertFalse("opaque-failure" in text)
        assertFalse("secret.test" in text)
    }

    @Test
    fun olderServerErrorsFallBackToAGenericSafeFailure() {
        val text = safeAddonFailure(
            AddonError(
                id = "opaque-id",
                message = "raw legacy exception and token",
            ),
        )

        assertEquals("An addon is unavailable.", text)
        assertFalse("opaque" in text)
        assertFalse("token" in text)
    }

    @Test
    fun credentialLikeAddonNamesAreNotDisplayed() {
        val text = safeAddonFailure(
            AddonError(
                id = "opaque-id",
                message = "sanitized compatibility text",
                name = "https://addon.test/account/raw-secret/manifest.json",
                code = "unavailable",
            ),
        )

        assertEquals("An addon is unavailable.", text)
        assertFalse("addon.test" in text)
    }
}
