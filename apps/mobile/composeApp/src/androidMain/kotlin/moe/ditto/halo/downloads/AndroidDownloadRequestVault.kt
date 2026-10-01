package moe.ditto.halo.downloads

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import moe.ditto.halo.api.HaloJson

internal sealed interface ProtectedRequestRead {
    data class Found(val request: ProtectedDownloadRequest) : ProtectedRequestRead
    data object Missing : ProtectedRequestRead
    data object Corrupt : ProtectedRequestRead
}

/**
 * AES-GCM request vault under noBackupFilesDir, with its key held by Android
 * Keystore. File names are validated UUIDs, never caller-controlled paths.
 */
internal class AndroidDownloadRequestVault(context: Context) : DownloadRequestVault {
    private val directory = File(context.noBackupFilesDir, DirectoryName)

    override fun write(request: ProtectedDownloadRequest): Boolean {
        val target = fileFor(request.jobId) ?: return false
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val cipher = Cipher.getInstance(CipherTransformation)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(request.jobId.toByteArray(Charsets.UTF_8))
            val cleartext = HaloJson
                .encodeToString(ProtectedDownloadRequest.serializer(), request)
                .toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(cleartext)
            val temporary = File(directory, "${request.jobId}.tmp")
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(fileOutput.buffered()).use { output ->
                    output.writeByte(FormatVersion)
                    output.writeByte(cipher.iv.size)
                    output.write(cipher.iv)
                    output.write(ciphertext)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun read(jobId: String): ProtectedRequestRead {
        val target = fileFor(jobId) ?: return ProtectedRequestRead.Corrupt
        if (!target.isFile) return ProtectedRequestRead.Missing
        return try {
            val decoded = DataInputStream(target.inputStream().buffered()).use { input ->
                if (input.readUnsignedByte() != FormatVersion) return ProtectedRequestRead.Corrupt
                val ivSize = input.readUnsignedByte()
                if (ivSize !in MinIvSize..MaxIvSize) return ProtectedRequestRead.Corrupt
                val iv = ByteArray(ivSize)
                input.readFully(iv)
                val ciphertext = input.readBytes()
                if (ciphertext.size < GcmTagBytes) return ProtectedRequestRead.Corrupt
                val cipher = Cipher.getInstance(CipherTransformation)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GcmTagBits, iv))
                cipher.updateAAD(jobId.toByteArray(Charsets.UTF_8))
                val cleartext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
                HaloJson.decodeFromString(ProtectedDownloadRequest.serializer(), cleartext)
            }
            if (decoded.jobId != jobId) ProtectedRequestRead.Corrupt else ProtectedRequestRead.Found(decoded)
        } catch (_: Throwable) {
            ProtectedRequestRead.Corrupt
        }
    }

    override fun delete(jobId: String) {
        val target = fileFor(jobId) ?: return
        runCatching { Files.deleteIfExists(target.toPath()) }
    }

    private fun fileFor(jobId: String): File? {
        val canonical = runCatching { UUID.fromString(jobId).toString() }.getOrNull() ?: return null
        if (!canonical.equals(jobId, ignoreCase = true)) return null
        return File(directory, "$canonical.request")
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (store.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DirectoryName = "download-requests"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "moe.ditto.halo.download.requests.v1"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val FormatVersion = 1
        const val MinIvSize = 12
        const val MaxIvSize = 32
        const val GcmTagBits = 128
        const val GcmTagBytes = GcmTagBits / 8
    }
}
