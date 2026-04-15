package com.trading.bot.api

import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.domain.RealtimePrice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class PriceStreamControllerTest {

    private lateinit var webSocketClient: UpbitWebSocketClient
    private lateinit var controller: PriceStreamController

    @BeforeEach
    fun setup() {
        webSocketClient = mockk(relaxed = true)
        controller = PriceStreamController(webSocketClient)
    }

    @Test
    fun `getLatestPrices returns all prices when no filter`() {
        val prices = mapOf(
            "KRW-BTC" to RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12),
            "KRW-ETH" to RealtimePrice("KRW-ETH", 3000000.0, -0.02, 5e11),
        )
        every { webSocketClient.allLatestPrices() } returns prices

        val result = controller.getLatestPrices(null)
        assertEquals(2, result.size)
        assertTrue(result.containsKey("KRW-BTC"))
        assertTrue(result.containsKey("KRW-ETH"))
    }

    @Test
    fun `getLatestPrices filters by tickers`() {
        val prices = mapOf(
            "KRW-BTC" to RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12),
            "KRW-ETH" to RealtimePrice("KRW-ETH", 3000000.0, -0.02, 5e11),
            "KRW-XRP" to RealtimePrice("KRW-XRP", 500.0, 0.05, 1e10),
        )
        every { webSocketClient.allLatestPrices() } returns prices

        val result = controller.getLatestPrices(listOf("KRW-BTC", "KRW-ETH"))
        assertEquals(2, result.size)
        assertFalse(result.containsKey("KRW-XRP"))
    }

    @Test
    fun `getLatestPrices handles case insensitive tickers`() {
        val prices = mapOf(
            "KRW-BTC" to RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12),
        )
        every { webSocketClient.allLatestPrices() } returns prices

        val result = controller.getLatestPrices(listOf("krw-btc"))
        assertEquals(1, result.size)
    }

    @Test
    fun `getConnectionStatus returns status`() {
        every { webSocketClient.isConnected() } returns true
        every { webSocketClient.allLatestPrices() } returns mapOf(
            "KRW-BTC" to RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12),
        )

        val result = controller.getConnectionStatus()
        assertEquals(true, result["connected"])
        assertTrue((result["tickers"] as Set<*>).contains("KRW-BTC"))
    }

    @Test
    fun `streamPrices subscribes to requested tickers`() {
        every { webSocketClient.priceFlow() } returns Flux.empty()

        controller.streamPrices(listOf("KRW-BTC", "KRW-ETH"))

        verify { webSocketClient.subscribe(listOf("KRW-BTC", "KRW-ETH")) }
    }

    @Test
    fun `streamPrices returns Flux of SSE events`() {
        val priceFlux = Flux.just(
            RealtimePrice("KRW-BTC", 50000000.0, 0.01, 1e12),
        )
        every { webSocketClient.priceFlow() } returns priceFlux

        val result = controller.streamPrices(null)
        assertNotNull(result)
    }
}
