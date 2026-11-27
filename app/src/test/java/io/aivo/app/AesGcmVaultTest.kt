package io.aivo.app

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmVaultTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    private val provider = SecretKeyProvider { key }

    @Test
    fun encryptsAndDecryptsWithRandomizedCiphertext() {
        val vault = AesGcmVault(provider)
        val first = vault.encrypt("private session data")
        val second = vault.encrypt("private session data")

        assertEquals("private session data", vault.decrypt(first))
        assertNotEquals(first, second)
    }

    @Test
    fun rejectsTamperedCiphertextAndWrongKey() {
        val ciphertext = AesGcmVault(provider).encrypt("private chat")
        val replacementKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val wrongKeyVault = AesGcmVault(SecretKeyProvider { replacementKey })
        val parts = ciphertext.split('.')
        val encryptedBytes = Base64.getUrlDecoder().decode(parts[2])
        encryptedBytes[0] = (encryptedBytes[0].toInt() xor 1).toByte()
        val tampered = listOf(
            parts[0],
            parts[1],
            Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedBytes),
        ).joinToString(".")

        assertThrows(Exception::class.java) {
            wrongKeyVault.decrypt(ciphertext)
        }
        assertThrows(Exception::class.java) {
            AesGcmVault(provider).decrypt(tampered)
        }
    }
}
