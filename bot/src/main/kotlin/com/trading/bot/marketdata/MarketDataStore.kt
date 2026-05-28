package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Component
class MarketDataStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val latestTickers = ConcurrentHashMap<String, NormalizedTicker>()
    private val tickerHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<NormalizedTicker>>()
    private val candleBuffers = ConcurrentHashMap<String, ConcurrentLinkedDeque<NormalizedCandle>>()
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
        val buffer = candleBuffers.computeIfAbsent(key) { ConcurrentLinkedDeque() }

        buffer.addFirst(candle)
        while (buffer.size > MAX_CANDLE_BUFFER_SIZE) {
            buffer.removeLast()
        }
    }

    fun getLatestTicker(exchange: Exchange, market: String): NormalizedTicker? {
        return latestTickers["$exchange:$market"]
    }

    fun getLatestPrice(exchange: Exchange, market: String): Double? {
        return latestTickers["$exchange:$market"]?.price
    }

    fun getAllTickers(): Map<String, NormalizedTicker> = latestTickers.toMap()

    fun getTickersByExchange(exchange: Exchange): List<NormalizedTicker> {
        return latestTickers.values.filter { it.exchange == exchange }
    }

    fun getCandles(exchange: Exchange, market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val key = "$exchange:$market:${interval.label}"
        val buffer = candleBuffers[key] ?: return emptyList()
        return buffer.take(count)
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
