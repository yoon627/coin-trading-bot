package com.trading.bot.engine

import com.trading.bot.kis.config.KisTradingProperties
import com.trading.bot.kis.keys.KisKeyController
import com.trading.bot.kis.keys.KisKeyStore
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserBrokerKeyRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.SecretsCrypto
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClient

class TradingManagersTest {

    private fun manager(ex: Exchange): UserTradingManager = mockk { every { exchange } returns ex }

    @Test
    fun `매니저를 자기 거래소로 색인한다`() {
        val upbit = manager(Exchange.UPBIT)
        val paper = manager(Exchange.KIS_PAPER)
        val managers = TradingManagers(listOf(upbit, paper))

        assertSame(upbit, managers.get(Exchange.UPBIT))
        assertSame(paper, managers.get(Exchange.KIS_PAPER))
        assertSame(paper, managers.find(Exchange.KIS_PAPER))
        assertNull(managers.find(Exchange.KIS))
        assertEquals(listOf(upbit, paper), managers.all())
    }

    @Test
    fun `등록되지 않은 거래소는 400 으로 나가는 예외다`() {
        val managers = TradingManagers(listOf(manager(Exchange.UPBIT)))

        val ex = assertThrows<UnsupportedExchangeException> { managers.get(Exchange.KIS_PAPER) }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals(Exchange.KIS_PAPER, ex.exchange)
    }

    @Test
    fun `같은 거래소 매니저가 둘이면 만들지 않는다`() {
        assertThrows<IllegalStateException> {
            TradingManagers(listOf(manager(Exchange.UPBIT), manager(Exchange.UPBIT)))
        }
    }

    @Test
    fun `reloadAll 은 실패가 있어도 모두 부르고 첫 실패를 그대로 던진다`() = runTest {
        val upbit = manager(Exchange.UPBIT)
        val paper = manager(Exchange.KIS_PAPER)
        val live = manager(Exchange.KIS)
        val first = RuntimeReloadFailedException(1L, RuntimeException("db down"), engineRestored = true)
        val second = IllegalStateException("kis down")
        coEvery { upbit.reloadUserRuntime(1L) } throws first
        coEvery { paper.reloadUserRuntime(1L) } throws second
        coEvery { live.reloadUserRuntime(1L) } returns Unit

        val thrown = runCatching { TradingManagers(listOf(upbit, paper, live)).reloadAll(1L) }.exceptionOrNull()

        assertSame(first, thrown)
        assertTrue(first.suppressed.contains(second))
        coVerify(exactly = 1) { upbit.reloadUserRuntime(1L) }
        coVerify(exactly = 1) { paper.reloadUserRuntime(1L) }
        coVerify(exactly = 1) { live.reloadUserRuntime(1L) }
    }

    @Test
    fun `reloadAll 이 취소되면 취소가 전파되고 남은 매니저는 부르지 않는다`() = runTest {
        val upbit = manager(Exchange.UPBIT)
        val paper = manager(Exchange.KIS_PAPER)
        coEvery { upbit.reloadUserRuntime(1L) } coAnswers { awaitCancellation() }
        coEvery { paper.reloadUserRuntime(1L) } returns Unit
        var failure: Throwable? = null

        val job = launch {
            try {
                TradingManagers(listOf(upbit, paper)).reloadAll(1L)
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()

        assertTrue(failure is CancellationException, "취소가 아닌 $failure 가 났다")
        coVerify(exactly = 0) { paper.reloadUserRuntime(any()) }
    }

    private fun wiring(): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(UserTradingManager::class.java, TradingManagers::class.java)
        .withBean(UserRepository::class.java, { mockk<UserRepository>(relaxed = true) })
        .withBean(BotStateRepository::class.java, { mockk<BotStateRepository>(relaxed = true) })
        .withBean(TradeExecutionService::class.java, { mockk<TradeExecutionService>(relaxed = true) })
        .withBean(DiscordNotifier::class.java, { mockk<DiscordNotifier>(relaxed = true) })
        .withBean(TradingStrategy::class.java, { mockk<TradingStrategy>(relaxed = true) })
        .withBean(TradingProperties::class.java, { TradingProperties() })
        .withBean(WebClient::class.java, { mockk<WebClient>(relaxed = true) })
        .withBean(UserSecretsService::class.java, { mockk<UserSecretsService>(relaxed = true) })
        .withBean(MarketDataStore::class.java, { mockk<MarketDataStore>(relaxed = true) })
        .withBean(TradingStateService::class.java, { mockk<TradingStateService>(relaxed = true) })

    @Test
    fun `실제 빈 배선에서 레지스트리는 Upbit 매니저 하나를 받는다`() {
        wiring().run { context ->
            assertThat(context).hasNotFailed()
            val upbit = context.getBean(UserTradingManager::class.java)
            val managers = context.getBean(TradingManagers::class.java)
            assertEquals(Exchange.UPBIT, upbit.exchange)
            assertEquals(listOf(upbit), managers.all())
            assertSame(upbit, managers.get(Exchange.UPBIT))
        }
    }

    @EnableConfigurationProperties(KisTradingProperties::class)
    class KisConfig

    @Test
    fun `KIS 를 켠 실제 빈 배선에서도 KIS 키 컨트롤러가 레지스트리를 받아 뜬다`() {
        wiring()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
            .withUserConfiguration(KisConfig::class.java, KisKeyStore::class.java, KisKeyController::class.java)
            .withBean(UserBrokerKeyRepository::class.java, { mockk<UserBrokerKeyRepository>() })
            .withBean(SecretsCrypto::class.java, { mockk<SecretsCrypto>() })
            .withPropertyValues("kis.trading.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(KisKeyController::class.java)
                assertEquals(listOf(context.getBean(UserTradingManager::class.java)), context.getBean(TradingManagers::class.java).all())
            }
    }
}
