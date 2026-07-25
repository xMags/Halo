package moe.ditto.halo.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import moe.ditto.halo.api.AddonStreams
import moe.ditto.halo.api.HaloClient
import moe.ditto.halo.api.ManifestCatalog
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.cache.HaloKey
import moe.ditto.halo.cache.QueryCache
import moe.ditto.halo.cache.QueryState

/**
 * Everything the browse screens read: catalogs, metadata, streams, and search.
 *
 * All of it is resolved server-side — this client never talks to an addon. It
 * addresses them by their opaque ids, so a transport URL (which can carry a
 * debrid key) never has to reach the device.
 *
 * Unlike the synced collections, none of this is ever written back; the server
 * is the only writer, and everything here is a cache over it.
 */
class BrowseRepository(
    private val client: HaloClient,
    private val cache: QueryCache,
    private val addons: AddonsRepository,
) {
    /**
     * One catalog's titles. [enabled] false observes without fetching, for rows
     * whose inputs are not resolved yet — Home's featured title, for instance,
     * has no catalog to read until the addon list arrives.
     */
    fun catalog(
        addonId: String,
        type: String,
        catalogId: String,
        enabled: Boolean = true,
    ): Flow<QueryState<List<MetaPreview>>> {
        val key = HaloKey.Catalog(addonId, type, catalogId)
        return cache.query(key, key.staleMs, enabled) { client.getCatalog(addonId, type, catalogId).metas }
    }

    /** Full metadata, from the first effective addon that can describe the id. */
    fun meta(type: String, metaId: String, enabled: Boolean = true): Flow<QueryState<MetaDetail>> {
        val key = HaloKey.Meta(type, metaId)
        return cache.query(key, key.staleMs, enabled) { client.getMeta(type, metaId).meta }
    }

    /**
     * Playable sources, grouped by the addon that offered them.
     *
     * Nothing is filtered here: the server already drops torrent and
     * external-link results and omits addons left with none, so an empty list
     * means no source can play this video, not that one was discarded.
     *
     * Addons that failed are reported separately by the API and deliberately
     * not surfaced — a source that did not answer is indistinguishable, to
     * someone trying to watch something, from one that had nothing.
     */
    fun streams(type: String, videoId: String): Flow<QueryState<List<AddonStreams>>> {
        val key = HaloKey.Streams(type, videoId)
        return cache.query(key, key.staleMs) { client.getStreams(type, videoId).results }
    }

    /**
     * Searches every catalog that supports it, one row per catalog that
     * answers.
     *
     * Terms are trimmed before they become a cache key, so trailing whitespace
     * from a keyboard cannot fan out a second identical search.
     */
    fun search(term: String): Flow<QueryState<List<SearchResultGroup>>> {
        val trimmed = term.trim()
        val key = HaloKey.Search(trimmed)
        return cache.query(key, key.staleMs, enabled = trimmed.length >= MinSearchTermLength) {
            runSearch(trimmed)
        }
    }

    /**
     * One request per search-capable catalog, in parallel.
     *
     * A failing catalog must not fail the search — addons are third-party and
     * routinely time out — so each request is caught individually and its
     * target drops out. The catch sits inside the child coroutine on purpose:
     * an exception escaping `async` here would cancel its siblings, turning one
     * slow addon into an empty screen.
     */
    private suspend fun runSearch(term: String): List<SearchResultGroup> {
        val targets = searchTargets(addons.effective())
        val results = coroutineScope {
            targets
                .map { target ->
                    async {
                        try {
                            client.getCatalog(
                                addonId = target.addonId,
                                type = target.type,
                                id = target.catalogId,
                                extra = mapOf(ManifestCatalog.SearchExtra to term),
                            ).metas
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                .awaitAll()
        }
        return buildSearchGroups(targets, results)
    }
}
