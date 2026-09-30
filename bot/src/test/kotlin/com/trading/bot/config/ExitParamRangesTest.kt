package com.trading.bot.config

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.common.config.ExitParamRanges
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaGetter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 청산·주문 파라미터의 의미상 구간 (#230) — 운영값이 아니라 판정식이 뜻을 잃는 경계다. 근거는 [ExitParamRanges] KDoc.
 */
class ExitParamRangesTest {

    private val entries = (ExitParamRanges.TRADING + ExitParamRanges.SHADOW).associateBy { it.key }

    @ParameterizedTest(name = "{0}={1} → 구간 안 {2}")
    @CsvSource(
        // 부호 오기 — 손절 -5 는 `pnl <= 5` 가 돼 매수 직후 손절한다.
        "trading.max-loss-pct, -5, false",
        "trading.max-loss-pct, 0, false",
        "trading.max-loss-pct, 0.01, true",
        "trading.max-loss-pct, 99.99, true",
        // 손절 100 이면 최소 주문 검사(1 - SL/100)가 0 이 돼 매수가 전부 막힌다.
        "trading.max-loss-pct, 100, false",
        "trading.take-profit-pct, 0, false",
        "trading.take-profit-pct, 0.01, true",
        "trading.take-profit-pct, 100, true",
        // 백테스트 API 가 라이브값을 폴백으로 쓰며 [0, 100] 을 요구한다.
        "trading.take-profit-pct, 100.01, false",
        "trading.trailing-stop-pct, 0, false",
        "trading.trailing-stop-pct, 1.5, true",
        "trading.trailing-stop-pct, 100, false",
        "trading.trailing-arm-pct, -0.01, false",
        "trading.trailing-arm-pct, -0.0, true",
        "trading.trailing-arm-pct, 0, true",
        "trading.trailing-arm-pct, 100, true",
        "trading.trailing-arm-pct, 100.01, false",
        "trading.max-hold-days, 0, false",
        "trading.max-hold-days, 1, true",
        "trading.invest-ratio, 0, false",
        "trading.invest-ratio, 1, true",
        "trading.invest-ratio, 1.01, false",
        "trading.round-trip-fee-rate, -0.001, false",
        "trading.round-trip-fee-rate, 0, true",
        "trading.round-trip-fee-rate, 0.009, true",
        "trading.round-trip-fee-rate, 0.01, false",
        // 0.1% 를 퍼센트 단위로 적은 오기 — 비율로는 10% 다.
        "trading.round-trip-fee-rate, 0.1, false",
        "trading.shadow-exit.trailing-stop-pct, 0, false",
        "trading.shadow-exit.trailing-stop-pct, 1.5, true",
        "trading.shadow-exit.trailing-stop-pct, 100, false",
        "trading.shadow-exit.trailing-arm-pct, -1, false",
        // 라이브 arm 의 상한은 백테 폴백 때문이라 그림자에는 없다.
        "trading.shadow-exit.trailing-arm-pct, 1000, true",
    )
    fun `구간은 판정식이 뜻을 잃는 경계에서 갈린다`(key: String, value: Double, inside: Boolean) {
        val entry = entries[key] ?: error("표에 $key 가 없다")
        assertThat(value in entry.range).isEqualTo(inside)
    }

    @Test
    fun `NaN 과 무한대는 어느 구간에도 들지 않는다`() {
        entries.values.forEach { entry ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
                assertThat(it in entry.range).describedAs("${entry.key}=$it").isFalse()
            }
        }
    }

    @Test
    fun `기본값과 운영값은 위반이 없다`() {
        assertThat(ExitParamRanges.violations(TradingProperties())).isEmpty()
        assertThat(ExitParamRanges.violations(PRODUCTION)).isEmpty()
    }

    @Test
    fun `위반은 키·값·허용 구간을 싣는다`() {
        assertThat(ExitParamRanges.violations(TradingProperties(maxLossPct = -5.0, maxHoldDays = 0))).containsExactly(
            "trading.max-loss-pct=-5.0 (허용 (0, 100))",
            "trading.max-hold-days=0 (허용 [1, ∞))",
        )
    }

    @Test
    fun `표의 키는 실제 바인딩 키다`() {
        assertThat(ExitParamRanges.TRADING.map { it.key }).containsExactly(
            "trading.take-profit-pct",
            "trading.max-loss-pct",
            "trading.trailing-stop-pct",
            "trading.trailing-arm-pct",
            "trading.max-hold-days",
            "trading.invest-ratio",
            "trading.round-trip-fee-rate",
        )
        assertThat(ExitParamRanges.SHADOW.map { it.key }).containsExactly(
            "trading.shadow-exit.trailing-stop-pct",
            "trading.shadow-exit.trailing-arm-pct",
        )
        // prefix 를 문자열로 적었으므로 소유 클래스의 @ConfigurationProperties 와 대조한다.
        entries.values.forEach { entry ->
            val owner = entry.property.javaGetter!!.declaringClass.kotlin
            assertThat(entry.key.substringBeforeLast('.')).isEqualTo(owner.findAnnotation<ConfigurationProperties>()!!.prefix)
        }
    }

    @Test
    fun `청산 스냅샷의 파라미터는 모두 구간을 가지고 선언 필수 키다`() {
        // 새 청산 파라미터를 스냅샷에 더하고 구간이나 선언 검사를 빠뜨리면 여기서 깨진다 — 빠진 쪽은 오기·누락을 조용히 통과시킨다.
        val snapshotParams = ExitParamsSnapshot::class.primaryConstructor!!.parameters.map { it.name }
        val entries = ExitParamRanges.TRADING.filter { it.property.name in snapshotParams }
        assertThat(entries.map { it.property.name }).containsExactlyInAnyOrderElementsOf(snapshotParams)
        assertThat(ExitParamsDeclarationCheck.REQUIRED_KEYS).containsAll(entries.map { it.key })
    }

    @Test
    fun `그림자 설정은 같은 표로 기동 때 막는다`() {
        assertThatThrownBy { ShadowExitProperties(trailingStopPct = 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("trading.shadow-exit.trailing-stop-pct")
        assertThatThrownBy { ShadowExitProperties(trailingArmPct = -1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("trading.shadow-exit.trailing-arm-pct")
        ShadowExitProperties(trailingStopPct = 1.5, trailingArmPct = 1000.0)
    }

    companion object {
        /** 청산 5개는 운영 실효값(2026-09-30 기동 로그). 비율·수수료는 코드 기본값 — 운영값은 구간 안인 것만 확인했다. */
        val PRODUCTION = TradingProperties(
            takeProfitPct = 5.0, maxLossPct = 5.0, trailingStopPct = 1.5, trailingArmPct = 0.0, maxHoldDays = 1,
        )
    }
}
