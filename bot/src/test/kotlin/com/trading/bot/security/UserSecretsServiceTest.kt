package com.trading.bot.security

import com.trading.bot.config.AppProperties
import com.trading.bot.persistence.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class UserSecretsServiceTest {
    private val crypto = SecretsCrypto(
        SecretKeyMaterialProvider(AppProperties(jwtSecret = "test-secret", encryptionSecret = "encrypt-secret"), MockEnvironment()),
    )
    private val service = UserSecretsService(crypto)
    private val webhook = "https://discord.com/api/webhooks/1/user-token"

    private fun user(stored: String?) = UserEntity(id = 1L, username = "u", password = "p", discordWebhookUrl = stored)

    @Test
    fun `an encrypted webhook is decrypted`() {
        assertEquals(webhook, service.discordWebhookOf(user(crypto.encrypt(webhook))))
    }

    // 암호화 전에 저장된 행도 그대로 쓰인다.
    @Test
    fun `a plain webhook passes through`() {
        assertEquals(webhook, service.discordWebhookOf(user(webhook)))
    }

    // 없거나 빈 값은 null — notifier 가 전역 webhook 으로 보낸다(지금과 같다).
    @Test
    fun `a missing or blank webhook is null`() {
        assertNull(service.discordWebhookOf(null))
        assertNull(service.discordWebhookOf(user(null)))
        assertNull(service.discordWebhookOf(user("")))
    }
}
