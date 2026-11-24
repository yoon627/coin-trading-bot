package com.trading.bot.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.trading.common.config.ExitParamRanges
import com.trading.common.config.TradingProperties

/** 사용자가 화면에서 바꾼 매매 값. null 인 항목은 서버 설정(env)을 쓴다. */
data class UserTradingParams(
    // 기본 snake_case 규칙은 kValue 를 "kvalue" 로 바꾼다 — 화면·env 이름(k-value)과 맞춘다.
    @get:JsonProperty("k_value") @param:JsonProperty("k_value") val kValue: Double? = null,
    val investRatio: Double? = null,
    val maxInvestAmount: Double? = null,
    val takeProfitPct: Double? = null,
    val maxLossPct: Double? = null,
    val trailingStopPct: Double? = null,
    val trailingArmPct: Double? = null,
    val maxHoldDays: Int? = null,
) {
    /** env 설정 위에 사용자가 정한 항목만 덮은 실효 설정. */
    fun applyTo(base: TradingProperties): TradingProperties = base.copy(
        kValue = kValue ?: base.kValue,
        investRatio = investRatio ?: base.investRatio,
        maxInvestAmount = maxInvestAmount ?: base.maxInvestAmount,
        takeProfitPct = takeProfitPct ?: base.takeProfitPct,
        maxLossPct = maxLossPct ?: base.maxLossPct,
        trailingStopPct = trailingStopPct ?: base.trailingStopPct,
        trailingArmPct = trailingArmPct ?: base.trailingArmPct,
        maxHoldDays = maxHoldDays ?: base.maxHoldDays,
    )

    /** 덮은 결과의 편집 항목 범위 위반(`키=값 (허용 …)`). env 전용 값이 구간 밖이어도 사용자 저장은 막지 않는다. */
    fun violations(base: TradingProperties): List<String> {
        val merged = applyTo(base)
        return ExitParamRanges.TRADING.filter { it.property in EDITABLE }.mapNotNull { it.violation(merged) }
    }

    companion object {
        val NONE = UserTradingParams()

        private val EDITABLE = setOf(
            TradingProperties::kValue,
            TradingProperties::investRatio,
            TradingProperties::maxInvestAmount,
            TradingProperties::takeProfitPct,
            TradingProperties::maxLossPct,
            TradingProperties::trailingStopPct,
            TradingProperties::trailingArmPct,
            TradingProperties::maxHoldDays,
        )
    }
}
