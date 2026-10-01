package moe.ditto.halo.downloads

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundDownloadsInstrumentedTest {
    @Test
    fun workIsConnectedOnlyAndItsInputContainsOnlyTheOpaqueJobId() {
        val jobId = UUID.randomUUID().toString()
        val request = androidDownloadWorkRequest(jobId)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(jobId, request.workSpec.input.getString(AndroidDownloadWorkContract.JobId))
        val input = request.workSpec.input.toString()
        assertFalse(input.contains("http"))
        assertFalse(input.contains("token"))
        assertEquals(1, request.workSpec.input.keyValueMap.size)
    }

    @Test
    fun requestRoundTripIsEncryptedAndStoredOutsideBackup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = AndroidDownloadRequestVault(context)
        val jobId = UUID.randomUUID().toString()
        val secret = "https://source.test/movie.mkv?token=instrumented-secret"
        val request = ProtectedDownloadRequest(
            jobId = jobId,
            sourceUrl = secret,
            partFilePath = File(context.filesDir, "downloads/movie.mkv.part").path,
            targetFilePath = File(context.filesDir, "downloads/movie.mkv").path,
        )

        try {
            assertTrue(vault.write(request))
            val stored = File(context.noBackupFilesDir, "download-requests/$jobId.request")
            assertTrue(stored.isFile)
            assertFalse(stored.readBytes().toString(Charsets.UTF_8).contains(secret))
            val recovered = vault.read(jobId)
            assertTrue(recovered is ProtectedRequestRead.Found)
            assertEquals(request, (recovered as ProtectedRequestRead.Found).request)
        } finally {
            vault.delete(jobId)
        }
    }

    @Test
    fun foregroundNotificationIsPrivateAndOffersPauseAndCancelWithoutSensitiveText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = AndroidDownloadNotifications(context)
            .foreground(UUID.randomUUID().toString(), 50, 100)
            .notification

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(listOf("Pause", "Cancel"), notification.actions.map { it.title.toString() })
        val rendered = buildString {
            append(notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            append(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        }
        assertFalse(rendered.contains("http"))
        assertFalse(rendered.contains("token"))
    }

    @Test
    fun workerRecoversEncryptedRequestAndContinuesTheExistingRange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloads = File(context.filesDir, "downloads").apply { mkdirs() }
        val jobId = UUID.randomUUID().toString()
        val target = File(downloads, "worker-range-$jobId.bin")
        val part = File(downloads, "${target.name}.part")
        val size = 1024 * 1024
        val prefixSize = 192 * 1024
        val seed = "android-worker"
        val sourceUrl = "http://127.0.0.1:18788/media/generated.bin" +
            "?fixture_size=$size&fixture_seed=$seed"
        val validatorIdentity = "generated:$seed:$size"
        val validator = "\"fixture-${sha256(validatorIdentity.toByteArray()).take(24)}\""
        val expected = generatedBytes(size, seed)
        part.writeBytes(expected.copyOf(prefixSize))
        val vault = AndroidDownloadRequestVault(context)
        val workManager = WorkManager.getInstance(context)
        val request = androidDownloadWorkRequest(jobId)

        try {
            assertTrue(
                vault.write(
                    ProtectedDownloadRequest(
                        jobId = jobId,
                        sourceUrl = sourceUrl,
                        partFilePath = part.path,
                        targetFilePath = target.path,
                        resumeValidator = validator,
                    ),
                ),
            )
            workManager.enqueueUniqueWork(
                AndroidDownloadWorkContract.UniqueWorkPrefix + jobId,
                ExistingWorkPolicy.REPLACE,
                request,
            ).result.get()

            val finished = withTimeout(60_000) {
                while (true) {
                    val info = workManager.getWorkInfoById(request.id).get()
                    if (info != null && info.state.isFinished) return@withTimeout info
                    delay(100)
                }
                error("unreachable")
            }

            assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
            assertFalse(part.exists())
            assertTrue(target.isFile)
            assertTrue(expected.contentEquals(target.readBytes()))
            assertTrue(fixtureRecordedPartialContent())
        } finally {
            workManager.cancelUniqueWork(AndroidDownloadWorkContract.UniqueWorkPrefix + jobId).result.get()
            vault.delete(jobId)
            part.delete()
            target.delete()
        }
    }

    private fun fixtureRecordedPartialContent(): Boolean {
        val body = URL("http://127.0.0.1:18788/status").readText()
        val requests = JSONObject(body).getJSONArray("requests")
        return (0 until requests.length()).any { index ->
            val request = requests.getJSONObject(index)
            request.optString("path") == "/media/generated.bin" && request.optInt("status") == 206
        }
    }

    private fun generatedBytes(size: Int, seed: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return ByteArray(size) { index ->
            (digest[index % digest.size].toInt() xor ((index / digest.size) and 0xff)).toByte()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
