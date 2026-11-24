package com.trading.bot.api

import com.trading.bot.marketdata.MarketBoardStore
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 업비트 코드(`KRW-BTC`)로 시세판 저장소(`BTC/KRW`)를 읽는다. 시세판 전체가 멈췄으면 모른다고 답해 REST 로 묻게 한다. */
class MarketBoardPricesTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val clock = MutableClock(Instant.parse("2026-11-24T05:00:00Z"))
    private val store = MarketBoardStore(clock)
    private val prices = MarketBoardPrices(store)

    @Test
    fun `it reads the board by Upbit market code and knows nothing else`() {
        store.update(NormalizedTicker(Exchange.UPBIT, "BTC/KRW", 100.0))
        store.update(NormalizedTicker(Exchange.UPBIT, "OLD/KRW", 5.0))
        store.retain(listOf("BTC/KRW"))

        assertEquals(100.0, prices.price("KRW-BTC"))
        assertNull(prices.price("KRW-ETH"))
        assertNull(prices.price("KRW-OLD"), "목록에서 빠진 마켓")
    }

    // 업비트 WS 만 장애면 저장소가 장애 직전 값을 계속 든다. 그 값으로 평가하지 않고 REST 로 돌린다.
    @Test
    fun `a board that received nothing for over two minutes answers nothing`() {
        assertNull(prices.price("KRW-BTC"), "아직 아무것도 받지 않았다")
        store.update(NormalizedTicker(Exchange.UPBIT, "BTC/KRW", 100.0))

        clock.now = clock.now.plusSeconds(120)
        assertEquals(100.0, prices.price("KRW-BTC"))

        clock.now = clock.now.plusSeconds(1)
        assertNull(prices.price("KRW-BTC"))
    }
}
