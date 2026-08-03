package com.trading.bot.kis.marketdata

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisCandle
import com.trading.bot.kis.domain.KisCandlePeriod
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KisDailyCandleHistoryCollectorTest {

    private val client = mockk<KisClient>()

    @Test
    fun `pages date windows and preserves adjusted flag`() = runTest {
        coEvery {
            client.getDailyCandles("005930", "20260613", "20260615", KisCandlePeriod.D, true)
        } returns listOf(candle("20260615"), candle("20260614"), candle("20260614"))
        coEvery {
            client.getDailyCandles("005930", "20260611", "20260613", KisCandlePeriod.D, true)
        } returns listOf(candle("20260613"), candle("20260612"))
        coEvery {
            client.getDailyCandles("005930", "20260609", "20260611", KisCandlePeriod.D, true)
        } returns listOf(candle("20260611"), candle("20260610"), candle("20260609"))

        val result = KisDailyCandleHistoryCollector(client, requestWindowDays = 3)
            .collect("005930", LocalDate.parse("20260609", BASIC), LocalDate.parse("20260615", BASIC), adjusted = true)

        assertEquals(listOf("20260615", "20260614", "20260613", "20260612", "20260611", "20260610", "20260609"), result.map { it.date })
        coVerifyOrder {
            client.getDailyCandles("005930", "20260613", "20260615", KisCandlePeriod.D, true)
            client.getDailyCandles("005930", "20260611", "20260613", KisCandlePeriod.D, true)
            client.getDailyCandles("005930", "20260609", "20260611", KisCandlePeriod.D, true)
        }
    }

    @Test
    fun `continues across a sparse response`() = runTest {
        coEvery {
            client.getDailyCandles("005930", "20260613", "20260615", KisCandlePeriod.D, false)
        } returns listOf(candle("20260615"))
        coEvery {
            client.getDailyCandles("005930", "20260610", "20260612", KisCandlePeriod.D, false)
        } returns listOf(candle("20260610"))
        coEvery {
            client.getDailyCandles("005930", "20260609", "20260609", KisCandlePeriod.D, false)
        } returns emptyList()

        val result = KisDailyCandleHistoryCollector(client, requestWindowDays = 3)
            .collect("005930", LocalDate.parse("20260609", BASIC), LocalDate.parse("20260615", BASIC), adjusted = false)

        assertEquals(listOf("20260615", "20260610"), result.map { it.date })
        coVerifyOrder {
            client.getDailyCandles("005930", "20260613", "20260615", KisCandlePeriod.D, false)
            client.getDailyCandles("005930", "20260610", "20260612", KisCandlePeriod.D, false)
        }
    }

    @Test
    fun `continues across an empty response`() = runTest {
        coEvery {
            client.getDailyCandles("005930", "20260613", "20260615", KisCandlePeriod.D, false)
        } returns emptyList()
        coEvery {
            client.getDailyCandles("005930", "20260610", "20260612", KisCandlePeriod.D, false)
        } returns listOf(candle("20260610"))
        coEvery {
            client.getDailyCandles("005930", "20260609", "20260609", KisCandlePeriod.D, false)
        } returns emptyList()

        val result = KisDailyCandleHistoryCollector(client, requestWindowDays = 3)
            .collect("005930", LocalDate.parse("20260609", BASIC), LocalDate.parse("20260615", BASIC), adjusted = false)

        assertEquals(listOf("20260610"), result.map { it.date })
    }

    @Test
    fun `rejects reversed date range`() {
        val collector = KisDailyCandleHistoryCollector(client)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                collector.collect("005930", LocalDate.parse("20260615", BASIC), LocalDate.parse("20260609", BASIC), adjusted = true)
            }
        }
    }

    private fun candle(date: String) = KisCandle(
        date = date,
        open = 100,
        high = 110,
        low = 90,
        close = 105,
        volume = 1_000,
    )

    private companion object {
        val BASIC = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    }
}
