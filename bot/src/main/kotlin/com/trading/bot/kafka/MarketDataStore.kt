package com.trading.bot.kafka

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Component
class MarketDataStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val latestTickers = ConcurrentHashMap<String, NormalizedTicker>()
    private val candleBuffers = ConcurrentHashMap<String, ConcurrentLinkedDeque<NormalizedCandle>>()

    companion object {
        private const val MAX_CANDLE_BUFFER_SIZE = 200
    }

    fun updateTicker(ticker: NormalizedTicker) {
        val key = "${ticker.exchange}:${ticker.market}"
        latestTickers[key] = ticker
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

    fun hasData(exchange: Exchange, market: String): Boolean {
        return latestTickers.containsKey("$exchange:$market")
    }
}
