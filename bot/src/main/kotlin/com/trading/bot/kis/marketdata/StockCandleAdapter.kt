package com.trading.bot.kis.marketdata

import com.trading.bot.kis.domain.KisCandle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** KIS REST 캔들(KisCandle, 정수 OHLCV) → 공용 NormalizedCandle 변환. 전략은 NormalizedCandle 로 동작. */
object StockCandleAdapter {
    private val KST = ZoneId.of("Asia/Seoul")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")

    fun toNormalized(symbol: String, c: KisCandle, interval: CandleInterval): NormalizedCandle {
        val openTime = openTime(c)
        return NormalizedCandle(
            exchange = Exchange.KIS,
            market = symbol,
            openPrice = c.open.toDouble(),
            highPrice = c.high.toDouble(),
            lowPrice = c.low.toDouble(),
            closePrice = c.close.toDouble(),
            volume = c.volume.toDouble(),
            interval = interval,
            openTime = openTime,
            closeTime = openTime,
        )
    }

    private fun openTime(c: KisCandle): Instant {
        val date = LocalDate.parse(c.date, DateTimeFormatter.BASIC_ISO_DATE)
        val time = c.time?.takeIf { it.length == 6 }?.let { LocalTime.parse(it, TIME_FMT) }
        return if (time != null) date.atTime(time).atZone(KST).toInstant() else date.atStartOfDay(KST).toInstant()
    }
}
