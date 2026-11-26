package com.nuvio.app.features.media.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object MediaCredentialVault {
    private var preferences: SharedPreferences? = null
    private const val ALIAS = "com.streambridge.media.credentials.v1"
    @Synchronized
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("streambridge_media_credentials", Context.MODE_PRIVATE)
    }
    @Synchronized
    actual fun read(key: String): String? {
        val preferences = checkNotNull(preferences) { "Media vault not initialized" }
        val encrypted = preferences.getString(key, null) ?: return null
        return try {
            val parts = encrypted.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(key.encodeToByteArray())
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString()
        } catch (_: Exception) {
            // Do not destroy a temporarily inaccessible credential or fall back to plaintext.
            throw MediaServiceException(MediaFailure.Authentication)
        }
    }
    @Synchronized
    actual fun write(key: String, value: String?) {
        val preferences = checkNotNull(preferences) { "Media vault not initialized" }
        val editor = preferences.edit()
        if (value == null) {
            check(editor.remove(key).commit()) { "Cannot remove media credential" }
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(key.encodeToByteArray())
            val encrypted = cipher.doFinal(value.encodeToByteArray())
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(editor.putString(key, payload).commit())
        } catch (_: Exception) {
            throw MediaServiceException(MediaFailure.Unavailable)
        }
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
