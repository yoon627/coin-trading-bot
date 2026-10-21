package com.trading.bot.kis.market

import com.trading.bot.kis.client.KisDailyCandle
import com.trading.bot.kis.client.KisQuote
import com.trading.common.domain.Candle
import java.time.LocalDate

/**
 * KIS 일봉 이력과 오늘 시세를 엔진의 일봉 어휘로 합친다.
 * 엔진은 newest 봉의 candleDateTimeUtc 가 `tradingDate.atStartOfDay(UTC)` 이상이면 오늘 봉으로 보므로
 * candleDateTimeUtc 는 KST 거래일의 'T00:00:00' 이다.
 */
object KisDailyCandles {

    /**
     * 최신이 [0] 이고 최대 [count] 개다. [history] 의 [today] 이후 행은 진행 중이거나 잘못 온 것이라 버린다.
     * 오늘이 거래일이고 시가가 0 보다 클 때만 시세로 오늘 봉을 만든다. 장 시작 전에는 시가가 0 이다.
     */
    fun merge(
        ticker: String,
        history: List<KisDailyCandle>,
        todayQuote: KisQuote?,
        today: LocalDate,
        todayIsTradingDay: Boolean,
        count: Int,
    ): List<Candle> {
        require(count >= 1) { "count 는 1 이상이어야 한다: $count" }
        val code = KrxTicker.toCode(ticker)
        require(todayQuote == null || todayQuote.code == code) { "시세 종목 ${todayQuote?.code} 이 티커 $ticker 와 다르다" }
        val todayCandle = todayQuote
            ?.takeIf { todayIsTradingDay && it.open > 0 }
            ?.let { candle(ticker, today, it.open, it.high, it.low, it.price, volume = 0) }
        // 시가·고가·저가가 0 인 행(거래정지일로 보인다)은 체결이 없던 날이라 버린다. 남기면 변동성 돌파 폭이 0 이 되어 필터가 꺼진다.
        val past = history
            .filter { it.date.isBefore(today) && it.open > 0 && it.high > 0 && it.low > 0 }
            .sortedByDescending { it.date }
            .map { candle(ticker, it.date, it.open, it.high, it.low, it.close, it.volume) }
        return (listOfNotNull(todayCandle) + past).take(count)
    }

    // 전략은 거래대금을 쓰지 않고 KIS 일봉에서도 받지 않아 0 으로 둔다. 오늘 시세에는 거래량이 없다.
    private fun candle(ticker: String, date: LocalDate, open: Long, high: Long, low: Long, close: Long, volume: Long) = Candle(
        market = ticker,
        candleDateTimeUtc = "${date}T00:00:00",
        candleDateTimeKst = "${date}T09:00:00",
        openingPrice = open.toDouble(),
        highPrice = high.toDouble(),
        lowPrice = low.toDouble(),
        tradePrice = close.toDouble(),
        candleAccTradePrice = 0.0,
        candleAccTradeVolume = volume.toDouble(),
    )
}
