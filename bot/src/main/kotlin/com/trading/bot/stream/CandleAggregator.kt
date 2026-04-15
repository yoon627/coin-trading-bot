package com.trading.bot.stream

import com.trading.bot.kafka.MarketDataStore
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoField
import java.util.concurrent.ConcurrentHashMap

@Component
class CandleAggregator(
    private val marketDataStore: MarketDataStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val activeCandles = ConcurrentHashMap<String, NormalizedCandle>()

    companion object {
        val AGGREGATE_INTERVALS = listOf(
            CandleInterval.M5, CandleInterval.M15,
            CandleInterval.H1, CandleInterval.H4,
            CandleInterval.D1, CandleInterval.W1, CandleInterval.MO1,
        )
    }

    fun onMinuteCandle(candle: NormalizedCandle) {
        for (interval in AGGREGATE_INTERVALS) {
            aggregateCandle(candle, interval)
        }
    }

    private fun aggregateCandle(minuteCandle: NormalizedCandle, interval: CandleInterval) {
        val periodStart = alignToPeriodStart(minuteCandle.openTime, interval)
        val periodEnd = periodStart.plusSeconds(interval.minutes * 60L)
        val key = "${minuteCandle.exchange}:${minuteCandle.market}:${interval.label}:$periodStart"

        val existing = activeCandles[key]
        if (existing == null) {
            val aggregated = NormalizedCandle(
                exchange = minuteCandle.exchange,
                market = minuteCandle.market,
                openPrice = minuteCandle.openPrice,
                highPrice = minuteCandle.highPrice,
                lowPrice = minuteCandle.lowPrice,
                closePrice = minuteCandle.closePrice,
                volume = minuteCandle.volume,
                quoteVolume = minuteCandle.quoteVolume,
                interval = interval,
                openTime = periodStart,
                closeTime = periodEnd,
            )
            activeCandles[key] = aggregated
            marketDataStore.addCandle(aggregated)
        } else {
            val updated = existing.copy(
                highPrice = maxOf(existing.highPrice, minuteCandle.highPrice),
                lowPrice = minOf(existing.lowPrice, minuteCandle.lowPrice),
                closePrice = minuteCandle.closePrice,
                volume = existing.volume + minuteCandle.volume,
                quoteVolume = existing.quoteVolume + minuteCandle.quoteVolume,
            )
            activeCandles[key] = updated
            marketDataStore.addCandle(updated)
        }

        cleanupOldPeriods(minuteCandle.exchange, minuteCandle.market, interval, periodStart)
    }

    private fun alignToPeriodStart(timestamp: Instant, interval: CandleInterval): Instant {
        val dt = timestamp.atZone(ZoneOffset.UTC)
        return when (interval) {
            CandleInterval.M5 -> dt.withMinute((dt.minute / 5) * 5).withSecond(0).withNano(0).toInstant()
            CandleInterval.M15 -> dt.withMinute((dt.minute / 15) * 15).withSecond(0).withNano(0).toInstant()
            CandleInterval.H1 -> dt.withMinute(0).withSecond(0).withNano(0).toInstant()
            CandleInterval.H4 -> dt.withHour((dt.hour / 4) * 4).withMinute(0).withSecond(0).withNano(0).toInstant()
            CandleInterval.D1 -> dt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
            CandleInterval.W1 -> dt.with(ChronoField.DAY_OF_WEEK, 1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
            CandleInterval.MO1 -> dt.withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
            else -> timestamp
        }
    }

    private fun cleanupOldPeriods(exchange: Exchange, market: String, interval: CandleInterval, currentPeriod: Instant) {
        val prefix = "$exchange:$market:${interval.label}:"
        val cutoff = currentPeriod.minusSeconds(interval.minutes * 60L * 3)
        activeCandles.keys.removeIf { key ->
            key.startsWith(prefix) && Instant.parse(key.removePrefix(prefix)) < cutoff
        }
    }
}
