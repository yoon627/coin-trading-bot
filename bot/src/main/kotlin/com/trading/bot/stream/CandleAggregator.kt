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
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * M1 폴링을 상위 interval 봉으로 접는다. 폴링은 진행 중 분봉을 주고 다음 라운드에 같은 분의 완결본을 다시 주므로,
 * 가장 새 분봉은 provisional 로만 두고 더 새 분봉이 와야 base 에 확정한다. 입력은 openTime 오름차순이어야 한다.
 * 상태가 없는 과거 period 는 앞부분을 몰라 store 의 완전한 봉을 덮을 수 있으므로 무시한다.
 * 호출은 분봉 수집 코루틴 하나가 순차로 한다는 전제다.
 */
@Component
class CandleAggregator(
    private val marketDataStore: MarketDataStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val periods = ConcurrentHashMap<String, Period>()
    private val newestPeriodStart = ConcurrentHashMap<String, Instant>()

    companion object {
        val AGGREGATE_INTERVALS = listOf(
            CandleInterval.M5, CandleInterval.M15,
            CandleInterval.H1, CandleInterval.H4,
            CandleInterval.D1, CandleInterval.W1, CandleInterval.MO1,
        )
    }

    // lastFolded 이하의 분봉은 이미 base 에 접힌 재전송이다.
    private data class Period(val base: NormalizedCandle?, val provisional: NormalizedCandle?, val lastFolded: Instant?) {
        fun published(interval: CandleInterval, periodStart: Instant, periodEnd: Instant): NormalizedCandle? = when {
            provisional == null -> base
            base == null -> provisional.copy(interval = interval, openTime = periodStart, closeTime = periodEnd)
            else -> base.copy(
                highPrice = maxOf(base.highPrice, provisional.highPrice),
                lowPrice = minOf(base.lowPrice, provisional.lowPrice),
                closePrice = provisional.closePrice,
                volume = base.volume + provisional.volume,
                quoteVolume = base.quoteVolume + provisional.quoteVolume,
            )
        }

        fun fold(interval: CandleInterval, periodStart: Instant, periodEnd: Instant): Period =
            if (provisional == null) this else Period(published(interval, periodStart, periodEnd), null, provisional.openTime)
    }

    fun onMinuteCandle(candle: NormalizedCandle) {
        for (interval in AGGREGATE_INTERVALS) {
            aggregateCandle(candle, interval)
        }
    }

    /** [now] 이전 period 의 분봉으로는 어느 interval 에서도 새 period 를 만들지 않는다. */
    fun startFrom(exchange: Exchange, market: String, now: Instant) {
        for (interval in AGGREGATE_INTERVALS) raiseFloor(prefix(exchange, market, interval), alignToPeriodStart(now, interval))
    }

    /**
     * 이미 완전한 봉을 그 period 의 확정 집계로 등록한다. [now] 의 분보다 오래된 M1 은 이 봉에 포함돼 있어 무시하고,
     * [now] 의 분만 얹는다. 그 한 분의 volume 이 겹칠 수 있지만 누적되지는 않는다.
     */
    fun prime(candle: NormalizedCandle, now: Instant) {
        val periodStart = alignToPeriodStart(candle.openTime, candle.interval)
        require(periodStart == candle.openTime) { "prime 봉의 openTime 이 ${candle.interval.label} period 시작과 다르다: ${candle.openTime}" }
        val currentMinute = now.truncatedTo(ChronoUnit.MINUTES)
        periods[key(candle.exchange, candle.market, candle.interval, periodStart)] =
            Period(base = candle, provisional = null, lastFolded = currentMinute.minusSeconds(60))
        raiseFloor(prefix(candle.exchange, candle.market, candle.interval), periodStart)
    }

    private fun aggregateCandle(minuteCandle: NormalizedCandle, interval: CandleInterval) {
        val periodStart = alignToPeriodStart(minuteCandle.openTime, interval)
        val periodEnd = periodStart.plusSeconds(interval.minutes * 60L)
        val prefix = prefix(minuteCandle.exchange, minuteCandle.market, interval)
        val key = key(minuteCandle.exchange, minuteCandle.market, interval, periodStart)
        val current = periods[key] ?: run {
            val newest = newestPeriodStart[prefix]
            if (newest != null && periodStart < newest) return
            raiseFloor(prefix, periodStart)
            Period(base = null, provisional = null, lastFolded = null)
        }
        val minute = minuteCandle.openTime

        if (current.lastFolded != null && minute <= current.lastFolded) return

        val provisionalMinute = current.provisional?.openTime
        val next = when {
            provisionalMinute == null || minute == provisionalMinute -> current.copy(provisional = minuteCandle)
            minute > provisionalMinute -> current.fold(interval, periodStart, periodEnd).copy(provisional = minuteCandle)
            else -> return
        }
        periods[key] = next
        next.published(interval, periodStart, periodEnd)?.let { marketDataStore.addCandle(it) }

        cleanupOldPeriods(minuteCandle.exchange, minuteCandle.market, interval, periodStart)
    }

    private fun raiseFloor(prefix: String, periodStart: Instant) {
        newestPeriodStart.merge(prefix, periodStart) { old, new -> maxOf(old, new) }
    }

    private fun prefix(exchange: Exchange, market: String, interval: CandleInterval) = "$exchange:$market:${interval.label}:"

    private fun key(exchange: Exchange, market: String, interval: CandleInterval, periodStart: Instant) =
        prefix(exchange, market, interval) + periodStart

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
        val prefix = prefix(exchange, market, interval)
        val cutoff = currentPeriod.minusSeconds(interval.minutes * 60L * 3)
        periods.keys.removeIf { key ->
            key.startsWith(prefix) && Instant.parse(key.removePrefix(prefix)) < cutoff
        }
    }
}
