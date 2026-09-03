package com.trading.bot.config

import com.trading.common.config.AccumulateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccumulatePropertiesTest {

    @Test
    fun `no tickers by default so the profile is off`() {
        assertTrue(AccumulateProperties().tickerList().isEmpty())
    }

    @Test
    fun `tickerList normalizes and dedups`() {
        val props = AccumulateProperties(tickers = " krw-btc , KRW-ETH, krw-btc ,")
        assertEquals(listOf("KRW-BTC", "KRW-ETH"), props.tickerList())
    }

    @Test
    fun `ladderParams mirrors the configured values`() {
        val params = AccumulateProperties(tickers = "KRW-BTC", budgetKrw = 50_000.0, maxRungs = 4, stepDownPct = 2.0, stepUpPct = 5.0).ladderParams()
        assertEquals(50_000.0, params.budgetKrw)
        assertEquals(4, params.maxRungs)
        assertEquals(2.0, params.stepDownPct)
        assertEquals(5.0, params.stepUpPct)
    }

    @Test
    fun `rejects a rung below the exchange minimum order at startup`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccumulateProperties(tickers = "KRW-BTC", budgetKrw = 20_000.0, maxRungs = 5)
        }
    }
}
