package com.trading.bot.kis.marketdata

import com.trading.bot.kis.domain.KisCandle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId

class StockCandleAdapterTest {
    @Test
    fun `maps daily KisCandle to NormalizedCandle`() {
        val c = KisCandle(date = "20260615", open = 100, high = 110, low = 90, close = 105, volume = 1000)
        val n = StockCandleAdapter.toNormalized("005930", c, CandleInterval.D1)

        assertEquals(Exchange.KIS, n.exchange)
        assertEquals("005930", n.market)
        assertEquals(100.0, n.openPrice)
        assertEquals(110.0, n.highPrice)
        assertEquals(90.0, n.lowPrice)
        assertEquals(105.0, n.closePrice)
        assertEquals(1000.0, n.volume)
        assertEquals(CandleInterval.D1, n.interval)
        // openTime = 2026-06-15 00:00 KST
        assertEquals("2026-06-15T00:00", n.openTime.atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime().toString())
    }
}
