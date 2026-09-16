package com.trading.bot.stream

import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Aggregation is observed through the MarketDataStore.addCandle sink: every M1
 * ingest emits one aggregated candle per interval, captured here without
 * touching the aggregator's private state.
 */
class CandleAggregatorTest {

    private lateinit var store: MarketDataStore
    private lateinit var aggregator: CandleAggregator
    private val captured = mutableListOf<NormalizedCandle>()

    @BeforeEach
    fun setup() {
        captured.clear()
        store = mockk(relaxed = true)
        every { store.addCandle(capture(captured)) } just Runs
        aggregator = CandleAggregator(store)
    }

    @Test
    fun `merges same-day M1 candles into one D1 candle`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 100.0, high = 110.0, low = 95.0, close = 105.0, volume = 1.0, quoteVolume = 10.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:01:00Z", open = 105.0, high = 120.0, low = 100.0, close = 115.0, volume = 2.0, quoteVolume = 20.0))
        aggregator.onMinuteCandle(m1("2024-01-01T12:00:00Z", open = 115.0, high = 118.0, low = 90.0, close = 112.0, volume = 3.0, quoteVolume = 30.0))

        val d1 = capturedFor(CandleInterval.D1)
        assertEquals(inst("2024-01-01T00:00:00Z"), d1.map { it.openTime }.distinct().single())

