package com.trading.bot.kis.config

import com.trading.common.config.ExitParamRanges
import com.trading.common.config.TradingProperties
import kotlin.reflect.full.findAnnotation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

class KisTradingPropertiesTest {

    @EnableConfigurationProperties(KisTradingProperties::class, KisApiProperties::class)
    class Config

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)

    @Test
    fun `투자비율·청산·주기 기본값은 TradingProperties 기본값을 따르고 나머지는 KIS 값이다`() {
        val kis = KisTradingProperties()
        val upbit = TradingProperties()
        assertThat(listOf(kis.investRatio, kis.takeProfitPct, kis.maxLossPct, kis.trailingStopPct, kis.trailingArmPct))
            .containsExactly(upbit.investRatio, upbit.takeProfitPct, upbit.maxLossPct, upbit.trailingStopPct, upbit.trailingArmPct)
        assertThat(kis.maxHoldDays).isEqualTo(upbit.maxHoldDays)
        assertThat(kis.intervalSeconds).isEqualTo(upbit.intervalSeconds)
        assertThat(kis.enabled).isFalse()
        assertThat(kis.liveEnabled).isFalse()
        assertThat(kis.maxInvestAmount).isEqualTo(1_000_000L)
        assertThat(kis.maxOrderAmount).isEqualTo(2_000_000L)
        assertThat(kis.paperMinRequestIntervalMs).isEqualTo(667L)
        assertThat(kis.liveMinRequestIntervalMs).isEqualTo(60L)
    }

    @Test
    fun `구간 밖이거나 0 인 값으로도 앱 컨텍스트가 뜨고 구간 위반은 표가 알린다`() {
        // KIS 설정 오기로 Upbit 앱 기동이 막히면 안 된다.
        runner.withInitializer { ctx ->
            ctx.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-env",
                    mapOf(
                        "KIS_TRADING_MAX_LOSS_PCT" to "-5",
                        "KIS_TRADING_MAX_HOLD_DAYS" to "0",
                        "KIS_TRADING_INTERVAL_SECONDS" to "0",
                        "KIS_TRADING_PAPER_MIN_REQUEST_INTERVAL_MS" to "0",
                        "KIS_API_QUERY_MAX_ATTEMPTS" to "0",
                    ),
                ),
            )
        }.run { ctx ->
            assertThat(ctx).hasNotFailed()
            val kis = ctx.getBean(KisTradingProperties::class.java)
            assertThat(kis.maxLossPct).isEqualTo(-5.0)
            assertThat(kis.paperMinRequestIntervalMs).isZero()
            assertThat(ctx.getBean(KisApiProperties::class.java).queryMaxAttempts).isZero()
            assertThat(KisExitParamRanges.violations(kis)).containsExactly(
                "kis.trading.max-loss-pct=-5.0 (허용 (0, 100))",
                "kis.trading.max-hold-days=0 (허용 [1, ∞))",
            )
        }
    }

    @Test
    fun `KIS 구간은 TRADING 의 같은 이름 항목과 같고 키는 kis_trading 바인딩 키다`() {
        val trading = ExitParamRanges.TRADING.associateBy { it.property.name }
        assertThat(KisExitParamRanges.KIS.map { it.property.name }).containsExactlyInAnyOrder(
            "takeProfitPct", "maxLossPct", "trailingStopPct", "trailingArmPct", "maxHoldDays", "investRatio",
        )
        val prefix = KisTradingProperties::class.findAnnotation<ConfigurationProperties>()!!.prefix
        KisExitParamRanges.KIS.forEach { entry ->
            assertThat(entry.range).describedAs(entry.key).isEqualTo(trading.getValue(entry.property.name).range)
            assertThat(entry.key.substringBeforeLast('.')).isEqualTo(prefix)
        }
        assertThat(KisExitParamRanges.violations(KisTradingProperties())).isEmpty()
    }
}
