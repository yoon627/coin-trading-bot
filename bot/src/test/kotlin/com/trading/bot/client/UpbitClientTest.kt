package com.trading.bot.client

import com.trading.bot.config.UpbitProperties
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `getOrder parses the charged fee from the real response shape`() = runTest {
        // 이 매핑이 깨지면(어노테이션 오타·삭제·Upbit 필드명 변경) paidFee 가 늘 null 이 되어
        // 모든 엔진 매수가 Unrecorded → fee 0 으로 조용히 떨어진다. 경고도 없고 fee 를 읽는 곳도
        // 없어 아무도 눈치채지 못한다. 그래서 실제 응답 형태로 파싱까지 고정한다(#133).
        mockServer.enqueue(
            MockResponse()
                .setBody(
                    """{"uuid":"o-1","side":"bid","ord_type":"price","price":"100000","state":"cancel",
                       "market":"KRW-BTC","volume":null,"remaining_volume":null,"executed_volume":"0.0003",
                       "trades_count":1,"reserved_fee":"50.0","remaining_fee":"37.7","paid_fee":"12.3"}""",
                )
                .addHeader("Content-Type", "application/json")
        )

        val order = client.getOrder("o-1")

        assertEquals("0.0003", order.executedVolume)
        assertEquals("cancel", order.state)
        assertEquals("12.3", order.paidFee)
        assertEquals(FeeBasis.Measured(12.3), order.feeBasis())
    }

    @Test
    fun `getOrder without a fee field falls back to unrecorded`() = runTest {
        // 필드 자체가 없는 응답에서도 예외 없이 미기록으로 떨어져야 한다.
        mockServer.enqueue(
            MockResponse()
                .setBody("""{"uuid":"o-2","state":"done","executed_volume":"0.001"}""")
                .addHeader("Content-Type", "application/json")
        )

        val order = client.getOrder("o-2")

        assertNull(order.paidFee)
        assertEquals(FeeBasis.Unrecorded, order.feeBasis())
    }

    @Test
    fun `feeBasis rejects values that would poison the fee column`() {
        // toDoubleOrNull 은 "NaN"·"Infinity" 를 정상 파싱한다(실측). 그 값이 double precision 컬럼에
        // 들어가면 이후 SUM(fee) 이 영구히 NaN 이 된다 — 0 이 섞이는 것과 달리 되돌릴 수 없다.
        listOf("NaN", "Infinity", "-Infinity", "-1.5", "not-a-number", "").forEach { raw ->
            assertEquals(
                FeeBasis.Unrecorded,
                Order(uuid = "o", paidFee = raw).feeBasis(),
                "paid_fee=\"$raw\" 는 실측으로 쓰면 안 된다",
            )
        }
        assertEquals(FeeBasis.Measured(0.0), Order(uuid = "o", paidFee = "0").feeBasis())
        assertEquals(FeeBasis.Measured(12.3), Order(uuid = "o", paidFee = "12.3").feeBasis())
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
