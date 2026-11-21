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

    /**
     * Not secret, and apart from [prefs] so a reset cannot erase it: how many
     * separate launches have seen a write fail on a plain InvalidKeyException.
     */
    private val health = context.getSharedPreferences(HealthPreferencesName, Context.MODE_PRIVATE)
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
     * Encrypts with the current key. A key that is gone for good is replaced,
     * which takes every value sealed with it. A plain InvalidKeyException,
     * which some devices raise for a passing Keystore fault, is tried once
     * more and then fails this write alone, keeping every stored value. Only
     * when that keeps happening on [BrokenKeyLaunches] separate launches,
     * with no success between, is the key taken as broken and replaced:
     * otherwise a key that stays broken would block every sign-in for good,
     * with clearing the app's data (downloads included) the only way out.
     * The reset touches only this store, never the downloads.
     */
    private fun encryptWithRecoveryLocked(key: String, plaintext: String): String {
        val envelope = try {
            encryptLocked(key, plaintext, getOrCreateKeyLocked())
        } catch (error: Exception) {
            if (isKeyGone(error)) {
                resetKeyAndEncryptedStateLocked()
                val sealed = encryptLocked(key, plaintext, createKeyLocked())
                keyWorkedLocked()
                return sealed
            }
            if (error !is InvalidKeyException) throw error
            try {
                encryptLocked(key, plaintext, getOrCreateKeyLocked())
            } catch (again: InvalidKeyException) {
                if (!keyFailedOnEnoughLaunchesLocked()) throw again
                resetKeyAndEncryptedStateLocked()
                encryptLocked(key, plaintext, createKeyLocked())
            }
        }
        keyWorkedLocked()
        return envelope
    }

    /**
     * Counts this launch once; true once [BrokenKeyLaunches] launches have
     * failed with no read or write succeeding between, each within
     * [BrokenKeyWindowMs] of the one before. Faults far apart start over.
     */
    private fun keyFailedOnEnoughLaunchesLocked(): Boolean {
        val counted = health.getString(LastFailedLaunchKey, null) == launchId
        if (counted) return health.getInt(FailedLaunchesKey, 0) >= BrokenKeyLaunches
        val now = System.currentTimeMillis()
        val previousAt = health.getLong(LastFailedAtKey, 0L)
        val recent = previousAt > 0L && now - previousAt in 0..BrokenKeyWindowMs
        val failures = (if (recent) health.getInt(FailedLaunchesKey, 0) else 0) + 1
        health.edit()
            .putInt(FailedLaunchesKey, failures)
            .putString(LastFailedLaunchKey, launchId)
            .putLong(LastFailedAtKey, now)
            .commit()
        return failures >= BrokenKeyLaunches
    }

    private fun keyWorkedLocked() {
        if (health.contains(FailedLaunchesKey)) health.edit().clear().commit()
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
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).also {
                // A key that reads is not broken: earlier failures were passing ones.
                keyWorkedLocked()
            }
        } catch (error: Exception) {
            // Only a key that is gone for good takes every value with it, and
            // only a value that is itself damaged is dropped. Anything else
            // (some devices report a passing Keystore fault as a plain
            // InvalidKeyException) keeps the data for the next read; a key
            // that stays broken is replaced by a write once it has failed on
            // several launches (encryptWithRecoveryLocked).
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
        const val HealthPreferencesName = "halo.auth.health"

        /** Separate launches with a failing key before it is replaced. */
        const val BrokenKeyLaunches = 3
        private const val FailedLaunchesKey = "failedLaunches"
        private const val LastFailedLaunchKey = "lastFailedLaunch"
        private const val LastFailedAtKey = "lastFailedAt"

        /** Failing launches further apart than this do not add up. */
        private const val BrokenKeyWindowMs = 7L * 24 * 60 * 60 * 1000

        /** One per process, so a burst of failures within one run counts once. */
        private val launchId: String = java.util.UUID.randomUUID().toString()

        private const val KeyStoreProvider = "AndroidKeyStore"
        private const val Transformation = "AES/GCM/NoPadding"
        private const val KeySizeBits = 256
        private const val GcmTagLengthBits = 128
    }
}
