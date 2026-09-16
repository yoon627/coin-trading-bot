package com.trading.bot.stream

import com.trading.bot.marketdata.MarketDataStore
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

    /**
     * 이미 완전한 봉(부팅 seed 의 오늘 D1)을 그 period 의 진행 중 집계로 등록한다. 등록이 없으면 첫 M1 이 이 period 를 M1 하나로
     * **새로** 시작해 [MarketDataStore.addCandle] upsert 로 seed 를 대체한다. 등록 뒤 M1 은 병합 분기로 이어진다 — seed(부팅 시각까지)와
     * 첫 M1(그 직후 분)의 volume 이 부팅당 최대 1분 겹칠 수 있고 누적되진 않는다(다음 부팅의 seed 가 원본으로 덮는다).
     */
    fun prime(candle: NormalizedCandle) {
        val periodStart = alignToPeriodStart(candle.openTime, candle.interval)
        require(periodStart == candle.openTime) { "prime 봉의 openTime 이 ${candle.interval.label} period 시작과 다르다: ${candle.openTime}" }
        activeCandles["${candle.exchange}:${candle.market}:${candle.interval.label}:$periodStart"] = candle
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
