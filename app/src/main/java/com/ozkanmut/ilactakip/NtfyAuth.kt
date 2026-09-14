package com.ozkanmut.ilactakip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object NtfyCredentialStore {
    private const val KEY_ALIAS = "dosefolk_ntfy_credential_key_v1"
    private const val PREFS = "dosefolk_ntfy_credentials"
    private const val CIPHERTEXT = "credential_ciphertext"
    private const val IV = "credential_iv"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun save(c: Context, credential: String) {
        val value = credential.trim()
        require(value.isNotEmpty()) { "credential must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    fun load(c: Context): String? = runCatching {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(CIPHERTEXT, null)?.takeIf { it.isNotBlank() } ?: return null
        val iv = prefs.getString(IV, null)?.takeIf { it.isNotBlank() } ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun clear(c: Context) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

object NtfyAuth {
    internal fun bearerValue(credential: String?): String? =
        credential?.trim()?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

    fun apply(c: Context, connection: HttpURLConnection) {
        bearerValue(NtfyCredentialStore.load(c))?.let {
            connection.setRequestProperty("Authorization", it)
        }
    }
}
