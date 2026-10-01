package moe.ditto.halo.downloads

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Joins app-process file access to WorkManager cancellation.
 *
 * WorkManager durably marks unique work cancelled before its operation future
 * completes, but a running CoroutineWorker may still be unwinding. Waiting for
 * this job lock prevents the runtime from pumping the next transfer or deleting
 * files while the old worker still owns its sink.
 */
internal object AndroidDownloadJobLifetimes {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(jobId: String, block: suspend () -> T): T =
        lock(jobId).withLock { block() }

    suspend fun awaitIdle(jobId: String) {
        lock(jobId).withLock { }
    }

    private fun lock(jobId: String): Mutex = locks.computeIfAbsent(jobId) { Mutex() }
}
