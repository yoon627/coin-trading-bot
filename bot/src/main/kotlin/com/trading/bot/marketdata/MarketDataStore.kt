package com.trading.bot.marketdata

import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

@Component
class MarketDataStore {

    /** 시세 수집기가 구독할 마켓. 엔진과 수집기가 이미 이 저장소를 함께 쓰므로 여기에 둔다. */
    val subscription = MarketSubscription()

    private val latestTickers = ConcurrentHashMap<String, NormalizedTicker>()
    private val candleBuffers = ConcurrentHashMap<String, ConcurrentSkipListMap<Instant, NormalizedCandle>>()

    companion object {
        private const val MAX_CANDLE_BUFFER_SIZE = 200

        // 실측 최대(BTC 초당 40건) × 60초 × 13종목 — 종목이 늘어도 체결률은 BTC 가 이끈다. 구독자가 이만큼 밀리면 오래된 체결부터 버린다.
        internal const val TRADE_BUFFER_CAPACITY = 40 * 60 * 13

        // 재연결 직후 다시 오는 체결을 가릴 만큼만 기억한다(BTC 최대 속도로 약 50초).
        internal const val TRADE_DEDUP_WINDOW = 2_048
    }

    // 발행이 수집 코루틴을 막지 않게 넘치면 기다리지 않고 버린다.
    private val tradeFlow = MutableSharedFlow<NormalizedTrade>(
        replay = 0,
        extraBufferCapacity = TRADE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val trades: SharedFlow<NormalizedTrade> = tradeFlow.asSharedFlow()

    private class TradeMemory {
        val recent = LinkedHashSet<Long>()
        var received = 0L
        var duplicates = 0L
    }

    private val tradeMemories = ConcurrentHashMap<String, TradeMemory>()

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

    /** 처음 보는 체결이면 [trades] 로 내보내고 true. 최신가([getLatestTicker])는 바꾸지 않는다. */
    fun publishTrade(trade: NormalizedTrade): Boolean {
        val memory = tradeMemories.computeIfAbsent(trade.market) { TradeMemory() }
        synchronized(memory) {
            memory.received++
            if (!memory.recent.add(trade.sequentialId)) {
                memory.duplicates++
                return false
            }
            if (memory.recent.size > TRADE_DEDUP_WINDOW) memory.recent.remove(memory.recent.first())
        }
        tradeFlow.tryEmit(trade)
        return true
    }

    /** 마지막 호출 뒤 마켓별로 받은 체결 수와 그중 중복으로 버린 수. */
    fun drainTradeIntake(): Map<String, TradeIntake> = tradeMemories.entries.mapNotNull { (market, memory) ->
        synchronized(memory) {
            val intake = TradeIntake(memory.received, memory.duplicates)
            memory.received = 0
            memory.duplicates = 0
            if (intake.received > 0) market to intake else null
        }
    }.toMap()

    /** 구독에서 빠진 마켓의 시세·봉을 지운다. 남기면 다시 구독됐을 때 멈춘 값이 최신처럼 읽힌다. */
    fun forget(exchange: Exchange, market: String) {
        latestTickers.remove("$exchange:$market")
        candleBuffers.keys.removeIf { it.startsWith("$exchange:$market:") }
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

data class TradeIntake(val received: Long, val duplicates: Long)
