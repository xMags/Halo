package moe.ditto.halo.browse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.ditto.halo.api.AddonEntry
import moe.ditto.halo.api.AddonsResponse
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState

/**
 * The installed addons: the admin-managed global list and the caller's own.
 *
 * Writes declare an entire list of transport URLs rather than adding or
 * removing one entry, because that is the server's contract — it diffs the list
 * against what it holds, fetches manifests only for URLs it has not seen, and
 * leaves existing entries' opaque ids alone. Callers build the new list from
 * the current one.
 */
class AddonsRepository(
    private val client: HaloClient,
    private val cache: QueryCache,
) {
    /** The raw split, which only the settings screen needs; everything else browses. */
    fun observe(): Flow<QueryState<AddonsResponse>> =
        cache.query(HaloKey.Addons, HaloKey.Addons.staleMs) { client.getAddons() }

    /** Resolution order: global addons first, then the caller's own. */
    fun observeEffective(): Flow<QueryState<List<AddonEntry>>> = observe().map { state ->
        QueryState(state.value?.effective, state.isFetching, state.error)
    }

    /**
     * The effective list without a loading state, fetching once when nothing is
     * cached yet. Search needs the addon list *inside* a fetch rather than as
     * screen state — it renders results, not the addons that produced them.
     *
     * Two searches racing on a cold cache can each fetch. That is a duplicate
     * request, not a correctness problem, and in practice Home has populated
     * this long before anyone opens search.
     */
    suspend fun effective(): List<AddonEntry> {
        val cached = cache.peek<AddonsResponse>(HaloKey.Addons)
        if (cached != null) return cached.effective
        val fetched = client.getAddons()
        cache.set(HaloKey.Addons, fetched)
        return fetched.effective
    }

    /** Declares the caller's own addons, array order being priority. */
    suspend fun setUserAddons(transportUrls: List<String>) {
        client.putAddons(transportUrls)
        cache.invalidate(HaloKey.Addons)
    }

    /** Admin-only: declares the list every user resolves against. */
    suspend fun setGlobalAddons(transportUrls: List<String>) {
        client.putGlobalAddons(transportUrls)
        cache.invalidate(HaloKey.Addons)
    }

    suspend fun setHideCatalogs(addonId: String, hidden: Boolean) {
        client.patchAddon(addonId, hidden)
        cache.invalidate(HaloKey.Addons)
    }

    /** Admin-only: the same knob on a global entry, for every user. */
    suspend fun setGlobalHideCatalogs(addonId: String, hidden: Boolean) {
        client.patchGlobalAddon(addonId, hidden)
        cache.invalidate(HaloKey.Addons)
    }

    /*
     * Every write invalidates instead of reconciling an echo, unlike the synced
     * collections. Two reasons, both structural: the list endpoints answer with
     * the caller's own entries only, which cannot replace a cached value holding
     * both lists, and the visibility patches answer with nothing at all. A
     * refetch is also the only way to learn what a newly installed addon can do
     * — its manifest is fetched server-side, so the client has never seen it.
     */
}
