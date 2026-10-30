package com.trading.common.config

import java.math.BigDecimal
import kotlin.reflect.KProperty1

/**
 * 청산·주문·주기 파라미터의 허용 구간. 판정식이 뜻을 잃는 값(예: `maxLossPct=-5` 는 매수 직후 손절을 반복시킨다)과
 * 단위·자릿수 오기로만 나올 값(예: 초를 ms 로 적은 `intervalSeconds=10000`)을 거른다.
 *
 * 앱은 구간 밖 값을 알리기만 한다. 기동을 막으면 보유 포지션의 손절이 평가되지 않는다.
 * `deploy/vultr/preflight_exit_params.sh` 에 같은 표의 사본이 있어 둘을 함께 바꿔야 한다.
 */
object ExitParamRanges {

    /** 끝이 null 이면 그쪽으로 열려 있다. NaN·무한대는 어느 구간에도 들지 않는다. */
    data class Range(val min: Double, val minInclusive: Boolean, val max: Double?, val maxInclusive: Boolean) {
        operator fun contains(value: Double): Boolean =
            value.isFinite() &&
                (if (minInclusive) value >= min else value > min) &&
                (max == null || if (maxInclusive) value <= max else value < max)

        override fun toString(): String =
            (if (minInclusive) "[" else "(") + plain(min) + ", " +
                (if (max == null) "∞)" else plain(max) + if (maxInclusive) "]" else ")")
    }

    /** 키를 프로퍼티 참조에서 만들어 rename 이 표를 비껴가지 않게 한다. */
    class Entry<T : Any>(prefix: String, val property: KProperty1<T, Number>, val range: Range) {
        val key: String = "$prefix." + property.name.replace(UPPER, "-$1").lowercase()

        /** 구간 밖이면 `키=값 (허용 구간)`, 안이면 null. */
        fun violation(target: T): String? {
            val value = property.get(target)
            return if (value.toDouble() in range) null else "$key=$value (허용 $range)"
        }
    }

    private val UPPER = Regex("([A-Z])")

    // 0 이하면 수익 전환 즉시 청산되고, 100 이상이면 발동하지 않는다.
    private val TRAILING_STOP = Range(0.0, false, 100.0, false)

    // 음수는 0 과 같게 동작하지만 부호 오기 신호다. 상한 100 은 퍼센트 오기(3 을 300 으로)를 거른다.
    private val TRAILING_ARM = Range(0.0, true, 100.0, true)

    val TRADING: List<Entry<TradingProperties>> = listOf(
        // 0 이하면 수익 없이 곧바로 익절한다. 상한 100 은 퍼센트 오기(5 를 500 으로)를 거른다.
        Entry("trading", TradingProperties::takeProfitPct, Range(0.0, false, 100.0, true)),
        // 0 이하면 매수 직후 손절한다. 100 이면 최소 주문 검사(투자액 × (1 - 손절/100))가 0 이 돼 매수가 전부 막힌다.
        Entry("trading", TradingProperties::maxLossPct, Range(0.0, false, 100.0, false)),
        Entry("trading", TradingProperties::trailingStopPct, TRAILING_STOP),
        Entry("trading", TradingProperties::trailingArmPct, TRAILING_ARM),
        // 판정 때 1 로 올려 쓰므로 0 이하는 적은 뜻과 다르게 동작한다.
        Entry("trading", TradingProperties::maxHoldDays, Range(1.0, true, null, false)),
        // 0 이하면 매수가 없고, 1 초과면 잔액보다 큰 주문이다.
        Entry("trading", TradingProperties::investRatio, Range(0.0, false, 1.0, true)),
        // Upbit 최소 주문(5,000원) 미만이면 모든 매수가 건너뛰어진다. 그 상수는 bot 모듈에 있어 숫자로 적는다.
        Entry("trading", TradingProperties::maxInvestAmount, Range(5_000.0, true, null, false)),
        // 퍼센트가 아니라 비율이다. 0.1% 를 0.1 로 잘못 적는 경우를 거른다.
        Entry("trading", TradingProperties::roundTripFeeRate, Range(0.0, true, 0.01, false)),
        // 음수면 돌파 기준가가 시가 아래로 내려가 돌파 필터가 풀린다. 상한 2 는 0.5 를 5 로 적은 오기를 거른다.
        Entry("trading", TradingProperties::kValue, Range(0.0, true, 2.0, true)),
        // 손절·익절·트레일링 평가가 이 주기로만 돈다. 상한 60 은 ms 로 잘못 적은 값을 거르고 평가 지연을 1분으로 묶는다.
        Entry("trading", TradingProperties::intervalSeconds, Range(1.0, true, 60.0, true)),
    )

    val SHADOW: List<Entry<ShadowExitProperties>> = listOf(
        Entry("trading.shadow-exit", ShadowExitProperties::trailingStopPct, TRAILING_STOP),
        Entry("trading.shadow-exit", ShadowExitProperties::trailingArmPct, TRAILING_ARM),
    )

    fun violations(properties: TradingProperties): List<String> = TRADING.mapNotNull { it.violation(properties) }

    fun violations(properties: ShadowExitProperties): List<String> = SHADOW.mapNotNull { it.violation(properties) }

    private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
