package com.trading.research.execution

import com.trading.research.domain.OrderSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CostModelTest {
    private fun near(a: Double, b: Double) = assertEquals(b, a, 1e-9)

    @Test
    fun `flat fee applied to both sides`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.001, slippageBps = 0.0)
        val buy = cm.applyFee(notional = 1000.0, side = OrderSide.BUY)
        near(buy, 1.0) // 1000 * 0.001
        val sell = cm.applyFee(notional = 1000.0, side = OrderSide.SELL)
        near(sell, 1.0)
    }

    @Test
    fun `buy slippage raises fill price`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 10.0) // 10 bps = 0.001
        val price = cm.applySlippage(quotedPrice = 100.0, side = OrderSide.BUY)
        near(price, 100.1) // +0.1%
    }

    @Test
    fun `sell slippage lowers fill price`() {
        val cm = FlatFeeSlippageModel(feeRate = 0.0, slippageBps = 25.0) // 25 bps = 0.0025
        val price = cm.applySlippage(quotedPrice = 200.0, side = OrderSide.SELL)
        near(price, 199.5)
    }

    @Test
    fun `negative feeRate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlatFeeSlippageModel(feeRate = -0.0001, slippageBps = 0.0)
        }
    }

    @Test
    fun `negative slippageBps is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlatFeeSlippageModel(feeRate = 0.0, slippageBps = -1.0)
        }
    }
}