        val merged = d1.last()
        assertEquals(100.0, merged.openPrice) // 최초 M1 open 보존
        assertEquals(120.0, merged.highPrice) // max
        assertEquals(90.0, merged.lowPrice) // min
        assertEquals(112.0, merged.closePrice) // last
        assertEquals(6.0, merged.volume) // 합
        assertEquals(60.0, merged.quoteVolume) // 합
    }

    // #209 재현: 부팅 seed 의 오늘 D1 을 prime 해 두면 첫 M1 이 그 봉을 이어받아야 한다.
    // prime 이 없으면 M1 하나로 D1 을 새로 만들어 upsert 하므로 seed 의 시가·고저가 사라지고 재시작 이후 구간만 남는다.
    @Test
    fun `first M1 of a period merges into the primed seed candle instead of replacing it`() {
        val day = inst("2024-01-01T00:00:00Z")
        val seeded = NormalizedCandle(
            exchange = Exchange.UPBIT, market = MARKET, openPrice = 100.0, highPrice = 130.0, lowPrice = 80.0, closePrice = 105.0,
            volume = 50.0, quoteVolume = 500.0, interval = CandleInterval.D1, openTime = day, closeTime = day.plusSeconds(86_400),
        )
        aggregator.prime(seeded, now = inst("2024-01-01T13:00:20Z"))

        aggregator.onMinuteCandle(m1("2024-01-01T13:00:00Z", open = 106.0, high = 108.0, low = 104.0, close = 107.0, volume = 2.0, quoteVolume = 20.0))
        val first = capturedFor(CandleInterval.D1).last()
        assertEquals(100.0, first.openPrice, "seed 의 시가 보존")
        assertEquals(130.0, first.highPrice, "seed 의 고가 보존")
        assertEquals(80.0, first.lowPrice, "seed 의 저가 보존")
        assertEquals(107.0, first.closePrice, "종가는 최신 M1")
        assertEquals(52.0, first.volume)
        assertEquals(520.0, first.quoteVolume)

        aggregator.onMinuteCandle(m1("2024-01-01T13:01:00Z", open = 107.0, high = 135.0, low = 106.0, close = 134.0, volume = 3.0, quoteVolume = 30.0))
        val second = capturedFor(CandleInterval.D1).last()
        assertEquals(135.0, second.highPrice, "병합값 위에서 이어서 집계")
        assertEquals(80.0, second.lowPrice)
        assertEquals(55.0, second.volume)
        // 다른 interval(prime 없음)은 기존대로 M1 로 시작한다.
        assertEquals(106.0, capturedFor(CandleInterval.H1).first().openPrice)
    }

    // 폴링은 진행 중 분봉을 돌려주고, 다음 라운드(count>1)가 같은 분의 완결본을 다시 보낸다 — 두 번째는 합산이 아니라 대체여야 한다.
    @Test
    fun `re-sent M1 of the same minute replaces its contribution instead of adding`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 100.0, high = 105.0, low = 99.0, close = 104.0, volume = 1.0, quoteVolume = 10.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 100.0, high = 110.0, low = 98.0, close = 108.0, volume = 3.0, quoteVolume = 30.0))

        val d1 = capturedFor(CandleInterval.D1).last()
        assertEquals(3.0, d1.volume, "같은 분의 재수신은 대체")
        assertEquals(30.0, d1.quoteVolume)
        assertEquals(110.0, d1.highPrice)
        assertEquals(98.0, d1.lowPrice)
        assertEquals(108.0, d1.closePrice)
    }

    // 한 라운드가 [완결 11, 완결 12, 진행 13] 을 오름차순으로 넘기면 건너뛴 12 가 복원되고,
    // 이미 접힌 분(다음 라운드 꼬리의 재전송)은 무시된다.
    @Test
    fun `older completed minutes in a round are folded once and re-sent folded minutes are ignored`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:11:00Z", open = 1.0, high = 2.0, low = 1.0, close = 1.5, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:12:00Z", open = 1.5, high = 9.0, low = 0.5, close = 2.0, volume = 2.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:13:00Z", open = 2.0, high = 3.0, low = 1.8, close = 2.5, volume = 4.0))
        // 다음 라운드 꼬리 — 11·12 는 이미 접혔으므로 무시, 13 은 완결본으로 대체, 14 는 새 provisional.
        aggregator.onMinuteCandle(m1("2024-01-01T00:11:00Z", open = 1.0, high = 2.0, low = 1.0, close = 1.5, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:12:00Z", open = 1.5, high = 9.0, low = 0.5, close = 2.0, volume = 2.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:13:00Z", open = 2.0, high = 3.5, low = 1.8, close = 2.6, volume = 5.0))
        aggregator.onMinuteCandle(m1("2024-01-01T00:14:00Z", open = 2.6, high = 2.7, low = 2.5, close = 2.6, volume = 1.0))

        val d1 = capturedFor(CandleInterval.D1).last()
        assertEquals(9.0, d1.highPrice, "건너뛴 12 의 고가 포함")
        assertEquals(0.5, d1.lowPrice)
        assertEquals(1.0 + 2.0 + 5.0 + 1.0, d1.volume, "11·12 는 한 번만, 13 은 완결본으로")
        assertEquals(2.6, d1.closePrice)
    }

    // 라운드 꼬리가 자정을 넘으면 전날 봉은 완결값으로 닫히고 다음 날 키가 새로 열린다.
    @Test
    fun `a round spanning midnight closes the previous day with its final minute and opens the next`() {
        aggregator.onMinuteCandle(m1("2024-01-01T23:58:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 4.0, low = 1.0, close = 3.0, volume = 2.0))
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 6.0, low = 1.0, close = 5.0, volume = 3.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:00:00Z", open = 5.0, high = 5.0, low = 5.0, close = 5.0, volume = 7.0))

        val byPeriod = capturedFor(CandleInterval.D1).groupBy { it.openTime }
        val day1 = byPeriod.getValue(inst("2024-01-01T00:00:00Z")).last()
        assertEquals(6.0, day1.highPrice, "전날 마지막 분의 완결본")
        assertEquals(4.0, day1.volume)
        assertEquals(7.0, byPeriod.getValue(inst("2024-01-02T00:00:00Z")).last().volume)
        val m5 = capturedFor(CandleInterval.M5).groupBy { it.openTime }
        assertEquals(6.0, m5.getValue(inst("2024-01-01T23:55:00Z")).last().highPrice, "전날 마지막 M5 도 완결본으로 닫힌다")
        assertEquals(7.0, m5.getValue(inst("2024-01-02T00:00:00Z")).last().volume)
    }

    // seed 는 prime 시각까지의 완전한 봉이라 그 이전 분의 M1(count>1 꼬리)은 이미 포함돼 있다 — 겹침은 prime 시각의 분 하나로 고정.
    @Test
    fun `M1 candles older than the prime minute are ignored so seed overlap stays within one minute`() {
        val day = inst("2024-01-01T00:00:00Z")
        val seeded = NormalizedCandle(
            exchange = Exchange.UPBIT, market = MARKET, openPrice = 100.0, highPrice = 130.0, lowPrice = 80.0, closePrice = 105.0,
            volume = 50.0, quoteVolume = 500.0, interval = CandleInterval.D1, openTime = day, closeTime = day.plusSeconds(86_400),
        )
        aggregator.prime(seeded, now = inst("2024-01-01T13:02:40Z"))

        aggregator.onMinuteCandle(m1("2024-01-01T13:00:00Z", open = 1.0, high = 200.0, low = 1.0, close = 1.0, volume = 10.0))
        aggregator.onMinuteCandle(m1("2024-01-01T13:01:00Z", open = 1.0, high = 200.0, low = 1.0, close = 1.0, volume = 10.0))
        aggregator.onMinuteCandle(m1("2024-01-01T13:02:00Z", open = 105.0, high = 106.0, low = 104.0, close = 106.0, volume = 2.0))

        val d1 = capturedFor(CandleInterval.D1).last()
        assertEquals(130.0, d1.highPrice, "prime 이전 분은 무시")
        assertEquals(52.0, d1.volume)
        assertEquals(106.0, d1.closePrice)
    }

    // 자정 직후 부팅: seed 는 어제·오늘 D1 을 store 에 넣고 오늘만 prime 한다. 첫 라운드 꼬리의 어제 분봉이 상태 없는 어제 period 를
    // 부분봉으로 새로 만들어 publish 하면 store 의 완전한 어제 D1 이 2분짜리로 덮인다 — 앞부분을 모르는 과거 period 는 publish 하지 않는다.
    @Test
    fun `tail candles of a past period without state are ignored instead of overwriting the seeded candle`() {
        val today = inst("2024-01-02T00:00:00Z")
        val seeded = NormalizedCandle(
            exchange = Exchange.UPBIT, market = MARKET, openPrice = 5.0, highPrice = 5.0, lowPrice = 5.0, closePrice = 5.0,
            volume = 1.0, interval = CandleInterval.D1, openTime = today, closeTime = today.plusSeconds(86_400),
        )
        aggregator.prime(seeded, now = inst("2024-01-02T00:02:30Z"))

        aggregator.onMinuteCandle(m1("2024-01-01T23:58:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:02:00Z", open = 5.0, high = 6.0, low = 5.0, close = 6.0, volume = 2.0))

        assertEquals(emptyList<Instant>(), capturedFor(CandleInterval.D1).map { it.openTime }.filter { it < today }, "어제 D1 을 publish 하지 않는다")
        assertEquals(6.0, capturedFor(CandleInterval.D1).last().highPrice)
    }

    // 같은 창인데 오늘 D1 행이 없어 prime 이 스킵된 경우(자정 직후엔 흔하다) — 바닥은 seed 시점에 모든 interval 에 걸린다.
    @Test
    fun `startFrom at seed time keeps past periods of every interval from being created by the first round's tail`() {
        aggregator.startFrom(Exchange.UPBIT, MARKET, now = inst("2024-01-02T00:02:30Z"))

        aggregator.onMinuteCandle(m1("2024-01-01T23:58:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:00:00Z", open = 5.0, high = 6.0, low = 5.0, close = 6.0, volume = 2.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:02:00Z", open = 6.0, high = 7.0, low = 6.0, close = 7.0, volume = 2.0))

        // W1/MO1 은 2024-01-01(월요일·월초)이 seed 시각의 period 라 제외 — 일 이하 interval 은 어제 period 를 만들지 않는다.
        val subDay = captured.filter { it.interval.minutes <= CandleInterval.D1.minutes }
        assertEquals(emptyList<Instant>(), subDay.map { it.openTime }.filter { it < inst("2024-01-02T00:00:00Z") }, "어제 period 는 publish 하지 않는다")
        val today = capturedFor(CandleInterval.D1).last()
        assertEquals(5.0, today.openPrice, "오늘 D1 은 00:00 분봉부터 새로 시작")
        assertEquals(4.0, today.volume)
    }

    @Test
    fun `prime rejects a candle whose openTime is not aligned to its period`() {
        val misaligned = NormalizedCandle(
            exchange = Exchange.UPBIT, market = MARKET, openPrice = 1.0, highPrice = 1.0, lowPrice = 1.0, closePrice = 1.0, volume = 1.0,
            interval = CandleInterval.D1, openTime = inst("2024-01-01T09:00:00Z"), closeTime = inst("2024-01-02T09:00:00Z"),
        )
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { aggregator.prime(misaligned, now = inst("2024-01-01T09:00:00Z")) }
    }

    @Test
    fun `aligns each interval to its period start`() {
        // 2024-01-17 = 수요일: interval 별 정렬 경계가 서로 달라(W1 월요일 01-15, MO1 01-01, D1 01-17)
        // 각 branch 를 독립 게이트한다. D1 정렬이 seed REST D1 openTime(UTC 자정)과 일치해야
        // MarketDataStore upsert dedup 이 성립 — 과거 D1 오염(d6c6857) 재발 방지 불변식의 전제.
        aggregator.onMinuteCandle(m1("2024-01-17T15:37:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))

        assertEquals(inst("2024-01-17T15:35:00Z"), openTimeOf(CandleInterval.M5))
        assertEquals(inst("2024-01-17T15:30:00Z"), openTimeOf(CandleInterval.M15))
        assertEquals(inst("2024-01-17T15:00:00Z"), openTimeOf(CandleInterval.H1))
        assertEquals(inst("2024-01-17T12:00:00Z"), openTimeOf(CandleInterval.H4))
        assertEquals(inst("2024-01-17T00:00:00Z"), openTimeOf(CandleInterval.D1))
        assertEquals(inst("2024-01-15T00:00:00Z"), openTimeOf(CandleInterval.W1))
        assertEquals(inst("2024-01-01T00:00:00Z"), openTimeOf(CandleInterval.MO1))
    }

    @Test
    fun `separates candles across the day boundary into distinct D1 periods`() {
        aggregator.onMinuteCandle(m1("2024-01-01T23:59:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 5.0))
        aggregator.onMinuteCandle(m1("2024-01-02T00:00:00Z", open = 2.0, high = 2.0, low = 2.0, close = 2.0, volume = 7.0))

        val byPeriod = capturedFor(CandleInterval.D1).groupBy { it.openTime }
        assertEquals(
            setOf(inst("2024-01-01T00:00:00Z"), inst("2024-01-02T00:00:00Z")),
            byPeriod.keys,
        )
        val day2 = byPeriod.getValue(inst("2024-01-02T00:00:00Z")).last()
        assertEquals(5.0, byPeriod.getValue(inst("2024-01-01T00:00:00Z")).last().volume)
        assertEquals(7.0, day2.volume) // 경계 넘어 병합되지 않음
        assertEquals(2.0, day2.openPrice) // 새 period 는 자기 봉 open 으로 시작
    }

    @Test
    fun `evicts D1 periods older than 3 intervals and does not republish a re-fed evicted period`() {
        aggregator.onMinuteCandle(m1("2024-01-01T00:00:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 5.0))
        // Jan 5 진입 → cutoff=Jan2, Jan1 D1 key 축출.
        aggregator.onMinuteCandle(m1("2024-01-05T00:00:00Z", open = 1.0, high = 1.0, low = 1.0, close = 1.0, volume = 1.0))
        // Jan1 재유입(무거래 마켓의 긴 꼬리) — 앞부분을 잃은 과거 period 라 부분봉으로 store 를 덮지 않는다(cleanup 없으면 5+7=12 로 publish).
        aggregator.onMinuteCandle(m1("2024-01-01T06:00:00Z", open = 9.0, high = 9.0, low = 9.0, close = 9.0, volume = 7.0))

        val jan1 = capturedFor(CandleInterval.D1).filter { it.openTime == inst("2024-01-01T00:00:00Z") }
        assertEquals(listOf(5.0), jan1.map { it.volume })
    }

    private fun capturedFor(interval: CandleInterval) = captured.filter { it.interval == interval }

    private fun openTimeOf(interval: CandleInterval) = capturedFor(interval).single().openTime

    private fun m1(openTime: String, open: Double, high: Double, low: Double, close: Double, volume: Double, quoteVolume: Double = 0.0) =
        NormalizedCandle(
            exchange = Exchange.UPBIT,
            market = MARKET,
            openPrice = open,
            highPrice = high,
            lowPrice = low,
            closePrice = close,
            volume = volume,
            quoteVolume = quoteVolume,
            interval = CandleInterval.M1,
            openTime = inst(openTime),
            closeTime = inst(openTime).plusSeconds(60),
        )

    private fun inst(iso: String) = Instant.parse(iso)

    companion object {
        private const val MARKET = "BTC/KRW"
    }
}
