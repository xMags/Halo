package moe.ditto.halo.people

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the session offers, and whether that is known yet. */
data class CapabilitiesState(
    val capabilities: Capabilities = Capabilities.None,
    /** The answer is in, or definitely failed: a screen waiting on it may decide. */
    val settled: Boolean = false,
    val failed: Boolean = false,
)

/**
 * What the server (or, without an account, this phone) offers beyond the
 * shared contract: people search, IMDb/TVDB lookups, magnets. Asked once per
 * session; until it answers, and when the answer cannot be had, everything
 * reads as off, and a failure is asked again the next time Search opens
 * ([refreshIfFailed]). Without an account it follows the saved TMDB key
 * ([keyStatus]), so saving or removing one in Settings applies at once.
 */
class SessionCapabilities(
    private val scope: CoroutineScope,
    private val load: suspend () -> Capabilities,
    keyStatus: StateFlow<TmdbKeyStatus>?,
) {
    private val _state = MutableStateFlow(CapabilitiesState())
    val state: StateFlow<CapabilitiesState> = _state

    /** The ask under way; a newer one replaces it, so a stale answer never lands over a newer one. */
    private var asking: Job? = null
    private val lock = Mutex()

    init {
        ask()
        if (keyStatus != null) {
            scope.launch { keyStatus.drop(1).distinctUntilChanged().collect { ask() } }
        }
    }

    /** Asks again when the last answer could not be had. */
    fun refreshIfFailed() {
        if (_state.value.failed) ask()
    }

    /** The answer, once it is in (everything off when it failed). */
    suspend fun settled(): Capabilities = state.first { it.settled }.capabilities

    private fun ask() {
        scope.launch {
            lock.withLock {
                asking?.cancel()
                // Everything reads as off until the new answer is in, so a removed
                // or refused key turns the features off at once, even for readers
                // that do not wait for [CapabilitiesState.settled].
                _state.value = CapabilitiesState()
                asking = scope.launch {
                    val next = try {
                        CapabilitiesState(load(), settled = true)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        CapabilitiesState(Capabilities.None, settled = true, failed = true)
                    }
                    _state.value = next
                }
            }
        }
    }
}
