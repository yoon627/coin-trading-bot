package com.trading.research.portfolio

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    @Test
    fun `buy fill reduces cash and opens position`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, quantity = 0.1, fillPrice = 50_000.0, fee = 5.0, tag = "entry"))
        assertEquals(10_000.0 - 5_000.0 - 5.0, p.cash)
        assertTrue(p.hasPosition(asset))
        val pos = p.positions[asset]!!
        assertEquals(0.1, pos.quantity)
        assertEquals(50_000.0, pos.avgEntryPrice)
    }

    @Test
    fun `sell fill closes position and realizes pnl`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 0.1, 50_000.0, 5.0, "entry"))
        p.applyFill(Fill(asset, OrderSide.SELL, 0.1, 55_000.0, 5.5, "exit"))
        assertFalse(p.hasPosition(asset))
        // cash: 10000 - 5005 + 5500 - 5.5 = 10489.5
        assertEquals(10_489.5, p.cash, 1e-6)
    }

    @Test
    fun `markToMarket updates unrealized but not cash`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 0.1, 50_000.0, 5.0, "entry"))
        val cashBefore = p.cash
        p.markToMarket(mapOf(asset to 60_000.0))
        assertEquals(cashBefore, p.cash)
        assertEquals(6_000.0, p.positions[asset]!!.marketValue)
        assertEquals(cashBefore + 6_000.0, p.totalEquity)
    }

    @Test
    fun `sell rejects oversell that exceeds open quantity`() {
        val p = Portfolio(initialCash = 10_000.0)
        p.applyFill(Fill(asset, OrderSide.BUY, 0.1, 50_000.0, 5.0, "entry"))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            p.applyFill(Fill(asset, OrderSide.SELL, 0.2, 55_000.0, 5.5, "oversell"))
        }
    }
}
