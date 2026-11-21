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
    private class Ready(val infoHash: String, val request: MagnetRequest)

    private val ready = MutableStateFlow<Ready?>(null)
    private val _request = MutableStateFlow<MagnetRequest?>(null)
    val request: StateFlow<MagnetRequest?> = _request

    /** [infoHash] is the hash the request was made for. */
    fun remember(infoHash: String, request: MagnetRequest) {
        if (request.state != MagnetState.Ready) return
        ready.value = Ready(infoHash, request)
        _request.value = request
    }

    /**
     * A magnet was pasted: a different one is no longer offered. The same one
     * is kept, so asking about it again (back to Search from its title) cannot
     * lose the ready source if that ask fails.
     */
    fun forgetOtherThan(infoHash: String) {
        if (ready.value?.infoHash == infoHash) return
        ready.value = null
        _request.value = null
    }
}
