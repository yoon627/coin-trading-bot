package com.trading.bot.kis.marketdata

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisCandle
import com.trading.bot.kis.domain.KisCandlePeriod
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Extends the KIS 100-row daily-chart request with a date-window cursor.
 * FHKST03010100 uses inclusive start/end dates and does not expose the account-query
 * continuation headers used by order-conclusion APIs.
 */
class KisDailyCandleHistoryCollector(
    private val client: KisClient,
    private val requestWindowDays: Long = DEFAULT_REQUEST_WINDOW_DAYS,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    init {
        require(requestWindowDays > 0) { "requestWindowDays must be positive" }
        require(maxPages > 0) { "maxPages must be positive" }
    }

    suspend fun collect(
        symbol: String,
        from: LocalDate,
        to: LocalDate,
        adjusted: Boolean,
    ): List<KisCandle> {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(!from.isAfter(to)) { "from must not be after to" }

        val candlesByDate = linkedMapOf<LocalDate, KisCandle>()
        var windowTo = to
        var page = 0
        while (!windowTo.isBefore(from)) {
            check(page < maxPages) { "KIS daily candle history exceeded maxPages=$maxPages" }
            val windowFrom = maxOf(from, windowTo.minusDays(requestWindowDays - 1))
            val response = client.getDailyCandles(
                symbol = symbol,
                from = windowFrom.format(BASIC_DATE),
                to = windowTo.format(BASIC_DATE),
                period = KisCandlePeriod.D,
                adjusted = adjusted,
            )
            val responseDates = response.map { candle ->
                val date = parseDate(candle.date)
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    candlesByDate.putIfAbsent(date, candle)
                }
                date
            }
            val oldest = responseDates.minOrNull()
            windowTo = if (oldest != null && oldest.isBefore(windowTo)) {
                oldest.minusDays(1)
            } else {
                windowFrom.minusDays(1)
            }
            page++
        }

        return candlesByDate.entries
            .sortedByDescending { it.key }
            .map { it.value }
    }

    private fun parseDate(value: String): LocalDate =
        try {
            LocalDate.parse(value, BASIC_DATE)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("KIS candle date must be YYYYMMDD", e)
        }

    private companion object {
        const val DEFAULT_REQUEST_WINDOW_DAYS = 100L
        const val DEFAULT_MAX_PAGES = 100
        val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
