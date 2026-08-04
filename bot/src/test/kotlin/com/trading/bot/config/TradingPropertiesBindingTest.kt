package com.trading.bot.config

import com.trading.common.config.TradingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * `application.yml` 이 `trading.*` 키를 정의하지 않아도 `TRADING_*` 환경변수가 바인딩되는지 검증한다.
 * 기본값 정의처를 `TradingProperties` 한 곳으로 줄이는 설계(#75)가 이 동작에 의존한다.
 *
 * 환경변수는 `withPropertyValues` 로 흉내낼 수 없다 — relaxed binding 의 대문자·언더스코어 매핑은
 * `SystemEnvironmentPropertySource` 일 때만 적용된다.
 */
class TradingPropertiesBindingTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)

    private fun withEnv(vararg pairs: Pair<String, String>) = runner.withInitializer { ctx ->
        ctx.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource("test-env", mapOf(*pairs)),
        )
    }

    @EnableConfigurationProperties(TradingProperties::class)
    class Config

    @Test
    fun `TRADING_ 환경변수가 relaxed binding 으로 오버라이드한다`() {
        withEnv("TRADING_TAKE_PROFIT_PCT" to "7.5", "TRADING_TRAILING_ARM_PCT" to "4.25")
            .run { ctx ->
                val p = ctx.getBean(TradingProperties::class.java)
                assertThat(p.takeProfitPct).isEqualTo(7.5)
                assertThat(p.trailingArmPct).isEqualTo(4.25)
                // 지정하지 않은 키는 기본값 유지
                assertThat(p.trailingStopPct).isEqualTo(2.0)
            }
    }

    @Test
    fun `일부 키만 지정해도 나머지는 기본값이다`() {
        withEnv("TRADING_MAX_HOLD_DAYS" to "3")
            .run { ctx ->
                val p = ctx.getBean(TradingProperties::class.java)
                assertThat(p.maxHoldDays).isEqualTo(3)
                assertThat(p.takeProfitPct).isEqualTo(5.0)
            }
    }

    /**
     * 배포 계층이 빈 문자열을 주입하면 기동이 깨진다 — 그래서 "줄을 아예 쓰지 않는" 설계를 택했다.
     * 이 테스트는 그 위험이 실재함을 고정한다.
     */
    @Test
    fun `빈 문자열 주입은 바인딩 실패다`() {
        withEnv("TRADING_TAKE_PROFIT_PCT" to "")
            .run { ctx ->
                // 실패했다는 것만으로는 부족하다 — 다른 원인의 컨텍스트 실패와 구분한다.
                // 체인: ConfigurationPropertiesBindException → BindException → IllegalArgumentException
                // (primitive double 에 null 대입). 그래서 root 가 아니라 체인 전체로 확인한다.
                assertThat(ctx).getFailure().hasCauseInstanceOf(BindException::class.java)
                assertThat(ctx).getFailure()
                    .hasStackTraceContaining("Failed to bind properties under 'trading.take-profit-pct'")
            }
    }

    @Test
    fun `모든 TRADING_ 키가 환경변수로 오버라이드된다`() {
        withEnv(
            "TRADING_TICKERS" to "KRW-SOL",
            "TRADING_STRATEGY" to "mean_reversion",
            "TRADING_INVEST_RATIO" to "0.25",
            "TRADING_MAX_INVEST_AMOUNT" to "250000",
            "TRADING_K_VALUE" to "0.7",
            "TRADING_TAKE_PROFIT_PCT" to "6.5",
            "TRADING_MAX_LOSS_PCT" to "4.5",
            "TRADING_TRAILING_STOP_PCT" to "1.5",
            "TRADING_TRAILING_ARM_PCT" to "2.5",
            "TRADING_MAX_HOLD_DAYS" to "4",
            "TRADING_ROUND_TRIP_FEE_RATE" to "0.002",
            "TRADING_INTERVAL_SECONDS" to "20",
            "TRADING_AUTO_START" to "true",
            "TRADING_CHART_EXIT_ENABLED" to "true",
        ).run { ctx ->
            val p = ctx.getBean(TradingProperties::class.java)
            assertThat(p.tickers).isEqualTo("KRW-SOL")
            assertThat(p.strategy).isEqualTo("mean_reversion")
            assertThat(p.investRatio).isEqualTo(0.25)
            assertThat(p.maxInvestAmount).isEqualTo(250_000.0)
            assertThat(p.kValue).isEqualTo(0.7)
            assertThat(p.takeProfitPct).isEqualTo(6.5)
            assertThat(p.maxLossPct).isEqualTo(4.5)
            assertThat(p.trailingStopPct).isEqualTo(1.5)
            assertThat(p.trailingArmPct).isEqualTo(2.5)
            assertThat(p.maxHoldDays).isEqualTo(4)
            assertThat(p.roundTripFeeRate).isEqualTo(0.002)
            assertThat(p.intervalSeconds).isEqualTo(20L)
            assertThat(p.autoStart).isTrue()
            assertThat(p.chartExitEnabled).isTrue()
        }
    }

    @Test
    fun `환경변수가 없으면 모든 필드가 data class 기본값이다`() {
        runner.run { ctx ->
            val p = ctx.getBean(TradingProperties::class.java)
            assertThat(p.tickers).isEqualTo("KRW-BTC")
            assertThat(p.strategy).isEqualTo("combined")
            assertThat(p.investRatio).isEqualTo(0.1)
            assertThat(p.maxInvestAmount).isEqualTo(100_000.0)
            assertThat(p.kValue).isEqualTo(0.5)
            assertThat(p.takeProfitPct).isEqualTo(5.0)
            assertThat(p.maxLossPct).isEqualTo(5.0)
            assertThat(p.trailingStopPct).isEqualTo(2.0)
            assertThat(p.trailingArmPct).isEqualTo(3.0)
            assertThat(p.maxHoldDays).isEqualTo(1)
            assertThat(p.roundTripFeeRate).isEqualTo(0.001)
            assertThat(p.intervalSeconds).isEqualTo(10L)
            assertThat(p.autoStart).isFalse()
            assertThat(p.chartExitEnabled).isFalse()
            assertThat(p.reconcileHaltThreshold).isEqualTo(20)
        }
    }

    @Test
    fun `기본값 조합은 트레일링 dead 경고 조건에 걸리지 않는다`() {
        runner.run { ctx ->
            val p = ctx.getBean(TradingProperties::class.java)
            // TradingEngine.warnIfExitConfigInert() 의 두 조건
            assertThat(p.takeProfitPct).isGreaterThan(p.trailingStopPct)
            assertThat(p.takeProfitPct).isGreaterThan(p.trailingArmPct)
            assertThat(p.trailingArmPct).isGreaterThan(p.trailingStopPct)
        }
    }
}
