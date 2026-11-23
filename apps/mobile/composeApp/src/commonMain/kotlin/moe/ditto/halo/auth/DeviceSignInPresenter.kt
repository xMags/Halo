package moe.ditto.halo.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where a device sign-in stands, for the screen. */
sealed interface DeviceSignInState {
    data object Starting : DeviceSignInState

    /** Waiting for the viewer to approve [userCode] at [verificationUrl]. */
    data class Showing(
        val userCode: String,
        val verificationUrl: String,
        /** For the QR code: the address with the code in it when the server gives one. */
        val qrUrl: String,
        val secondsLeft: Int,
    ) : DeviceSignInState

    data object Approved : DeviceSignInState
    data object Denied : DeviceSignInState
    data object Expired : DeviceSignInState
    data class Failed(val message: String) : DeviceSignInState
}

/**
 * Runs one device sign-in at a time: asks for a code, shows it with a
 * countdown, and polls until the viewer approves or refuses it on the phone,
 * or the code runs out.
 *
 * Polling waits the server's interval between asks, five seconds longer after
 * each `slow_down`, and keeps going through transport failures (a TV's Wi-Fi
 * dropping for a moment must not end the sign-in). An approval is handed to
 * [onApproved], which keeps the session the way the matching password or
 * browser sign-in would. [cancel] (the screen leaving) stops everything.
 */
class DeviceSignInPresenter(
    private val signIn: DeviceSignIn,
    private val serverUrl: String,
    private val config: JsonObject,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val onApproved: suspend (DeviceSession) -> Unit,
) {
    private val _state = MutableStateFlow<DeviceSignInState>(DeviceSignInState.Starting)
    val state: StateFlow<DeviceSignInState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts, or starts over with a new code. */
    fun start() {
        job?.cancel()
        _state.value = DeviceSignInState.Starting
        job = scope.launch { run() }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val challenge = try {
            signIn.start(serverUrl, config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DeviceSignInException) {
            _state.value = DeviceSignInState.Failed(refused.message ?: DefaultFailure)
            return
        } catch (_: Exception) {
            _state.value = DeviceSignInState.Failed(Unreachable)
            return
        }
        val expiresAt = clock.nowMs() + challenge.expiresInSeconds * 1_000L
        val qrUrl = challenge.verificationUrlComplete ?: challenge.verificationUrl
        fun secondsLeft(): Int = ((expiresAt - clock.nowMs() + 999) / 1_000).toInt().coerceAtLeast(0)
        fun show() {
            _state.value = DeviceSignInState.Showing(challenge.userCode, challenge.verificationUrl, qrUrl, secondsLeft())
        }
        show()

        var interval = challenge.intervalSeconds.coerceAtLeast(MinIntervalSeconds) * 1_000L
        var nextPollAt = clock.nowMs() + interval
        while (true) {
            // Tick the countdown once a second until the next poll is due.
            while (clock.nowMs() < nextPollAt) {
                if (secondsLeft() == 0) {
                    _state.value = DeviceSignInState.Expired
                    return
                }
                delay(minOf(1_000L, nextPollAt - clock.nowMs()))
                show()
            }
            if (secondsLeft() == 0) {
                _state.value = DeviceSignInState.Expired
                return
            }
            val result = try {
                signIn.poll(serverUrl, challenge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DeviceSignInException) {
                _state.value = DeviceSignInState.Failed(refused.message ?: DefaultFailure)
                return
            } catch (_: Exception) {
                // Unreachable for a moment: ask again at the next turn.
                null
            }
            when (result) {
                DevicePollResult.Pending, null -> Unit
                DevicePollResult.SlowDown -> interval += SlowDownStepMs
                DevicePollResult.Denied -> {
                    _state.value = DeviceSignInState.Denied
                    return
                }
                DevicePollResult.Expired -> {
                    _state.value = DeviceSignInState.Expired
                    return
                }
                is DevicePollResult.Approved -> {
                    try {
                        onApproved(result.session)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        _state.value = DeviceSignInState.Failed(failure.message ?: DefaultFailure)
                        return
                    }
                    _state.value = DeviceSignInState.Approved
                    return
                }
            }
            nextPollAt = clock.nowMs() + interval
        }
    }

    private companion object {
        const val MinIntervalSeconds = 1
        const val SlowDownStepMs = 5_000L
        const val DefaultFailure = "Signing in didn't work. Get a new code and try again."
        const val Unreachable = "Couldn't reach the server. Check the connection and try again."
    }
}
