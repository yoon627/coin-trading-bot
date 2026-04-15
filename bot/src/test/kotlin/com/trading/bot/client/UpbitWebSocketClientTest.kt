package com.trading.bot.client

import com.trading.bot.domain.RealtimePrice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpbitWebSocketClientTest {

    @Test
    fun `latestPrice returns null when no data received`() {
        val client = UpbitWebSocketClient()
        assertNull(client.latestPrice("KRW-BTC"))
    }

    @Test
    fun `allLatestPrices returns empty map initially`() {
        val client = UpbitWebSocketClient()
        assertTrue(client.allLatestPrices().isEmpty())
    }

    @Test
    fun `isConnected returns false initially`() {
        val client = UpbitWebSocketClient()
        assertFalse(client.isConnected())
    }

    @Test
    fun `subscribe adds tickers without error`() {
        val client = UpbitWebSocketClient()
        // Should not throw even without actual connection
        assertDoesNotThrow { client.subscribe(listOf("KRW-BTC", "KRW-ETH")) }
    }

    @Test
    fun `priceFlow returns a Flux`() {
        val client = UpbitWebSocketClient()
        val flux = client.priceFlow()
        assertNotNull(flux)
    }
}
