package com.example.guardian_iot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtSeconds: Long,
    val userId: String,
    val email: String,
)

class EncryptedSessionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "supabase-session.v1")

    @Synchronized
    fun read(): AuthSession? = runCatching {
        if (!file.isFile) return null
        val parts = file.readText(Charsets.UTF_8).split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        AuthSession(
            json.getString("access_token"),
            json.getString("refresh_token"),
            json.optLong("expires_at", 0L),
            json.optString("user_id"),
            json.optString("email"),
        ).takeIf { it.accessToken.isNotBlank() && it.refreshToken.isNotBlank() }
    }.getOrNull()

    @Synchronized
    fun write(session: AuthSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(
            JSONObject()
                .put("access_token", session.accessToken)
                .put("refresh_token", session.refreshToken)
                .put("expires_at", session.expiresAtSeconds)
                .put("user_id", session.userId)
                .put("email", session.email)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(encoded, Charsets.UTF_8)
        check(temporary.renameTo(file) || (file.delete() && temporary.renameTo(file))) {
            "No se pudo guardar la sesión cifrada."
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tzinda.supabase.session.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

