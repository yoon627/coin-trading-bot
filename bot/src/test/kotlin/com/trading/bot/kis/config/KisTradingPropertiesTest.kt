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
                "kis.trading.interval-seconds=0 (허용 [1, 60])",
            )
            // 주기는 구간 표가 하한까지 보므로 프로파일 검사에 같은 키가 두 번 나오지 않는다.
            assertThat(KisExitParamRanges.problems(kis).filter { it.startsWith("kis.trading.interval-seconds=") }).hasSize(1)
            // 상한 60 을 넘으면 KIS 클라이언트를 만들지 않는 프로파일 문제다.
            assertThat(KisExitParamRanges.problems(KisTradingProperties(intervalSeconds = 61)))
                .containsExactly("kis.trading.interval-seconds=61 (허용 [1, 60])")
        }
    }

    @Test
    fun `KIS 구간은 TRADING 의 같은 이름 항목과 같고 키는 kis_trading 바인딩 키다`() {
        val trading = ExitParamRanges.TRADING.associateBy { it.property.name }
        assertThat(KisExitParamRanges.KIS.map { it.property.name }).containsExactlyInAnyOrder(
            "takeProfitPct", "maxLossPct", "trailingStopPct", "trailingArmPct", "maxHoldDays", "investRatio", "intervalSeconds",
            *COST_RATE_NAMES.toTypedArray(),
        )
        val prefix = KisTradingProperties::class.findAnnotation<ConfigurationProperties>()!!.prefix
        KisExitParamRanges.KIS.forEach { entry ->
            if (entry.property.name !in COST_RATE_NAMES) {
                assertThat(entry.range).describedAs(entry.key).isEqualTo(trading.getValue(entry.property.name).range)
            }
            assertThat(entry.key.substringBeforeLast('.')).isEqualTo(prefix)
        }
        assertThat(KisExitParamRanges.violations(KisTradingProperties())).isEmpty()
    }

    @Test
    fun `비용 요율 기본값은 편도 수수료 0_0002·매도 거래세 0_002 이고 구간은 비율 0 이상 0_01 미만이다`() {
        val kis = KisTradingProperties()
        assertThat(kis.commissionRate).isEqualTo(0.0002)
        assertThat(kis.sellTaxRate).isEqualTo(0.002)
        val cost = KisExitParamRanges.KIS.filter { it.property.name in COST_RATE_NAMES }
        assertThat(cost.map { it.key }).containsExactly("kis.trading.commission-rate", "kis.trading.sell-tax-rate")
        cost.forEach { assertThat(it.range).describedAs(it.key).isEqualTo(ExitParamRanges.Range(0.0, true, 0.01, false)) }
        assertThat(KisExitParamRanges.violations(KisTradingProperties(commissionRate = 0.2, sellTaxRate = -0.001))).containsExactly(
            "kis.trading.commission-rate=0.2 (허용 [0, 0.01))",
            "kis.trading.sell-tax-rate=-0.001 (허용 [0, 0.01))",
        )
    }

    private companion object {
        // TRADING 에 같은 이름이 없어 KIS 가 자체 구간을 두는 항목.
        val COST_RATE_NAMES = listOf("commissionRate", "sellTaxRate")
    }
}
