package com.trading.bot.config

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.common.config.ExitParamRanges
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaGetter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.ConfigurationProperties

/** 청산·주문·주기 파라미터의 허용 구간. 운영값이 아니라 판정식이 뜻을 잃는 경계와 단위·자릿수 오기를 거르는 경계다. */
class ExitParamRangesTest {

    private val entries = (ExitParamRanges.TRADING + ExitParamRanges.SHADOW).associateBy { it.key }

    @ParameterizedTest(name = "{0}={1} → 구간 안 {2}")
    @CsvSource(
        // 부호 오기: 손절 -5 는 `pnl <= 5` 가 돼 매수 직후 손절한다.
        "trading.max-loss-pct, -5, false",
        "trading.max-loss-pct, 0, false",
        "trading.max-loss-pct, 0.01, true",
        "trading.max-loss-pct, 99.99, true",
        // 손절 100 이면 최소 주문 검사(1 - SL/100)가 0 이 돼 매수가 전부 막힌다.
        "trading.max-loss-pct, 100, false",
        "trading.take-profit-pct, 0, false",
        "trading.take-profit-pct, 0.01, true",
        "trading.take-profit-pct, 100, true",
        // 상한 100 은 판정식이 아니라 퍼센트 오기(5 를 500 으로)를 거르는 경계다.
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
        // 최소 주문(5,000원) 미만이면 모든 매수가 건너뛰어진다.
        "trading.max-invest-amount, 0, false",
        "trading.max-invest-amount, 4999.99, false",
        "trading.max-invest-amount, 5000, true",
        "trading.max-invest-amount, 100000, true",
        "trading.round-trip-fee-rate, -0.001, false",
        "trading.round-trip-fee-rate, 0, true",
        "trading.round-trip-fee-rate, 0.009, true",
        "trading.round-trip-fee-rate, 0.01, false",
        // 0.1% 를 퍼센트 단위로 적은 오기. 비율로는 10% 다.
        "trading.round-trip-fee-rate, 0.1, false",
        // 음수면 돌파 기준가가 시가 아래로 내려가 돌파 필터가 풀린다. 상한 2 는 0.5 를 5 로 적은 오기를 거른다.
        "trading.k-value, -0.001, false",
        "trading.k-value, 0, true",
        "trading.k-value, 2, true",
        "trading.k-value, 2.001, false",
        // 상한 60 초는 손절 평가 지연의 상한이다. 10000 은 ms 로 잘못 적은 값이다.
        "trading.interval-seconds, 0, false",
        "trading.interval-seconds, 1, true",
        "trading.interval-seconds, 60, true",
        "trading.interval-seconds, 61, false",
        "trading.interval-seconds, 10000, false",
        // 막힌 매도 알림은 이 값 × 주기 뒤에 나간다. 상한 100 은 20 을 200·2000 으로 적은 자릿수 오기를 거른다.
        "trading.reconcile-halt-threshold, 0, false",
        "trading.reconcile-halt-threshold, 1, true",
        "trading.reconcile-halt-threshold, 100, true",
        "trading.reconcile-halt-threshold, 101, false",
        "trading.reconcile-halt-threshold, 2000, false",
        "trading.shadow-exit.trailing-stop-pct, 0, false",
        "trading.shadow-exit.trailing-stop-pct, 1.5, true",
        "trading.shadow-exit.trailing-stop-pct, 100, false",
        "trading.shadow-exit.trailing-arm-pct, -1, false",
        "trading.shadow-exit.trailing-arm-pct, 100, true",
        "trading.shadow-exit.trailing-arm-pct, 100.01, false",
    )
    fun `구간은 판정식이 뜻을 잃거나 오기로만 나올 값에서 갈린다`(key: String, value: Double, inside: Boolean) {
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
        // 101 로 TradingProperties 를 만들 수 있어야 한다 — 기동은 막지 않고 알리기만 한다.
        val properties = TradingProperties(maxLossPct = -5.0, maxHoldDays = 0, intervalSeconds = 61, reconcileHaltThreshold = 101)
        assertThat(ExitParamRanges.violations(properties)).containsExactly(
            "trading.max-loss-pct=-5.0 (허용 (0, 100))",
            "trading.max-hold-days=0 (허용 [1, ∞))",
            "trading.interval-seconds=61 (허용 [1, 60])",
            "trading.reconcile-halt-threshold=101 (허용 [1, 100])",
        )
    }

    @Test
    fun `1회 매수 상한의 하한은 Upbit 최소 주문과 같다`() {
        // 표는 common 모듈이라 bot 의 상수를 쓰지 못해 숫자로 적었다.
        assertThat(entries.getValue("trading.max-invest-amount").range.min).isEqualTo(UpbitMarketRules.MIN_ORDER_KRW)
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
            "trading.max-invest-amount",
            "trading.round-trip-fee-rate",
            "trading.k-value",
            "trading.interval-seconds",
            "trading.reconcile-halt-threshold",
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
        // 새 청산 파라미터를 스냅샷에 더하고 구간이나 선언 검사를 빠뜨리면 여기서 깨진다.
        val snapshotParams = ExitParamsSnapshot::class.primaryConstructor!!.parameters.map { it.name }
        val entries = ExitParamRanges.TRADING.filter { it.property.name in snapshotParams }
        assertThat(entries.map { it.property.name }).containsExactlyInAnyOrderElementsOf(snapshotParams)
        assertThat(ExitParamsDeclarationCheck.REQUIRED_KEYS).containsAll(entries.map { it.key })
    }

    @Test
    fun `그림자 설정은 구간 밖이어도 만들어지고 같은 표로 위반을 돌려준다`() {
        // 관측 전용이라 기동은 막지 않고 관측만 끄고 알린다.
        assertThat(ExitParamRanges.violations(ShadowExitProperties(trailingStopPct = 0.0)).single())
            .contains("trading.shadow-exit.trailing-stop-pct")
        assertThat(ExitParamRanges.violations(ShadowExitProperties(trailingArmPct = -1.0)).single())
            .contains("trading.shadow-exit.trailing-arm-pct")
        assertThat(ExitParamRanges.violations(ShadowExitProperties(trailingStopPct = Double.NaN))).hasSize(1)
        assertThat(ExitParamRanges.violations(ShadowExitProperties(trailingStopPct = 1.5, trailingArmPct = 100.0))).isEmpty()
        assertThat(ExitParamRanges.violations(ShadowExitProperties(trailingArmPct = 1000.0)).single())
            .contains("trading.shadow-exit.trailing-arm-pct")
    }

    companion object {
        /** 청산 5개는 운영 실효값이고 비율·수수료는 코드 기본값이다. */
        val PRODUCTION = TradingProperties(
            takeProfitPct = 5.0, maxLossPct = 5.0, trailingStopPct = 1.5, trailingArmPct = 0.0, maxHoldDays = 1,
        )
    }
}
