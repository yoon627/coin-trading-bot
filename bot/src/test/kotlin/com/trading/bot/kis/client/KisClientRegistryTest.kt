package com.trading.bot.kis.client

import com.trading.bot.kis.config.KisApiProperties
import com.trading.bot.kis.config.KisTradingProperties
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class KisClientRegistryTest {

    @EnableConfigurationProperties(KisTradingProperties::class, KisApiProperties::class)
    class Config

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java, KisClientRegistry::class.java)

    private fun registry(liveEnabled: Boolean = false) =
        KisClientRegistry(KisTradingProperties(enabled = true, liveEnabled = liveEnabled), KisApiProperties())

    @Test
    fun `kis_trading_enabled 가 없거나 false 면 빈이 없고 true 면 있다`() {
        runner.run { assertThat(it).doesNotHaveBean(KisClientRegistry::class.java) }
        runner.withPropertyValues("kis.trading.enabled=false").run { assertThat(it).doesNotHaveBean(KisClientRegistry::class.java) }
        runner.withPropertyValues("kis.trading.enabled=true").run { assertThat(it).hasSingleBean(KisClientRegistry::class.java) }
    }

    @Test
    fun `설정 오류가 있어도 컨텍스트는 뜨고 KIS 클라이언트 요청만 키 이름을 담아 거절한다`() {
        runner.withPropertyValues(
            "kis.trading.enabled=true",
            "kis.trading.paper-min-request-interval-ms=0",
            "kis.api.query-max-attempts=11",
            "kis.api.token-min-issue-interval-seconds=0",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val error = assertThrows<IllegalStateException> {
                context.getBean(KisClientRegistry::class.java).api(KisEnvironment.PAPER, TEST_ACCESS)
            }
            assertThat(error.message)
                .contains("kis.trading.paper-min-request-interval-ms=0")
                .contains("kis.api.query-max-attempts=11")
                .contains("kis.api.token-min-issue-interval-seconds=0")
        }
    }

    @Test
    fun `같은 키면 같은 인스턴스, 키가 다르면 다른 인스턴스다`() {
        val registry = registry()
        val sameKeys = KisAccess(TEST_ACCESS.appKey, TEST_ACCESS.appSecret, TEST_ACCESS.cano, TEST_ACCESS.productCode)
        val otherKeys = KisAccess("other-app-key", TEST_ACCESS.appSecret, TEST_ACCESS.cano, TEST_ACCESS.productCode)

        val api = registry.api(KisEnvironment.PAPER, TEST_ACCESS)

        assertThat(registry.api(KisEnvironment.PAPER, sameKeys)).isSameAs(api)
        assertThat(registry.session(KisEnvironment.PAPER, sameKeys)).isSameAs(registry.session(KisEnvironment.PAPER, TEST_ACCESS))
        assertThat(registry.api(KisEnvironment.PAPER, otherKeys)).isNotSameAs(api)
        assertThat(registry.session(KisEnvironment.PAPER, otherKeys).tokens).isNotSameAs(registry.session(KisEnvironment.PAPER, TEST_ACCESS).tokens)
        registry.close()
    }

    @Test
    fun `liveEnabled 가 false 면 실전 클라이언트를 거절한다`() {
        assertThrows<IllegalStateException> { registry().api(KisEnvironment.LIVE, TEST_ACCESS) }
        val live = registry(liveEnabled = true)
        assertThat(live.api(KisEnvironment.LIVE, TEST_ACCESS)).isNotSameAs(live.api(KisEnvironment.PAPER, TEST_ACCESS))
        live.close()
    }

    @Test
    fun `evict 는 리미터를 닫고 다음 요청에 새 세션을 만든다`() = runTest {
        val registry = registry()
        val apiBefore = registry.api(KisEnvironment.PAPER, TEST_ACCESS)
        val before = registry.session(KisEnvironment.PAPER, TEST_ACCESS)

        registry.evict(KisEnvironment.PAPER, TEST_ACCESS)

        assertThrows<KisTransientException> { before.limiter.acquire(KisLane.ORDER) }
        assertThat(registry.api(KisEnvironment.PAPER, TEST_ACCESS)).isNotSameAs(apiBefore)
        val after = registry.session(KisEnvironment.PAPER, TEST_ACCESS)
        assertThat(after).isNotSameAs(before)
        after.limiter.acquire(KisLane.ORDER)
        registry.close()
        assertThrows<KisTransientException> { after.limiter.acquire(KisLane.ORDER) }
        assertThrows<IllegalStateException> { registry.api(KisEnvironment.PAPER, TEST_ACCESS) }
    }

    @Test
    fun `evict 전에 받은 KisApi 는 토큰 발급도 보내지 않고 바로 실패한다`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val registry = KisClientRegistry(KisTradingProperties(enabled = true), KisApiProperties(paperBaseUrl = server.baseUrl()))
            val api = registry.api(KisEnvironment.PAPER, TEST_ACCESS)

            registry.evict(KisEnvironment.PAPER, TEST_ACCESS)

            assertThrows<KisTransientException> { api.currentPrice("005930", KisLane.QUOTE) }
            assertThrows<KisTransientException> {
                api.placeCashOrder(KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 1))
            }
            assertThat(server.requestCount).isZero()
            registry.close()
        } finally {
            server.shutdown()
        }
    }
}
