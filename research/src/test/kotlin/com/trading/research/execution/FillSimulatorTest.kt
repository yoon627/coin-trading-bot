package com.trading.research.execution

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import com.trading.research.portfolio.Fill
import com.trading.research.portfolio.Portfolio
import com.trading.research.sizing.SizingCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FillSimulatorTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")
    private val cost = FlatFeeSlippageModel(feeRate = 0.001, slippageBps = 10.0)

    @Test
    fun `buy order at next bar open applies slippage and fee`() {
        val p = Portfolio(initialCash = 10_000.0)
        val sim = FillSimulator(cost, SizingCalculator)
        val order = OrderRequest(asset, OrderSide.BUY, SizingRule.FixedFraction(0.1))

        val fills = sim.fill(
            orders = listOf(order),
            openPrices = mapOf(asset to 100.0),
            equityBefore = p.totalEquity,
            assetDailyVol = mapOf(asset to 0.02),
            barIndex = 0L,
        )

        assertEquals(1, fills.size)
        val f = fills[0]
        // notional target = 10000 * 0.1 = 1000
        // slippage BUY: 100 * 1.001 = 100.1
        // quantity = 1000 / 100.1 ≈ 9.99001
        assertEquals(100.1, f.fillPrice, 1e-9)
        assertEquals(1000.0 / 100.1, f.quantity, 1e-6)
        // fee = notional * feeRate = (qty * fillPrice) * 0.001
        assertEquals(f.quantity * f.fillPrice * 0.001, f.fee, 1e-6)
    }

    @Test
    fun `sell CloseAll closes the full existing position`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 10.0, 100.0, 1.0, "entry"))
        val sim = FillSimulator(cost, SizingCalculator)

        val fills = sim.fillExit(
            orders = listOf(OrderRequest(asset, OrderSide.SELL, SizingRule.CloseAll, tag = "exit")),
            openPrices = mapOf(asset to 110.0),
            portfolio = p,
            barIndex = 1L,
        )

        assertEquals(1, fills.size)
        assertEquals(10.0, fills[0].quantity, 1e-9)
        // slippage SELL: 110 * (1 - 0.001) = 109.89
        assertEquals(109.89, fills[0].fillPrice, 1e-9)
    }
}
