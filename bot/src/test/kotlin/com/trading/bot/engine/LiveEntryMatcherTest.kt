package com.trading.bot.engine

import com.trading.bot.engine.LiveEntryMatcher.ArmEntry
import com.trading.bot.engine.LiveEntryMatcher.LiveBuy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LiveEntryMatcherTest {

    private fun arm(market: String, day: String, bar: String = "${day}T00:05:00", price: Double = 100.0) = ArmEntry(market, day, bar, price, "${day}T23:55:00")

    @Test
    fun `trading day is the UTC date even past 15 UTC`() {
        val late = LiveBuy(1, "KRW-BTC", "2026-07-20T23:59:30", 100.0)
        val early = LiveBuy(2, "KRW-BTC", "2026-07-21T00:00:09", 100.0)
        val r = LiveEntryMatcher.match(5, listOf(late, early), listOf(arm("KRW-BTC", "2026-07-20"), arm("KRW-BTC", "2026-07-21")))
        assertEquals(2, r.matched.size)
        assertEquals(1.0, r.recall)
    }

    @Test
    fun `second live buy on the same market-day is reported not failed, arm duplicates fail`() {
        val a = LiveBuy(1, "KRW-BTC", "2026-07-20T00:00:09", 100.0)
        val b = LiveBuy(2, "KRW-BTC", "2026-07-20T05:00:00", 101.0)
        val r = LiveEntryMatcher.match(5, listOf(b, a), listOf(arm("KRW-BTC", "2026-07-20")))
        assertEquals(listOf(1L), r.matched.map { it.live.id })
        assertEquals(listOf(2L), r.liveOnly.map { it.id })
        assertEquals(listOf(2L), r.duplicateSameDay.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { LiveEntryMatcher.match(5, emptyList(), listOf(arm("KRW-BTC", "2026-07-20"), arm("KRW-BTC", "2026-07-20"))) }
    }

    @Test
    fun `empty intersection gives zero recall precision f1 and arm-only list`() {
        val r = LiveEntryMatcher.match(240, listOf(LiveBuy(1, "KRW-ETH", "2026-07-20T01:00:00", 1.0)), listOf(arm("KRW-BTC", "2026-07-20")))
        assertEquals(0.0, r.recall); assertEquals(0.0, r.precision); assertEquals(0.0, r.f1)
        assertEquals(1, r.armOnly.size); assertEquals(1, r.liveOnly.size)
    }

    @Test
    fun `absolute median does not cancel symmetric errors but signed median does`() {
        val buys = listOf(LiveBuy(1, "A", "2026-07-20T01:00:00", 102.0), LiveBuy(2, "B", "2026-07-20T01:00:00", 98.0))
        val r = LiveEntryMatcher.match(5, buys, listOf(arm("A", "2026-07-20"), arm("B", "2026-07-20")))
        assertEquals(0.0, r.signedMedianFill, 1e-9)
        assertEquals(2.0, LiveEntryMatcher.medianAbsFill(r, setOf(1, 2)), 1e-9)
        assertEquals(2.0, LiveEntryMatcher.medianAbsFill(r, setOf(1)), 1e-9)
    }

    @Test
    fun `minutes are live time minus arm bar start`() {
        val r = LiveEntryMatcher.match(15, listOf(LiveBuy(1, "A", "2026-07-20T00:12:00", 100.0)), listOf(arm("A", "2026-07-20", bar = "2026-07-20T00:15:00")))
        assertEquals(-3L, r.matched.single().minutes)
    }

    @Test
    fun `verdict withholds on f1 tie, small common sample and axis disagreement`() {
        val buys = (1..25).map { LiveBuy(it.toLong(), "M$it", "2026-07-20T01:00:00", 100.0 + it % 3) }
        val full = buys.map { arm(it.market, "2026-07-20") }
        val tie = listOf(LiveEntryMatcher.match(240, buys, full), LiveEntryMatcher.match(5, buys, full))
        assertNull(LiveEntryMatcher.verdict(tie).closest)

        val sparse = listOf(LiveEntryMatcher.match(240, buys, full.take(10)), LiveEntryMatcher.match(5, buys, full))
        val v = LiveEntryMatcher.verdict(sparse)
        assertEquals(5, v.f1Best); assertEquals(10, v.commonN); assertNull(v.fillBest); assertNull(v.closest)

        val cheaper240 = buys.map { arm(it.market, "2026-07-20", price = it.price) } // 240 은 가격이 정확하지만 진입이 적고, 5 는 다 잡지만 가격이 틀린다
        val disagree = listOf(LiveEntryMatcher.match(240, buys, cheaper240.drop(3)), LiveEntryMatcher.match(5, buys, full))
        val d = LiveEntryMatcher.verdict(disagree)
        assertEquals(5, d.f1Best); assertEquals(240, d.fillBest); assertNull(d.closest)
    }

    @Test
    fun `verdict withholds when the price axis is tied within MIN_FILL_LEAD`() {
        val buys = (1..25).map { LiveBuy(it.toLong(), "M$it", "2026-07-20T01:00:00", 101.0) }
        val a = buys.map { arm(it.market, "2026-07-20", price = 100.0) }
        val b = buys.map { arm(it.market, "2026-07-20", price = 100.01) }.drop(8) // F1 은 뒤지지만 가격 오차는 0.01%p 차이 — 동률
        val v = LiveEntryMatcher.verdict(listOf(LiveEntryMatcher.match(240, buys, a), LiveEntryMatcher.match(5, buys, b)))
        assertEquals(240, v.f1Best); assertNull(v.fillBest); assertNull(v.closest)
    }

    @Test
    fun `verdict agrees when one resolution leads both axes`() {
        val buys = (1..25).map { LiveBuy(it.toLong(), "M$it", "2026-07-20T01:00:00", 101.0) }
        val exact = buys.map { arm(it.market, "2026-07-20", price = 101.0) }
        val off = buys.map { arm(it.market, "2026-07-20", price = 100.0) }.drop(5)
        val v = LiveEntryMatcher.verdict(listOf(LiveEntryMatcher.match(240, buys, off), LiveEntryMatcher.match(5, buys, exact)))
        assertEquals(5, v.closest)
    }
}
