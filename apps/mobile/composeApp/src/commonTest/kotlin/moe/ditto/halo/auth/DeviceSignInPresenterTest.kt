package moe.ditto.halo.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSignInPresenterTest {
    private val approvedSession = DeviceSession.Local(token = "session-token", expiresAt = 99L)

    private class FakeDeviceSignIn(
        val issue: () -> DeviceChallenge = { challenge() },
        val answers: MutableList<() -> DevicePollResult> = mutableListOf(),
    ) : DeviceSignIn {
        val pollTimes = mutableListOf<Long>()
        var starts = 0
        lateinit var now: () -> Long

        override suspend fun start(serverUrl: String, config: JsonObject): DeviceChallenge {
            starts += 1
            return issue()
        }

        override suspend fun poll(serverUrl: String, challenge: DeviceChallenge): DevicePollResult {
            pollTimes += now()
            return answers.removeFirstOrNull()?.invoke() ?: DevicePollResult.Pending
        }
    }

    private class Harness(val presenter: DeviceSignInPresenter, val approved: MutableList<DeviceSession>)

    private fun TestScope.harness(
        signIn: FakeDeviceSignIn,
        onApproved: suspend (DeviceSession) -> Unit = {},
    ): Harness {
        signIn.now = { testScheduler.currentTime }
        val approved = mutableListOf<DeviceSession>()
        val presenter = DeviceSignInPresenter(
            signIn = signIn,
            serverUrl = "https://halo.test",
            config = JsonObject(emptyMap()),
            scope = backgroundScope,
            clock = EpochClock { testScheduler.currentTime },
            onApproved = { session ->
                onApproved(session)
                approved += session
            },
        )
        return Harness(presenter, approved)
    }

    @Test
    fun showsTheCodeAndCountsDown() = runTest {
        val h = harness(FakeDeviceSignIn())
        h.presenter.start()
        runCurrent()
        val shown = assertIs<DeviceSignInState.Showing>(h.presenter.state.value)
        assertEquals("BCDF-GHJK", shown.userCode)
        assertEquals("https://halo.test/device", shown.verificationUrl)
        assertEquals("https://halo.test/device?code=BCDFGHJK", shown.qrUrl)
        assertEquals(600, shown.secondsLeft)

        advanceTimeBy(3_001)
        assertEquals(597, assertIs<DeviceSignInState.Showing>(h.presenter.state.value).secondsLeft)
    }

    @Test
    fun anApprovalKeepsTheSessionAfterPollingAtTheInterval() = runTest {
        val signIn = FakeDeviceSignIn(
            answers = mutableListOf({ DevicePollResult.Pending }, { DevicePollResult.Pending }, { DevicePollResult.Approved(approvedSession) }),
        )
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(16_000)
        assertEquals(listOf(5_000L, 10_000L, 15_000L), signIn.pollTimes)
        assertEquals(listOf<DeviceSession>(approvedSession), h.approved)
        assertEquals(DeviceSignInState.Approved, h.presenter.state.value)
    }

    @Test
    fun aRefusalOnThePhoneStops() = runTest {
        val signIn = FakeDeviceSignIn(answers = mutableListOf({ DevicePollResult.Denied }))
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(30_000)
        assertEquals(DeviceSignInState.Denied, h.presenter.state.value)
        assertEquals(1, signIn.pollTimes.size)
    }

    @Test
    fun theCodeRunsOutOnTheDeviceClock() = runTest {
        val signIn = FakeDeviceSignIn(issue = { challenge(expiresIn = 12) })
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(11_000)
        assertIs<DeviceSignInState.Showing>(h.presenter.state.value)
        advanceTimeBy(2_000)
        assertEquals(DeviceSignInState.Expired, h.presenter.state.value)
        assertEquals(listOf(5_000L, 10_000L), signIn.pollTimes)
    }

    @Test
    fun theServerSayingExpiredStops() = runTest {
        val signIn = FakeDeviceSignIn(answers = mutableListOf({ DevicePollResult.Expired }))
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(6_000)
        assertEquals(DeviceSignInState.Expired, h.presenter.state.value)
    }

    @Test
    fun slowDownWaitsFiveSecondsLongerEachTime() = runTest {
        val signIn = FakeDeviceSignIn(
            answers = mutableListOf({ DevicePollResult.SlowDown }, { DevicePollResult.SlowDown }, { DevicePollResult.Pending }),
        )
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(46_000)
        // 5 s, then 10 s later, then 15 s later, and 15 s from then on.
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 45_000L), signIn.pollTimes)
    }

    @Test
    fun aNetworkFailureKeepsPolling() = runTest {
        val signIn = FakeDeviceSignIn(
            answers = mutableListOf({ throw IllegalStateException("offline") }, { DevicePollResult.Approved(approvedSession) }),
        )
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(11_000)
        assertEquals(DeviceSignInState.Approved, h.presenter.state.value)
        assertEquals(2, signIn.pollTimes.size)
    }

    @Test
    fun aServerRefusalWhilePollingIsShown() = runTest {
        val signIn = FakeDeviceSignIn(answers = mutableListOf({ throw DeviceSignInException("This TV is not allowed to sign in.") }))
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(6_000)
        assertEquals(DeviceSignInState.Failed("This TV is not allowed to sign in."), h.presenter.state.value)
    }

    @Test
    fun startingFailsPlainly() = runTest {
        val unreachable = harness(FakeDeviceSignIn(issue = { throw IllegalStateException("offline") }))
        unreachable.presenter.start()
        runCurrent()
        val failed = assertIs<DeviceSignInState.Failed>(unreachable.presenter.state.value)
        assertTrue(failed.message.startsWith("Couldn't reach the server"))

        val refused = harness(FakeDeviceSignIn(issue = { throw DeviceSignInException("Too many sign-ins. Try again later.") }))
        refused.presenter.start()
        runCurrent()
        assertEquals(DeviceSignInState.Failed("Too many sign-ins. Try again later."), refused.presenter.state.value)
    }

    @Test
    fun leavingTheScreenStopsPolling() = runTest {
        val signIn = FakeDeviceSignIn(
            answers = mutableListOf({ DevicePollResult.Pending }, { DevicePollResult.Approved(approvedSession) }),
        )
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(6_000)
        h.presenter.cancel()
        advanceTimeBy(60_000)
        assertEquals(1, signIn.pollTimes.size)
        assertTrue(h.approved.isEmpty())
    }

    @Test
    fun aSessionThatCannotBeKeptIsAFailure() = runTest {
        val signIn = FakeDeviceSignIn(answers = mutableListOf({ DevicePollResult.Approved(approvedSession) }))
        val h = harness(signIn, onApproved = { throw IllegalStateException("Could not save the session") })
        h.presenter.start()
        advanceTimeBy(6_000)
        assertEquals(DeviceSignInState.Failed("Could not save the session"), h.presenter.state.value)
    }

    @Test
    fun aNewCodeStartsOver() = runTest {
        var issued = 0
        val signIn = FakeDeviceSignIn(issue = { issued += 1; challenge(userCode = "CODE-000$issued", expiresIn = 6) })
        val h = harness(signIn)
        h.presenter.start()
        advanceTimeBy(7_000)
        assertEquals(DeviceSignInState.Expired, h.presenter.state.value)
        h.presenter.start()
        runCurrent()
        assertEquals("CODE-0002", assertIs<DeviceSignInState.Showing>(h.presenter.state.value).userCode)
        assertEquals(2, signIn.starts)
    }

    @Test
    fun theSecretNeverAppearsInText() {
        assertTrue("device-secret" !in challenge().toString())
        assertTrue("session-token" !in approvedSession.toString())
    }

    private companion object {
        fun challenge(userCode: String = "BCDF-GHJK", expiresIn: Int = 600) = DeviceChallenge(
            deviceCode = "device-secret",
            userCode = userCode,
            verificationUrl = "https://halo.test/device",
            verificationUrlComplete = "https://halo.test/device?code=BCDFGHJK",
            expiresInSeconds = expiresIn,
            intervalSeconds = 5,
        )
    }
}
