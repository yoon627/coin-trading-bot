package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentSkipListMap

@Component
class MarketDataStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val latestTickers = ConcurrentHashMap<String, NormalizedTicker>()
    private val tickerHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<NormalizedTicker>>()
    private val candleBuffers = ConcurrentHashMap<String, ConcurrentSkipListMap<Instant, NormalizedCandle>>()
    private val orderBooks = ConcurrentHashMap<String, NormalizedOrderBook>()

    companion object {
        private const val MAX_CANDLE_BUFFER_SIZE = 200
        private const val MAX_TICKER_HISTORY_SIZE = 100
    }

    fun updateTicker(ticker: NormalizedTicker) {
        val key = "${ticker.exchange}:${ticker.market}"
        latestTickers[key] = ticker

        val history = tickerHistory.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        history.addFirst(ticker)
        while (history.size > MAX_TICKER_HISTORY_SIZE) {
            history.removeLast()
        }
    }

    fun updateOrderBook(orderBook: NormalizedOrderBook) {
        val key = "${orderBook.exchange}:${orderBook.market}"
        orderBooks[key] = orderBook
    }

    fun addCandle(candle: NormalizedCandle) {
        val key = "${candle.exchange}:${candle.market}:${candle.interval.label}"
        val buffer = candleBuffers.computeIfAbsent(key) { ConcurrentSkipListMap() }

        // openTime upsert: CandleAggregator 가 같은 period(openTime)를 매 분봉 갱신·재전송해도(반복 addCandle)
        // openTime 당 1개만 최신값으로 유지. 구 addFirst 는 dedup 없이 중복 누적 → 지표/차트/매수 D1 오염.
        // writer 는 MarketDataIngestionService 의 단일 ingestion 코루틴이 순차 호출 → put + trim race 없음.
        buffer[candle.openTime] = candle
        while (buffer.size > MAX_CANDLE_BUFFER_SIZE) {
            buffer.pollFirstEntry() // 가장 오래된 openTime 제거
        }
    }

    fun getLatestTicker(exchange: Exchange, market: String): NormalizedTicker? {
        return latestTickers["$exchange:$market"]
    }

    fun getAllTickers(): Map<String, NormalizedTicker> = latestTickers.toMap()

    fun getTickersByExchange(exchange: Exchange): List<NormalizedTicker> {
        return latestTickers.values.filter { it.exchange == exchange }
    }

    fun getCandles(exchange: Exchange, market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val key = "$exchange:$market:${interval.label}"
        val buffer = candleBuffers[key] ?: return emptyList()
        return buffer.descendingMap().values.take(count) // 최신 openTime 먼저
    }

    fun getRecentTickers(exchange: Exchange, market: String, count: Int): List<NormalizedTicker> {
        val key = "$exchange:$market"
        val history = tickerHistory[key] ?: return emptyList()
        return history.take(count)
    }

    fun getOrderBook(exchange: Exchange, market: String): NormalizedOrderBook? {
        return orderBooks["$exchange:$market"]
    }

    fun hasData(exchange: Exchange, market: String): Boolean {
        return latestTickers.containsKey("$exchange:$market")
    }
}
