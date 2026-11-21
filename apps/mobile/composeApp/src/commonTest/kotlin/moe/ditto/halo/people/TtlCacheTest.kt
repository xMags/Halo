package moe.ditto.halo.people

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TtlCacheTest {
    private fun cache() = TtlCache<String>(maxEntries = 10, ttlMs = 60_000, now = { 0L })

    @Test
    fun concurrentCallersShareOneLoad() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val first = async { cache.getOrLoad("nolan") { loads++; gate.await(); "value" } }
        val second = async { cache.getOrLoad("nolan") { loads++; "other" } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("value", first.await())
        assertEquals("value", second.await())
        assertEquals(1, loads)
    }

    @Test
    fun aWaiterLoadsItselfWhenTheLoadingCallerIsCancelled() = runTest {
        val cache = cache()
        val never = CompletableDeferred<Unit>()
        val owner = async { cache.getOrLoad("nolan") { never.await(); "never" } }
        runCurrent()
        val waiter = async { cache.getOrLoad("nolan") { "fresh" } }
        runCurrent()
        owner.cancel()
        assertEquals("fresh", waiter.await())
        // And the key is not left marked as loading.
        assertEquals("fresh", cache.getOrLoad("nolan") { "unused" })
    }

    @Test
    fun aCancelledLoadDoesNotLeaveTheKeyLoading() = runTest {
        val cache = cache()
        val never = CompletableDeferred<Unit>()
        val owner = async { cache.getOrLoad("nolan") { never.await(); "never" } }
        runCurrent()
        owner.cancel()
        runCurrent()
        assertEquals("next", cache.getOrLoad("nolan") { "next" })
    }

    @Test
    fun aFailedLoadReachesItsWaitersAndIsNotKept() = runTest {
        val cache = cache()
        val gate = CompletableDeferred<Unit>()
        val owner = async { runCatching { cache.getOrLoad("nolan") { gate.await(); throw IllegalStateException("down") } } }
        runCurrent()
        val waiter = async { runCatching { cache.getOrLoad("nolan") { "unused" } } }
        runCurrent()
        gate.complete(Unit)
        assertFailsWith<IllegalStateException> { owner.await().getOrThrow() }
        assertFailsWith<IllegalStateException> { waiter.await().getOrThrow() }
        assertEquals("later", cache.getOrLoad("nolan") { "later" })
    }
}
