package moe.ditto.halo.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private encrypted auth storage. Ciphertext lives in SharedPreferences;
 * the non-exportable AES key lives in Android Keystore and can be used while
 * the app is backgrounded so session restoration and refresh remain possible.
 */
class AndroidSecureStorage(context: Context) : SecureStorage {
    private val lock = Any()
    private val prefs = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }

    override fun read(key: String): String? = read(key, strict = false)

    override fun readStrict(key: String): String? = read(key, strict = true)

    private fun read(key: String, strict: Boolean): String? = synchronized(lock) {
        val stored = prefs.all[key] ?: return@synchronized null
        if (stored !is String) {
            removeLocked(key)
            return@synchronized null
        }

        if (!stored.startsWith("$EnvelopeVersion:")) {
            return@synchronized migrateLegacyValueLocked(key, stored)
        }

        decryptLocked(key, stored, strict)
    }

    override fun write(key: String, value: String) {
        synchronized(lock) {
            val envelope = encryptWithRecoveryLocked(key, value)
            check(prefs.edit().putString(key, envelope).commit()) {
                "Could not persist encrypted auth state."
            }
        }
    }

    override fun delete(key: String) {
        synchronized(lock) {
            check(prefs.edit().remove(key).commit()) {
                "Could not delete encrypted auth state."
            }
        }
    }

    private fun migrateLegacyValueLocked(key: String, plaintext: String): String? {
        return try {
            val envelope = encryptWithRecoveryLocked(key, plaintext)
            if (!prefs.edit().putString(key, envelope).commit()) {
                removeLocked(key)
                null
            } else {
                plaintext
            }
        } catch (_: Exception) {
            removeLocked(key)
            null
        }
    }

    /**
     * Encrypts with the current key. Only a key that is gone for good is
     * replaced, which takes every value sealed with it. A plain
     * InvalidKeyException, which some devices raise for a passing Keystore
     * fault, is tried once more and then fails this write alone, keeping
     * every stored value.
     */
    private fun encryptWithRecoveryLocked(key: String, plaintext: String): String {
        return try {
            encryptLocked(key, plaintext, getOrCreateKeyLocked())
        } catch (error: Exception) {
            if (isKeyGone(error)) {
                resetKeyAndEncryptedStateLocked()
                return encryptLocked(key, plaintext, createKeyLocked())
            }
            if (error !is InvalidKeyException) throw error
            encryptLocked(key, plaintext, getOrCreateKeyLocked())
        }
    }

    private fun encryptLocked(key: String, plaintext: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(aadFor(key))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$EnvelopeVersion:$iv:$payload"
    }

    private fun decryptLocked(key: String, envelope: String, strict: Boolean): String? {
        return try {
            val parts = envelope.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == EnvelopeVersion)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            require(iv.isNotEmpty() && ciphertext.isNotEmpty())

            val cipher = Cipher.getInstance(Transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKeyLocked(),
                GCMParameterSpec(GcmTagLengthBits, iv),
            )
            cipher.updateAAD(aadFor(key))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            // Only a key that is gone for good takes every value with it, and
            // only a value that is itself damaged is dropped. Anything else
            // (some devices report a passing Keystore fault as a plain
            // InvalidKeyException) keeps the data for the next read; a key
            // that stays broken is replaced by the next write instead.
            when {
                isKeyGone(error) -> resetKeyAndEncryptedStateLocked()
                isDamagedValue(error) -> removeLocked(key)
                strict -> throw SecureStorageUnavailableException("A stored value could not be read right now.")
            }
            null
        }
    }

    private fun getOrCreateKeyLocked(): SecretKey {
        val existing = try {
            keyStore.getKey(KeyAlias, null)
        } catch (_: UnrecoverableKeyException) {
            resetKeyAndEncryptedStateLocked()
            null
        }
        return existing as? SecretKey ?: createKeyLocked()
    }

    private fun createKeyLocked(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeyStoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KeySizeBits)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun resetKeyAndEncryptedStateLocked() {
        if (keyStore.containsAlias(KeyAlias)) keyStore.deleteEntry(KeyAlias)
        prefs.edit().clear().commit()
    }

    private fun removeLocked(key: String) {
        prefs.edit().remove(key).commit()
    }

    private fun aadFor(key: String): ByteArray =
        "$EnvelopeVersion:$key".toByteArray(StandardCharsets.UTF_8)

    private fun isKeyGone(error: Exception): Boolean =
        error is UnrecoverableKeyException || error is KeyPermanentlyInvalidatedException

    /** A value this key did not seal (a failed tag check) or one not in the envelope's shape. */
    private fun isDamagedValue(error: Exception): Boolean =
        error is AEADBadTagException || error is IllegalArgumentException

    internal companion object {
        const val PreferencesName = "halo.auth"
        const val KeyAlias = "moe.ditto.halo.auth.aes"
        const val EnvelopeVersion = "v1"

        private const val KeyStoreProvider = "AndroidKeyStore"
        private const val Transformation = "AES/GCM/NoPadding"
        private const val KeySizeBits = 256
        private const val GcmTagLengthBits = 128
    }
}
