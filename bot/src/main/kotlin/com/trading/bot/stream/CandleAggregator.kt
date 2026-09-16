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
 * M1 폴링을 상위 interval 봉으로 접는다. 폴링은 진행 중 분봉을 돌려주고 다음 라운드가 같은 분의 완결본을 다시 보내므로
 * 분 단위로 멱등이어야 한다 — period 마다 [Period] 를 두고, 가장 새 분봉은 [Period.provisional] 로만 얹어 두다가
 * 더 새 분봉이 오면 그때 [Period.base] 에 확정한다. 입력은 openTime 오름차순이어야 한다(내림차순이면 부분 봉이
 * 확정되고 완결본은 오래된 봉으로 버려진다). 상태가 없는 **과거** period(부팅 첫 라운드 꼬리의 어제 분봉, 무거래 마켓의
 * 긴 꼬리, 축출된 period 의 재유입)는 앞부분을 모르므로 부분봉을 만들어 [MarketDataStore] 의 완전한 seed 봉을 덮지 않고
 * 무시한다 — (market, interval) 별 최신 period 를 바닥으로 기억한다. 바닥은 부팅 seed 시점([startFrom], 모든 interval)·
 * [prime]·새 period 생성 시 올라가고 내려가지 않는다. 상태 갱신은 candle 폴링 코루틴 하나가 순차
 * 호출한다는 전제 위에 있다([MarketDataStore.addCandle] 과 같은 전제) — 그래서 map put 한 번으로 교체하는 immutable 값이다.
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

    /**
     * [base]: 확정된 분봉들의 병합(period 모양, 없으면 null). [provisional]: 가장 새 분봉의 최신 버전(M1 원본).
     * [lastFolded]: 마지막으로 base 에 접은 분의 openTime — 이보다 오래된 분봉은 재전송이라 무시한다.
     */
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

    /**
     * 이미 완전한 봉(부팅 seed 의 오늘 D1)을 그 period 의 확정 집계로 등록한다. 등록이 없으면 첫 M1 이 이 period 를 M1 하나로
     * **새로** 시작해 [MarketDataStore.addCandle] upsert 로 seed 를 대체한다. seed 는 [now] 까지의 봉이라 [now] 의 분보다
     * 오래된 M1(폴링 꼬리)은 이미 포함돼 있어 무시하고, [now] 의 분만 provisional 로 얹는다 — seed 와의 volume 겹침은
     * 부팅당 그 한 분 안이고 누적되진 않는다(다음 부팅의 seed 가 원본으로 덮는다).
     */
    /** [now] 이전 period 의 분봉은 앞부분을 모르므로 어느 interval 에서도 새 period 를 만들지 않는다 — 부팅 seed 가 호출한다. */
    fun startFrom(exchange: Exchange, market: String, now: Instant) {
        for (interval in AGGREGATE_INTERVALS) raiseFloor(prefix(exchange, market, interval), alignToPeriodStart(now, interval))
    }

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
