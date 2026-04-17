package com.trading.research.execution

import com.trading.research.domain.OrderSide

interface CostModel {
    /** Adjusts quoted price to fill price incorporating slippage. */
    fun applySlippage(quotedPrice: Double, side: OrderSide): Double

    /** Returns fee amount in quote currency given notional. */
    fun applyFee(notional: Double, side: OrderSide): Double
}

/**
 * v1: 거래소별 단일 수수료율 + 고정 bps 슬리피지.
 * 시장충격(market impact) 및 부분체결 모델은 v2에서 별도 구현.
 */
class FlatFeeSlippageModel(
    private val feeRate: Double,      // e.g., 0.0005 = 0.05%
    private val slippageBps: Double,  // basis points; 10 bps = 0.1%
) : CostModel {
    init {
        require(feeRate >= 0.0) { "feeRate must be >= 0" }
        require(slippageBps >= 0.0) { "slippageBps must be >= 0" }
    }

    override fun applySlippage(quotedPrice: Double, side: OrderSide): Double {
        val adjustment = slippageBps / BPS_TO_FRACTION
        return when (side) {
            OrderSide.BUY -> quotedPrice * (1.0 + adjustment)
            OrderSide.SELL -> quotedPrice * (1.0 - adjustment)
        }
    }

    override fun applyFee(notional: Double, side: OrderSide): Double = notional * feeRate

    companion object {
        /** 1 basis point = 1/10_000; divisor to convert bps to fractional rate. */
        private const val BPS_TO_FRACTION = 10_000.0
    }
}
