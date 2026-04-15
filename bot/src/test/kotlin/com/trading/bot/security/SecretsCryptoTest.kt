package com.trading.bot.security

import com.trading.bot.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class SecretsCryptoTest {
    @Test
    fun `encrypts and decrypts round trip`() {
        val keyProvider = SecretKeyMaterialProvider(
            AppProperties(jwtSecret = "test-secret", encryptionSecret = "encrypt-secret"),
            MockEnvironment(),
        )
        val crypto = SecretsCrypto(keyProvider)

        val encrypted = crypto.encrypt("value-123")

        assertEquals("value-123", crypto.decryptIfNeeded(encrypted))
        assertEquals("plain", crypto.decryptIfNeeded("plain"))
    }
}
