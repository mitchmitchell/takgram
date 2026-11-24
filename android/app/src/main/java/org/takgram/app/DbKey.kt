package org.takgram.app

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The encryption key for TDLib's local database (which holds the Telegram session).
 *
 * A random 32-byte key is stored in the app's preferences, wrapped by an AES key that lives in the
 * Android Keystore and never leaves it. Only databases created by this version onwards get a key;
 * an older, unencrypted database keeps working with an empty key.
 */
object DbKey {
    private const val ALIAS = "takgram-db-wrap"
    private const val STORE = "AndroidKeyStore"

    /** @return the database key, or an empty array for "not encrypted". */
    fun load(prefs: SharedPreferences, name: String, createIfMissing: Boolean): ByteArray {
        try {
            val stored = prefs.getString(name, null)
            if (stored != null) {
                val blob = Base64.decode(stored, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
                return cipher.doFinal(blob, 12, blob.size - 12)
            }
            if (!createIfMissing) return ByteArray(0)
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
            val blob = cipher.iv + cipher.doFinal(key)
            // commit(), not apply(): the key must be on disk before the database is created with it.
            if (!prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) return ByteArray(0)
            return key
        } catch (e: Exception) {
            Log.e(Tg.TAG, "database key unavailable", e)
            // With a stored key we must not fall back to "no key": TDLib would report the mismatch.
            return if (prefs.contains(name)) ByteArray(32) else ByteArray(0)
        }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }
}
