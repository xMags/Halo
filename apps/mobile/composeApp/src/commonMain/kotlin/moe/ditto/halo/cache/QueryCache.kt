package moe.ditto.halo.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ditto.halo.auth.EpochClock

/** Identifies one cached resource. Implementations must make [id] unique per resource. */
interface QueryKey {
    val id: String
}

/**
 * What an observer sees. [value] survives across refetches and failures so a
 * screen keeps rendering the last good data while revalidating — the reason
 * this is a triple rather than a sealed loading/success/error union.
 */
data class QueryState<out T>(
    val value: T? = null,
    val isFetching: Boolean = false,
    val error: Throwable? = null,
)

/**
 * Stale-while-revalidate cache for server state.
 *
 * Observers get the cached value immediately and a fresh one when it arrives.
 * Concurrent readers of the same key share a single request. Writes apply
 * optimistically and reconcile against the server's echo.
 *
 * Ordering rule, which every operation obeys: each fetch and each mutation
 * claims the next sequence number for its key, and a result may only be
 * applied while its sequence is still the latest one issued. Without it a slow
 * response can overwrite newer state — the API's write endpoints return the
 * caller's full post-merge collection, so a late echo is a complete snapshot
 * of *older* server state, not a harmless partial update.
 */
class QueryCache(
    scope: CoroutineScope,
    private val clock: EpochClock,
) {
    /**
     * Work outlives the screen that requested it: a fetch must survive
     * navigation so the next reader joins it instead of starting over.
     *
     * The supervisor is load-bearing rather than idiomatic decoration. A
     * failing `async` cancels its parent unless that parent is a supervisor,
     * so sharing the caller's scope directly would let one failed request tear
     * down every other query in the app.
     */
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    private class Entry {
        val state = MutableStateFlow(QueryState<Any?>())

        /** Zero means never fetched, which is distinct from fetched long ago. */
        var fetchedAt = 0L
        var inflight: Deferred<Any?>? = null

        /** Retained so invalidation can refetch without a caller supplying it again. */
        var fetcher: (suspend () -> Any?)? = null

        /** Monotonic per key; every fetch and mutation claims the next number. */
        var issued = 0L
    }

    /**
     * Observes [key], fetching when the cached value is missing or older than
     * [staleMs]. Emits the cached value first, so a revisit renders instantly
     * and updates in place.
     *
     * [enabled] false observes without ever fetching, for queries whose inputs
     * are not ready — a search with no term, a detail screen still resolving
     * its id.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> query(
        key: QueryKey,
        staleMs: Long,
        enabled: Boolean = true,
        fetch: suspend () -> T,
    ): Flow<QueryState<T>> = flow {
        val entry = entry(key)
        mutex.withLock { entry.fetcher = fetch as suspend () -> Any? }
        if (enabled) scope.launch { revalidate(key, staleMs, force = false) }
        emitAll(entry.state.map { it as QueryState<T> })
    }

    /** The currently cached value, without fetching. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> peek(key: QueryKey): T? = entry(key).state.value.value as T?

    /** Replaces the cached value and supersedes anything in flight for [key]. */
    suspend fun <T> set(key: QueryKey, value: T) {
        val entry = entry(key)
        mutex.withLock {
            entry.issued++
            entry.fetchedAt = clock.nowMs()
            entry.state.value = QueryState(value, isFetching = false, error = null)
        }
    }

    /**
     * Marks [key] stale and refetches it when something is observing. An
     * unobserved key is only marked, so a screen nobody is looking at does not
     * generate traffic; it refetches when next observed.
     */
    suspend fun invalidate(key: QueryKey) {
        val entry = entry(key)
        val shouldRefetch = mutex.withLock {
            entry.fetchedAt = 0
            entry.fetcher != null && entry.state.subscriptionCount.value > 0
        }
        if (shouldRefetch) revalidate(key, staleMs = 0, force = true)
    }

    /**
     * Applies an optimistic write, sends it, and reconciles the server's echo.
     *
     * [put] receives the merged value rather than the caller's patch, so a
     * request always carries the fully merged state. Two rapid edits therefore
     * build on each other instead of the later one dropping the earlier one's
     * field under a newer timestamp.
     *
     * [resolve] decides what to keep when the echo arrives; the default trusts
     * the server. Callers holding their own version (a last-write-wins
     * timestamp, say) can refuse an echo older than what they already have.
     *
     * A failure does not restore a snapshot. Another write may already have
     * landed, and reinstating a pre-mutation value would discard it — the key
     * is marked stale so the next read takes server truth instead.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> mutate(
        key: QueryKey,
        optimistic: (T?) -> T,
        put: suspend (T) -> T,
        resolve: (current: T?, echo: T) -> T = { _, echo -> echo },
    ): T {
        val entry = entry(key)
        var seq: Long
        val merged: T
        mutex.withLock {
            merged = optimistic(entry.state.value.value as T?)
            seq = ++entry.issued
            entry.state.value = QueryState(merged, isFetching = true, error = null)
        }
        val echo = try {
            put(merged)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            mutex.withLock {
                entry.fetchedAt = 0
                if (seq == entry.issued) {
                    entry.state.value = entry.state.value.copy(isFetching = false, error = failure)
                }
            }
            throw failure
        }
        mutex.withLock {
            if (seq == entry.issued) {
                entry.fetchedAt = clock.nowMs()
                entry.state.value = QueryState(
                    value = resolve(entry.state.value.value as T?, echo),
                    isFetching = false,
                    error = null,
                )
            }
        }
        return echo
    }

    private suspend fun revalidate(key: QueryKey, staleMs: Long, force: Boolean) {
        val entry = entry(key)
        var seq = 0L
        val deferred = mutex.withLock {
            entry.inflight?.let { return@withLock it }
            if (!force && !isStale(entry, staleMs)) return
            val fetcher = entry.fetcher ?: return
            seq = ++entry.issued
            scope.async { fetcher() }.also {
                entry.inflight = it
                entry.state.value = entry.state.value.copy(isFetching = true, error = null)
            }
        }
        if (seq == 0L) {
            // Joined a request already in flight; whoever issued it applies the
            // result. Failures reach this caller through the shared state.
            runCatching { deferred.await() }
            return
        }
        try {
            val value = deferred.await()
            settle(entry, seq, deferred, fetched = true) { QueryState(value, isFetching = false, error = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            settle(entry, seq, deferred, fetched = false) { it.copy(isFetching = false, error = failure) }
        }
    }

    private suspend fun settle(
        entry: Entry,
        seq: Long,
        deferred: Deferred<Any?>,
        fetched: Boolean,
        outcome: (QueryState<Any?>) -> QueryState<Any?>,
    ) = mutex.withLock {
        if (entry.inflight === deferred) entry.inflight = null
        // A newer fetch or write has since been issued; this result is stale
        // even though it arrived later.
        if (seq != entry.issued) return@withLock
        if (fetched) entry.fetchedAt = clock.nowMs()
        entry.state.value = outcome(entry.state.value)
    }

    private fun isStale(entry: Entry, staleMs: Long): Boolean {
        // Never fetched is always stale. Checking the age alone would read a
        // never-fetched entry as fresh under a very long staleness window.
        if (entry.fetchedAt == 0L) return true
        return clock.nowMs() - entry.fetchedAt >= staleMs
    }

    /**
     * The single point where entries are looked up or created. Persistence, if
     * it lands later, hydrates here and writes through [settle]/[set] rather
     * than at every call site.
     */
    private suspend fun entry(key: QueryKey): Entry = mutex.withLock {
        entries.getOrPut(key.id) { Entry() }
    }
}
