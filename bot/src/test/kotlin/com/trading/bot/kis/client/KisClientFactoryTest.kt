package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisProperties
import com.trading.bot.security.UserSecretsService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KisClientFactoryTest {

    private val userSecretsService = mockk<UserSecretsService>()

    @Test
    fun `defaultClient is null when global appKey not configured`() {
        val factory = KisClientFactory(KisProperties(), userSecretsService)
        assertNull(factory.defaultClient())
    }

    @Test
    fun `defaultClient builds and caches when global credentials present`() {
        val props = KisProperties(
            appKey = "ak", appSecret = "sk", cano = "12345678", acntPrdtCd = "01", paper = true,
        )
        val factory = KisClientFactory(props, userSecretsService)

        val c1 = factory.defaultClient()
        val c2 = factory.defaultClient()

        assertNotNull(c1)
        assertSame(c1, c2) // 토큰 캐시 보존 위해 동일 인스턴스 재사용
    }
}
