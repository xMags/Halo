package moe.ditto.halo.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSecureStorageInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var storage: AndroidSecureStorage

    @Before
    fun setUp() {
        clearState()
        storage = AndroidSecureStorage(context)
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun encryptsRoundTripsAndDeletesValues() {
        val plaintext = "session-token-marker"

        storage.write("session", plaintext)
        val firstEnvelope = rawPreferences().getString("session", null)

        assertEquals(plaintext, storage.read("session"))
        assertTrue(firstEnvelope?.startsWith("v1:") == true)
        assertFalse(firstEnvelope.orEmpty().contains(plaintext))

        storage.write("session", plaintext)
        val secondEnvelope = rawPreferences().getString("session", null)
        assertNotEquals(firstEnvelope, secondEnvelope)

        storage.delete("session")
        assertNull(storage.read("session"))
    }

    @Test
    fun migratesLegacyPlaintextBeforeReturningIt() {
        val plaintext = "legacy-session-marker"
        assertTrue(rawPreferences().edit().putString("session", plaintext).commit())

        assertEquals(plaintext, storage.read("session"))

        val migrated = rawPreferences().getString("session", null)
        assertTrue(migrated?.startsWith("v1:") == true)
        assertFalse(migrated.orEmpty().contains(plaintext))
    }

    @Test
    fun removesMalformedTamperedAndWrongTypeRecords() {
        assertTrue(rawPreferences().edit().putString("malformed", "v1:not-base64:!").commit())
        assertNull(storage.read("malformed"))
        assertFalse(rawPreferences().contains("malformed"))

        storage.write("tampered", "secret")
        val envelope = requireNotNull(rawPreferences().getString("tampered", null))
        val replacement = if (envelope.last() == 'A') 'B' else 'A'
        assertTrue(
            rawPreferences().edit()
                .putString("tampered", envelope.dropLast(1) + replacement)
                .commit(),
        )
        assertNull(storage.read("tampered"))
        assertFalse(rawPreferences().contains("tampered"))

        assertTrue(rawPreferences().edit().putInt("wrong-type", 7).commit())
        assertNull(storage.read("wrong-type"))
        assertFalse(rawPreferences().contains("wrong-type"))
    }

    @Test
    fun authenticatesTheLogicalStorageKey() {
        storage.write("session", "session-value")
        storage.write("server", "server-value")
        val sessionEnvelope = requireNotNull(rawPreferences().getString("session", null))
        val serverEnvelope = requireNotNull(rawPreferences().getString("server", null))

        assertTrue(
            rawPreferences().edit()
                .putString("session", serverEnvelope)
                .putString("server", sessionEnvelope)
                .commit(),
        )

        assertNull(storage.read("session"))
        assertNull(storage.read("server"))
    }

    @Test
    fun serializesConcurrentOperations() {
        val workerCount = 8
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(workerCount)

        repeat(workerCount) { worker ->
            executor.submit {
                try {
                    start.await()
                    repeat(20) { iteration ->
                        val key = "worker-$worker"
                        val value = "value-$worker-$iteration"
                        storage.write(key, value)
                        assertEquals(value, storage.read(key))
                    }
                } catch (error: Throwable) {
                    errors += error
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertTrue("workers timed out", executor.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue("concurrent storage failures: ${errors.size}", errors.isEmpty())
    }

    private fun rawPreferences() =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    private fun clearState() {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val keyStore = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
        if (keyStore.containsAlias(KeyAlias)) keyStore.deleteEntry(KeyAlias)
    }

    private companion object {
        const val PreferencesName = "halo.auth"
        const val KeyStoreProvider = "AndroidKeyStore"
        const val KeyAlias = "moe.ditto.halo.auth.aes"
    }
}
