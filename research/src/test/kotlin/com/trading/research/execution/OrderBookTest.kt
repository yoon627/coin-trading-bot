package com.trading.research.execution

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.OrderRequest
import com.trading.research.domain.OrderSide
import com.trading.research.domain.SizingRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderBookTest {
    private val asset = Asset(Exchange.UPBIT, "BTC/KRW")

    @Test
    fun `submitted orders live until drained`() {
        val ob = OrderBook()
        ob.submit(OrderRequest(asset, OrderSide.BUY, SizingRule.FixedFraction(0.1)))
        ob.submit(OrderRequest(asset, OrderSide.SELL, SizingRule.CloseAll))
        val drained = ob.drain()
        assertEquals(2, drained.size)
        assertTrue(ob.drain().isEmpty())
    }
}
