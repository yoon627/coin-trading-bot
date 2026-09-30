package com.trading.common.config

import java.math.BigDecimal
import kotlin.reflect.KProperty1

/**
 * 청산·주문 파라미터의 **의미상** 허용 구간 (#230). 운영값을 강제하지 않고 판정식이 뜻을 잃는 값만 거른다 —
 * 부호 오기 `maxLossPct=-5` 는 손절 `pnl <= 5` 가 돼 매수 직후 손절이 반복된다.
 *
 * 이 표를 셋이 쓴다.
 * - 앱(`ExitParamsDeclarationCheck`)은 구간 밖 전역값을 **알리기만** 한다. 기동을 막으면 보유 포지션의
 *   손절·트레일링이 평가되지 않는 공백이 생기고, 그 손해가 잘못된 값보다 크다(#179).
 * - [ShadowExitProperties] 는 관측 전용이라 기동 때 막는다.
 * - 배포 preflight(`deploy/vultr/preflight_exit_params.sh`)가 사본으로 업로드 전에 막는다 —
 *   `ExitParamsPreflightScriptTest` 가 두 표를 대조한다.
 *
 * 한 필드의 구간만 둔다. 필드 사이 관계(익절이 트레일링보다 커야 트레일링이 산다 등)는 `TradingEngine` 이 WARN 으로 본다.
 * 익절·arm 의 상한 100 은 판정식상 필요 조건이 아니다 — 완화 여부는 #288(preflight 사본·대조 테스트와 함께 바꾼다).
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

    /** 표의 한 행. 키는 바인딩 prefix 와 프로퍼티 이름에서 파생한다 — 문자열로 적으면 rename 이 표를 조용히 비껴간다. */
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

    val TRADING: List<Entry<TradingProperties>> = listOf(
        // 0 이하면 수익 없이(또는 손실에) 곧바로 익절한다.
        Entry("trading", TradingProperties::takeProfitPct, Range(0.0, false, 100.0, true)),
        // 0 이하면 매수 직후 손절한다. 100 이면 최소 주문 검사(투자액 × (1 - 손절/100))가 0 이 돼 매수가 전부 막힌다.
        Entry("trading", TradingProperties::maxLossPct, Range(0.0, false, 100.0, false)),
        Entry("trading", TradingProperties::trailingStopPct, TRAILING_STOP),
        // 음수는 0 과 같게 동작하지만 부호 오기 신호다.
        Entry("trading", TradingProperties::trailingArmPct, Range(0.0, true, 100.0, true)),
        // 판정 때 1 로 올려 쓰므로(ExitGates.effectiveMaxHoldDays) 0 이하는 적은 뜻과 다르게 돈다.
        Entry("trading", TradingProperties::maxHoldDays, Range(1.0, true, null, false)),
        // 0 이하면 매수가 없고, 1 초과면 잔액보다 큰 주문이다.
        Entry("trading", TradingProperties::investRatio, Range(0.0, false, 1.0, true)),
        // 비율이다(0.001 = 0.1%). 형제 키가 퍼센트라 0.1%를 0.1 로 적는 오기(=10%)를 거른다.
        Entry("trading", TradingProperties::roundTripFeeRate, Range(0.0, true, 0.01, false)),
    )

    val SHADOW: List<Entry<ShadowExitProperties>> = listOf(
        Entry("trading.shadow-exit", ShadowExitProperties::trailingStopPct, TRAILING_STOP),
        // 라이브 arm 의 상한 100 은 판정식 근거가 없어(#288) 그림자에는 두지 않는다 — 두면 새 기동 차단 조건이 된다.
        Entry("trading.shadow-exit", ShadowExitProperties::trailingArmPct, Range(0.0, true, null, false)),
    )

    fun violations(properties: TradingProperties): List<String> = TRADING.mapNotNull { it.violation(properties) }

    private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
