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
    fun `default is the operating watchlist`() {
        assertEquals(
            listOf(
                "KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-SOL", "KRW-DOGE", "KRW-ADA", "KRW-AVAX",
                "KRW-LINK", "KRW-DOT", "KRW-SHIB", "KRW-NEAR", "KRW-SUI", "KRW-HBAR",
            ),
            WatchlistProperties().tickerList(),
        )
    }

    @Test
    fun `tickerList is empty when blank`() {
        assertTrue(WatchlistProperties("").tickerList().isEmpty())
    }
}
