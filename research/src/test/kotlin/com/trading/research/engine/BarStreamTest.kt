package com.trading.research.engine

import com.trading.common.domain.Exchange
import com.trading.research.domain.Asset
import com.trading.research.domain.Bar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BarStreamTest {
    private fun bar(t: Instant, close: Double): Bar =
        Bar(t, t.plusSeconds(86400), close, close, close, close, 1.0, close)

    @Test
    fun `events emitted in chronological order, ties broken by asset name`() {
        val btc = Asset(Exchange.UPBIT, "BTC/KRW")
        val eth = Asset(Exchange.UPBIT, "ETH/KRW")
        val aapl = Asset(Exchange.KIS, "AAPL")

        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = Instant.parse("2024-01-02T00:00:00Z")

        val history = mapOf(
            aapl to listOf(bar(t0, 190.0), bar(t1, 191.0)),
            btc to listOf(bar(t0, 42000.0), bar(t1, 43000.0)),
            eth to listOf(bar(t0, 2500.0), bar(t1, 2600.0)),
        )

        val stream = BarStream(history).iterator()
        val ordered = mutableListOf<Pair<Asset, Instant>>()
        stream.forEach { ordered += it.asset to it.bar.closeTime }

        val day1 = ordered.filter { it.second == t0.plusSeconds(86400) }.map { it.first.toString() }
        assertEquals(listOf("KIS:AAPL", "UPBIT:BTC/KRW", "UPBIT:ETH/KRW"), day1)
        assertEquals(6, ordered.size)
    }
}
