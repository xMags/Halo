package moe.ditto.halo.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.Me
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState

/**
 * The signed-in account record used to gate admin-only settings. Without an
 * account [client] is null and there is no record: nothing is admin-only then.
 */
class AccountRepository(
    private val client: HaloClient?,
    private val cache: QueryCache,
) {
    fun observe(): Flow<QueryState<Me>> {
        val account = client ?: return flowOf(QueryState())
        return cache.query(HaloKey.Me, HaloKey.Me.staleMs) { account.getMe() }
    }

    // The last picture fetched, by its fingerprint. One is all the app shows,
    // and a new fingerprint replaces it rather than piling up.
    private val avatarLock = Mutex()
    private var avatar: Pair<String, ByteArray?>? = null

    /**
     * The profile picture named by [version] (`Me.avatarVersion`), fetched once
     * per fingerprint; null when the server has none after all. Concurrent
     * callers share the one request.
     */
    suspend fun avatar(version: String): ByteArray? {
        val account = client ?: return null
        return avatarLock.withLock {
            avatar?.takeIf { it.first == version }?.second
                ?: account.getAvatar(version).also { avatar = version to it }
        }
    }
}
