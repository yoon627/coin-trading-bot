package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

@Component
class MarketDataStore {

    private val latestTickers = ConcurrentHashMap<String, NormalizedTicker>()
    private val candleBuffers = ConcurrentHashMap<String, ConcurrentSkipListMap<Instant, NormalizedCandle>>()

    companion object {
        private const val MAX_CANDLE_BUFFER_SIZE = 200
    }

    fun updateTicker(ticker: NormalizedTicker) {
        latestTickers["${ticker.exchange}:${ticker.market}"] = ticker
    }

    fun addCandle(candle: NormalizedCandle) {
        val key = "${candle.exchange}:${candle.market}:${candle.interval.label}"
        val buffer = candleBuffers.computeIfAbsent(key) { ConcurrentSkipListMap() }

        // 집계기가 같은 period 를 매분 다시 보내므로 openTime 으로 덮어쓴다.
        // 쓰는 쪽이 수집 코루틴 하나라 put 과 trim 사이에 경합이 없다.
        buffer[candle.openTime] = candle
        while (buffer.size > MAX_CANDLE_BUFFER_SIZE) {
            buffer.pollFirstEntry()
        }
    }

    fun getLatestTicker(exchange: Exchange, market: String): NormalizedTicker? {
        return latestTickers["$exchange:$market"]
    }

    fun getCandles(exchange: Exchange, market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val key = "$exchange:$market:${interval.label}"
        val buffer = candleBuffers[key] ?: return emptyList()
        return buffer.descendingMap().values.take(count)
    }

}
