package com.streamer.timetable.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the school credentials the NTLM handshake needs.
 *
 * NTLM has no session token to fall back on: every request is authenticated from
 * the password itself, so unlike a cookie-based login the app has no choice but to
 * keep the real password. It is therefore encrypted with an AES-GCM key held in the
 * Android Keystore, which means the key material never enters app memory and the
 * ciphertext is useless if the preferences file is copied off the device.
 *
 * A hand-rolled Keystore wrapper is used in preference to androidx.security's
 * EncryptedSharedPreferences, which is still an alpha artifact and has been
 * deprecated upstream.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("streamer_credentials", Context.MODE_PRIVATE)

    data class Credentials(
        val username: String,
        val password: String,
        val domain: String,
    )

    fun save(username: String, password: String, domain: String) {
        val (iv, ciphertext) = encrypt(password)
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_DOMAIN, domain)
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(KEY_PASSWORD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Credentials? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val ivEncoded = prefs.getString(KEY_IV, null) ?: return null
        val passwordEncoded = prefs.getString(KEY_PASSWORD, null) ?: return null
        val domain = prefs.getString(KEY_DOMAIN, "").orEmpty()

        return try {
            val password = decrypt(
                Base64.decode(ivEncoded, Base64.NO_WRAP),
                Base64.decode(passwordEncoded, Base64.NO_WRAP),
            )
            Credentials(username, password, domain)
        } catch (e: Exception) {
            // The Keystore key is invalidated if the device lock is removed or the
            // app data is restored onto another device. Treat that as logged out
            // rather than crashing on every launch.
            clear()
            null
        }
    }

    fun hasCredentials(): Boolean = prefs.contains(KEY_PASSWORD)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return cipher.iv to ciphertext
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication: background syncs
                // run while the device is locked, and gating on unlock would stop them.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "streamer_credential_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128

        const val KEY_USERNAME = "username"
        const val KEY_DOMAIN = "domain"
        const val KEY_PASSWORD = "password_ciphertext"
        const val KEY_IV = "password_iv"
    }
}
