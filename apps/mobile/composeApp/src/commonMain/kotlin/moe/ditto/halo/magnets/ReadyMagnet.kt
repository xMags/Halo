package moe.ditto.halo.magnets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The last pasted magnet that came back ready, so a title page's sources can
 * offer its file as "Your magnet". Kept until another magnet is pasted or the
 * session ends: it lives in the session graph, so a different sign-in starts
 * with none. Its files are never listed anywhere.
 */
class ReadyMagnet {
    private val _request = MutableStateFlow<MagnetRequest?>(null)
    val request: StateFlow<MagnetRequest?> = _request

    fun remember(request: MagnetRequest) {
        if (request.state == MagnetState.Ready) _request.value = request
    }

    /** Another magnet was pasted: the previous one is no longer offered. */
    fun forget() {
        _request.value = null
    }
}
