package com.trading.bot.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WatchlistPropertiesTest {

    @Test
    fun `tickerList normalizes to uppercase and trims`() {
        assertEquals(listOf("KRW-BTC", "KRW-ETH"), WatchlistProperties(" krw-btc , KRW-ETH ").tickerList())
    }

    @Test
    fun `tickerList drops blank entries`() {
        assertEquals(listOf("KRW-BTC"), WatchlistProperties("KRW-BTC,,  ,").tickerList())
    }

    @Test
    fun `tickerList dedups case-insensitively`() {
        assertEquals(listOf("KRW-BTC"), WatchlistProperties("KRW-BTC,krw-btc").tickerList())
    }

    @Test
    fun `tickerList is empty when blank`() {
        assertTrue(WatchlistProperties("").tickerList().isEmpty())
    }
}
