package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 체결 알림의 잔고 칸은 덤이라 조회가 안 되면 null 이고 예외를 내지 않는다. */
class ExchangeBalanceLookupTest {

    private val user = UserEntity(id = 1L, username = "u", password = "p")
    private val decrypted = user.copy(upbitAccessKey = "ak", upbitSecretKey = "sk")
    private val client = mockk<ExchangeClient>()
    private val manager = mockk<UserTradingManager> { every { queryClient(decrypted) } returns client }
    private val managers = mockk<TradingManagers> {
        every { find(Exchange.UPBIT) } returns manager
        every { find(Exchange.KIS) } returns null
    }
    private val secrets = mockk<UserSecretsService> { every { decryptUserSecrets(user) } returns decrypted }
    private val lookup = ExchangeBalanceLookup(managers, secrets)

    @Test
    fun `reads the KRW balance with the decrypted keys of that exchange's manager`() = runTest {
        coEvery { client.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.1"), Account(currency = "KRW", balance = "900000"))

        assertEquals(900000.0, lookup.krwBalance(user, "UPBIT"))
    }

    @Test
    fun `an unknown exchange, a missing manager or a failed lookup gives no balance`() = runTest {
        coEvery { client.getAccounts() } throws IllegalStateException("upbit down")

        assertNull(lookup.krwBalance(user, "NOPE"))
        assertNull(lookup.krwBalance(user, "KIS"))
        assertNull(lookup.krwBalance(user, "UPBIT"))
    }
}
