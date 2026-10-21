package com.trading.bot.kis.config

import com.trading.common.config.ExitParamRanges
import kotlin.reflect.KProperty1

/**
 * [KisTradingProperties] 의 청산·투자비율·비용 요율 허용 구간. 청산·투자비율은 [ExitParamRanges.TRADING] 의 같은 이름 항목을
 * 그대로 쓰고, 비용 요율은 Upbit 에 같은 항목이 없어 따로 둔다.
 * `deploy/vultr/preflight_exit_params.sh` 에 사본이 있어 함께 바꿔야 한다.
 */
object KisExitParamRanges {

    private const val PREFIX = "kis.trading"

    // 퍼센트가 아니라 비율이다. 0.2% 를 0.2 로 잘못 적는 경우를 거른다.
    private val COST_RATE = ExitParamRanges.Range(0.0, true, 0.01, false)

    val KIS: List<ExitParamRanges.Entry<KisTradingProperties>> = listOf(
        sameAsTrading(KisTradingProperties::takeProfitPct),
        sameAsTrading(KisTradingProperties::maxLossPct),
        sameAsTrading(KisTradingProperties::trailingStopPct),
        sameAsTrading(KisTradingProperties::trailingArmPct),
        sameAsTrading(KisTradingProperties::maxHoldDays),
        sameAsTrading(KisTradingProperties::investRatio),
        ExitParamRanges.Entry(PREFIX, KisTradingProperties::commissionRate, COST_RATE),
        ExitParamRanges.Entry(PREFIX, KisTradingProperties::sellTaxRate, COST_RATE),
    )

    fun violations(properties: KisTradingProperties): List<String> = KIS.mapNotNull { it.violation(properties) }

    /** 구간 위반에 금액·주기·요청 간격의 하한 위반을 더한 프로파일 전체 검사. 키 이름과 값만 담는다. */
    fun problems(properties: KisTradingProperties): List<String> = buildList {
        atLeast("$PREFIX.max-invest-amount", properties.maxInvestAmount, 1)
        atLeast("$PREFIX.max-order-amount", properties.maxOrderAmount, 1)
        atLeast("$PREFIX.interval-seconds", properties.intervalSeconds, 1)
        atLeast("$PREFIX.paper-min-request-interval-ms", properties.paperMinRequestIntervalMs, 1)
        atLeast("$PREFIX.live-min-request-interval-ms", properties.liveMinRequestIntervalMs, 1)
        addAll(violations(properties))
    }

    private fun sameAsTrading(property: KProperty1<KisTradingProperties, Number>): ExitParamRanges.Entry<KisTradingProperties> {
        val range = ExitParamRanges.TRADING.singleOrNull { it.property.name == property.name }?.range
            ?: error("ExitParamRanges.TRADING 에 ${property.name} 항목이 없다")
        return ExitParamRanges.Entry(PREFIX, property, range)
    }
}

internal fun MutableList<String>.atLeast(key: String, value: Long, min: Long) {
    if (value < min) add("$key=$value (허용 [$min, ∞))")
}

internal fun MutableList<String>.within(key: String, value: Long, min: Long, max: Long) {
    if (value < min || value > max) add("$key=$value (허용 [$min, $max])")
}
