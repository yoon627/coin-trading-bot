package com.trading.bot.client

import com.trading.bot.config.UpbitProperties
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class UpbitClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: UpbitClient

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val properties = UpbitProperties(
            accessKey = "test-key",
            secretKey = "test-secret-key-that-is-long-enough",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
        )
        val authProvider = UpbitAuthProvider(properties)
        val webClient = WebClient.builder()
            .baseUrl(properties.baseUrl)
            .build()
        client = UpbitClientImpl(webClient, authProvider)
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun `getAccounts returns parsed accounts`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("""[{"currency":"KRW","balance":"1000000","locked":"0","avg_buy_price":"0","avg_buy_price_modified":false,"unit_currency":"KRW"}]""")
                .addHeader("Content-Type", "application/json")
        )

        val accounts = client.getAccounts()
        assertEquals(1, accounts.size)
        assertEquals("KRW", accounts[0].currency)
        assertEquals(1_000_000.0, accounts[0].balanceDouble())
    }

    @Test
    fun `getTicker returns parsed ticker`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("""[{"market":"KRW-BTC","trade_price":95000000.0,"signed_change_rate":0.01,"acc_trade_price_24h":500000000000.0}]""")
                .addHeader("Content-Type", "application/json")
        )

        val tickers = client.getTicker("KRW-BTC")
        assertEquals(1, tickers.size)
        assertEquals("KRW-BTC", tickers[0].market)
        assertEquals(95_000_000.0, tickers[0].tradePrice)
    }

    @Test
    fun `getDayCandles returns parsed candles`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("""[{"market":"KRW-BTC","candle_date_time_utc":"2024-01-01T00:00:00","candle_date_time_kst":"2024-01-01T09:00:00","opening_price":94000000,"high_price":96000000,"low_price":93000000,"trade_price":95000000,"candle_acc_trade_price":100000000,"candle_acc_trade_volume":1.5}]""")
                .addHeader("Content-Type", "application/json")
        )

        val candles = client.getDayCandles("KRW-BTC", 1)
        assertEquals(1, candles.size)
        assertEquals(95_000_000.0, candles[0].tradePrice)
        assertEquals(96_000_000.0, candles[0].highPrice)
    }
}
