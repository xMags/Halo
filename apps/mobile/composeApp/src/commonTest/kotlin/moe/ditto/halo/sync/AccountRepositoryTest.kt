package moe.ditto.halo.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ditto.halo.api.Me
import moe.ditto.halo.cache.QueryCache
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountRepositoryTest {
    @Test
    fun loadsTheSignedInAccountOncePerSession() = runTest {
        val api = RecordingApi {
            """{"id":"user-1","username":"dev","isAdmin":true,"createdAt":17}"""
        }
        val repository = AccountRepository(api.client, QueryCache(backgroundScope, FakeClock()))

        val first = repository.observe().first { it.value != null }.value
        val second = repository.observe().first { it.value != null }.value

        assertEquals(Me("user-1", "dev", isAdmin = true, createdAt = 17), first)
        assertEquals(first, second)
        assertEquals(1, api.requests.size)
    }
}
