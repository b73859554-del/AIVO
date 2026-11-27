package io.aivo.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AivoSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val email: String,
)

internal interface SessionStore {
    fun read(): AivoSession?
    fun write(session: AivoSession)
    fun clear()
}

internal class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val vault = AesGcmVault(AndroidKeystoreKeyProvider(KEY_ALIAS))
    private val gson = Gson()

    override fun read(): AivoSession? {
        val encrypted = preferences.getString(SESSION_KEY, null) ?: return null
        val parsed = try {
            gson.fromJson(vault.decrypt(encrypted), AivoSession::class.java)
        } catch (exception: Exception) {
            throw IllegalStateException("The saved AIVO session could not be decrypted.", exception)
        }
        return try {
            AivoSessionRules.validate(parsed)
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("The saved AIVO session is invalid.", exception)
        }
    }

    override fun write(session: AivoSession) {
        AivoSessionRules.validate(session)
        val encrypted = vault.encrypt(gson.toJson(session))
        check(preferences.edit().putString(SESSION_KEY, encrypted).commit()) {
            "The AIVO session could not be saved to private app storage."
        }
    }

    override fun clear() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "The saved AIVO session could not be removed from private app storage."
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "aivo_secure_store"
        const val SESSION_KEY = "encrypted_session_v1"
        const val KEY_ALIAS = "io.aivo.app.session.aes.v1"
    }
}

internal object AivoSessionRules {
    fun validate(session: AivoSession?): AivoSession {
        requireNotNull(session) { "The saved AIVO session is empty." }
        require(session.accessToken.isNotBlank()) { "AIVO returned an empty access token." }
        require(session.refreshToken.isNotBlank()) { "AIVO returned an empty refresh token." }
        require(session.expiresAtEpochSeconds > 0) { "AIVO returned an invalid session expiry." }
        require(session.email.isNotBlank()) { "AIVO returned no account email." }
        return session
    }
}

internal fun interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
}

internal class AesGcmVault(private val keyProvider: SecretKeyProvider) {
    fun encrypt(plaintext: String): String {
        require(plaintext.isNotEmpty()) { "Cannot encrypt an empty value." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            FORMAT_VERSION,
            encoder.encodeToString(cipher.iv),
            encoder.encodeToString(ciphertext),
        ).joinToString(".")
    }

    fun decrypt(encoded: String): String {
        val parts = encoded.split('.', limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unsupported encrypted data format." }
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(parts[1])
        require(iv.size == GCM_IV_BYTES) { "Invalid encrypted data IV." }
        val ciphertext = decoder.decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

internal class AndroidKeystoreKeyProvider(private val alias: String) : SecretKeyProvider {
    override fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
