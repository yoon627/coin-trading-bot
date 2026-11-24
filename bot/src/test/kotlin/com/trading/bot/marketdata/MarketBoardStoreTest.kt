package com.trading.bot.marketdata

import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 시세판 저장소의 변경 번호. 스트림은 마지막 받은 번호 이후를 당겨 읽으므로, 번호보다 늦게 보이는 값이 없어야 마켓을 잃지 않는다. */
class MarketBoardStoreTest {

    private val store = MarketBoardStore()

    private fun ticker(market: String, price: Double = 1.0) = NormalizedTicker(Exchange.UPBIT, market, price)

    @Test
    fun `each update raises the version and changedSince returns only what changed after it`() {
        store.update(ticker("BTC/KRW"))
        val after = store.version()
        store.update(ticker("ETH/KRW"))
        store.update(ticker("ETH/KRW", 2.0))

        val changes = store.changedSince(after)

        assertEquals(listOf("ETH/KRW" to 2.0), changes.tickers.map { it.market to it.price })
        assertEquals(store.version(), changes.upTo)
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `the snapshot carries the version read before copying`() {
        store.update(ticker("BTC/KRW"))
        val snapshot = store.snapshot()

        assertEquals(1, snapshot.upTo)
        assertEquals(listOf("BTC/KRW"), snapshot.tickers.map { it.market })
        assertTrue(store.changedSince(snapshot.upTo).tickers.isEmpty())
    }

    // 묘비는 번호를 지닌 채 남는다 — 느린 사용자도 마지막 번호가 그보다 앞이면 지운 것을 받는다.
    @Test
    fun `a market that leaves the catalog stays as a tombstone until it comes back`() {
        store.update(ticker("OLD/KRW"))
        val slow = store.version()
        store.retain(listOf("BTC/KRW"))
        store.update(ticker("BTC/KRW"))

        val changes = store.changedSince(slow)
        assertEquals(listOf("OLD/KRW"), changes.removed)
        assertNull(store.get("OLD/KRW"))
        assertTrue(store.snapshot().tickers.none { it.market == "OLD/KRW" })

        store.update(ticker("OLD/KRW", 3.0))
        assertEquals(3.0, store.get("OLD/KRW")?.price)
        assertTrue(store.changedSince(slow).removed.isEmpty())
    }

    // 쓰는 쪽이 둘이다(netty 의 update, 목록 갱신의 retain). 읽는 쪽이 delta 를 이어 붙인 결과는 언제나 지금 snapshot 과 같아야 한다.
    @Test
    fun `a reader that chains deltas under concurrent writes and removals ends equal to the snapshot`() {
        val start = CountDownLatch(1)
        val markets = (0 until 4).map { "M$it/KRW" }
        val writers = markets.mapIndexed { w, market ->
            thread {
                start.await()
                repeat(5_000) { i ->
                    store.update(ticker(market, i.toDouble()))
                    // 한 쓰기는 가끔 다른 마켓 하나를 목록에서 뺀다 — 그 마켓의 쓰기는 계속되어 묘비와 되살림이 겹친다.
                    if (w == 0 && i % 50 == 0) store.retain(markets - markets[1 + i / 50 % 3])
                }
            }
        }
        start.countDown()
        var last = 0L
        val seen = mutableMapOf<String, Double>()
        fun apply(changes: MarketBoardStore.Changes) {
            changes.tickers.forEach { seen[it.market] = it.price }
            changes.removed.forEach { seen.remove(it) }
            last = changes.upTo
        }
        while (writers.any { it.isAlive }) apply(store.changedSince(last))
        writers.forEach { it.join() }
        apply(store.changedSince(last))

        assertEquals(store.snapshot().tickers.associate { it.market to it.price }, seen)
    }
}
