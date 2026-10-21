package com.trading.bot.kis.market

import com.trading.bot.kis.client.KisDailyCandle
import com.trading.bot.kis.client.KisQuote
import com.trading.common.domain.Candle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KisDailyCandlesTest {

    private val ticker = "KRX-005930"
    private val today = LocalDate.of(2026, 10, 21)
    private val quote = KisQuote("005930", price = 105, open = 100, high = 110, low = 95)

    private fun row(date: LocalDate, close: Long = 50) = KisDailyCandle(date, open = 40, high = 60, low = 30, close = close, volume = 1_000)

    private fun history(vararg daysAgo: Long) = daysAgo.map { row(today.minusDays(it)) }

    private fun merge(
        history: List<KisDailyCandle>,
        todayQuote: KisQuote? = quote,
        tradingDay: Boolean = true,
        count: Int = 30,
    ) = KisDailyCandles.merge(ticker, history, todayQuote, today, tradingDay, count)

    @Test
    fun `오늘 봉을 맨 앞에 두고 최신순으로 최대 count 개를 돌려준다`() {
        val merged = merge(history(3, 1, 2), count = 3)

        assertThat(merged.map { it.candleDateTimeUtc }).containsExactly("2026-10-21T00:00:00", "2026-10-20T00:00:00", "2026-10-19T00:00:00")
        val todayCandle = merged[0]
        assertThat(listOf(todayCandle.open, todayCandle.high, todayCandle.low, todayCandle.close)).containsExactly(100.0, 110.0, 95.0, 105.0)
        val past = merged[1]
        assertThat(past.market).isEqualTo(ticker)
        assertThat(past.candleDateTimeKst).isEqualTo("2026-10-20T09:00:00")
        assertThat(listOf(past.open, past.high, past.low, past.close)).containsExactly(40.0, 60.0, 30.0, 50.0)
        assertThat(past.candleAccTradeVolume).isEqualTo(1_000.0)
        assertThat(merged.map { it.candleAccTradePrice }).containsOnly(0.0)
    }

    @Test
    fun `오늘 봉은 거래일이고 시가가 0 보다 클 때만 만든다`() {
        val newestOf = { merged: List<Candle> -> merged.first().candleDateTimeUtc }

        assertThat(newestOf(merge(history(1), tradingDay = false))).isEqualTo("2026-10-20T00:00:00")
        assertThat(newestOf(merge(history(1), todayQuote = quote.copy(open = 0)))).isEqualTo("2026-10-20T00:00:00")
        assertThat(newestOf(merge(history(1), todayQuote = null))).isEqualTo("2026-10-20T00:00:00")
        assertThat(merge(emptyList(), todayQuote = null)).isEmpty()
        assertThat(merge(emptyList()).map { it.candleDateTimeUtc }).containsExactly("2026-10-21T00:00:00")
    }

    @Test
    fun `이력의 오늘 이후 행은 버리고 시세로 만든 오늘 봉만 남긴다`() {
        val merged = merge(listOf(row(today.plusDays(1), close = 999), row(today, close = 999)) + history(1))

        assertThat(merged.map { it.candleDateTimeUtc }).containsExactly("2026-10-21T00:00:00", "2026-10-20T00:00:00")
        assertThat(merged.map { it.close }).doesNotContain(999.0)
    }

    @Test
    fun `시가·고가·저가가 0 인 이력 행은 체결이 없던 날이라 버린다`() {
        val suspended = KisDailyCandle(today.minusDays(1), open = 0, high = 0, low = 0, close = 50, volume = 0)

        val merged = merge(listOf(suspended, row(today.minusDays(2))), todayQuote = null)

        assertThat(merged.map { it.candleDateTimeUtc }).containsExactly("2026-10-19T00:00:00")
    }

    @Test
    fun `티커가 형식이 아니거나 시세 종목이 다르면 예외다`() {
        assertThatThrownBy { KisDailyCandles.merge("KRW-BTC", emptyList(), null, today, true, 30) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { merge(history(1), todayQuote = quote.copy(code = "000660")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `엔진의 오늘 봉 판정 규약에 맞는다`() {
        // KST 2026-10-21 09:30. 엔진은 newest 봉의 candleDateTimeUtc 를 UTC 로 읽어 거래일 00:00(UTC 표기) 이상인지 본다.
        val clock = Clock.fixed(Instant.parse("2026-10-21T00:30:00Z"), ZoneOffset.UTC)
        val dayOpen = KrxTradingCalendar(KrxSchedule.load()).tradingDate(clock).atStartOfDay(ZoneOffset.UTC).toInstant()
        val newestOpen = { merged: List<Candle> ->
            LocalDateTime.parse(merged.first().candleDateTimeUtc).toInstant(ZoneOffset.UTC)
        }

        assertThat(newestOpen(merge(history(1)))).isEqualTo(dayOpen)
        assertThat(newestOpen(merge(history(1), todayQuote = quote.copy(open = 0)))).isBefore(dayOpen)
    }
}
