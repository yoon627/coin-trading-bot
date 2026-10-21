package com.trading.bot.kis.market

import com.trading.bot.kis.config.KisTradingProperties

/** KRX 매매 비용 요율(비율). 수수료는 매수·매도 양쪽에, 거래세는 매도에만 붙는다. */
data class KrxCostModel(val commissionRate: Double, val sellTaxRate: Double) {

    val buyFeeRate: Double get() = commissionRate

    val sellFeeRate: Double get() = commissionRate + sellTaxRate

    val roundTripRate: Double get() = 2 * commissionRate + sellTaxRate

    companion object {
        fun from(properties: KisTradingProperties): KrxCostModel =
            KrxCostModel(properties.commissionRate, properties.sellTaxRate)
    }
}
