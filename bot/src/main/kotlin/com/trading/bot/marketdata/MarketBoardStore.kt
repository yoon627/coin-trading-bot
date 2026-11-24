package com.trading.bot.marketdata

import com.trading.common.domain.NormalizedTicker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

/**
 * 시세판 마켓별 최신 ticker 와 변경 번호. 봇의 [MarketDataStore] 와 나눠, 시세판 목록이 바뀌어도 봇 시세를 지우지 않는다.
 * 구독자를 깨우지 않는다 — 스트림이 [version] 을 보고 [changedSince] 로 당겨 읽는다. 쓰기는 값을 넣은 뒤 버전을 올려,
 * 읽은 버전 이하의 변경은 그때 이미 보인다.
 */
@Component
class MarketBoardStore {
    // ticker 가 null 이면 목록에서 빠진 마켓(묘비). 다시 들어오면 덮는다.
    private class Entry(val ticker: NormalizedTicker?, val seq: Long)

    class Snapshot(val upTo: Long, val tickers: List<NormalizedTicker>)

    class Changes(val upTo: Long, val tickers: List<NormalizedTicker>, val removed: List<String>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val published = AtomicLong()
    private val writeLock = Any()

    // netty 스레드에서 불린다 — 넣고 번호만 올린다.
    fun update(ticker: NormalizedTicker) = synchronized(writeLock) {
        val seq = published.get() + 1
        entries[ticker.market] = Entry(ticker, seq)
        published.set(seq)
    }

    fun retain(markets: Collection<String>) = synchronized(writeLock) {
        val keep = markets.toSet()
        for ((market, entry) in entries) {
            if (market in keep || entry.ticker == null) continue
            val seq = published.get() + 1
            entries[market] = Entry(null, seq)
            published.set(seq)
        }
    }

    fun version(): Long = published.get()

    fun get(market: String): NormalizedTicker? = entries[market]?.ticker

    // 버전을 먼저 읽는다 — 그 뒤 들어온 변경이 섞이면 다음 changedSince 에 한 번 더 갈 뿐 빠지지 않는다.
    fun snapshot(): Snapshot {
        val upTo = version()
        return Snapshot(upTo, entries.values.mapNotNull { it.ticker })
    }

    fun changedSince(last: Long): Changes {
        val upTo = version()
        val tickers = mutableListOf<NormalizedTicker>()
        val removed = mutableListOf<String>()
        for ((market, entry) in entries) {
            if (entry.seq <= last) continue
            if (entry.ticker == null) removed += market else tickers += entry.ticker
        }
        return Changes(upTo, tickers, removed)
    }
}
