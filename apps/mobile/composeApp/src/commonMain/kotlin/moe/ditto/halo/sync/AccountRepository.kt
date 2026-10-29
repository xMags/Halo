package moe.ditto.halo.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
}
