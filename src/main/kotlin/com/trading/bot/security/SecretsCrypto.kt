package com.trading.bot.security

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class SecretsCrypto(
    secretKeyMaterialProvider: SecretKeyMaterialProvider,
) {
    private val keySpec = SecretKeySpec(secretKeyMaterialProvider.encryptionKeyBytes(), "AES")
    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(GCM_IV_LENGTH).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decryptIfNeeded(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        if (!value.startsWith(PREFIX)) {
            return value
        }
        val decoded = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        val iv = decoded.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = decoded.copyOfRange(GCM_IV_LENGTH, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    companion object {
        private const val PREFIX = "enc:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
