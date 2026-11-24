package com.trading.bot.persistence.entity

import com.trading.bot.domain.UserTradingParams
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("user_trading_params")
data class UserTradingParamsEntity(
    @Id
    val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val kValue: Double? = null,
    val investRatio: Double? = null,
    val maxInvestAmount: Double? = null,
    val takeProfitPct: Double? = null,
    val maxLossPct: Double? = null,
    val trailingStopPct: Double? = null,
    val trailingArmPct: Double? = null,
    val maxHoldDays: Int? = null,
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain() = UserTradingParams(kValue, investRatio, maxInvestAmount, takeProfitPct, maxLossPct, trailingStopPct, trailingArmPct, maxHoldDays)

    fun with(params: UserTradingParams, now: Instant) = copy(
        kValue = params.kValue,
        investRatio = params.investRatio,
        maxInvestAmount = params.maxInvestAmount,
        takeProfitPct = params.takeProfitPct,
        maxLossPct = params.maxLossPct,
        trailingStopPct = params.trailingStopPct,
        trailingArmPct = params.trailingArmPct,
        maxHoldDays = params.maxHoldDays,
        updatedAt = now,
    )
}
