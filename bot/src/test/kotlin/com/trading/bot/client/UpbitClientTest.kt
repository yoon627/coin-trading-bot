package com.trading.bot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

class UpbitClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: UpbitClient

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val authProvider = UpbitAuthProvider(
            accessKey = "test-key",
            secretKey = "test-secret-key-that-is-long-enough",
        )
        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString().trimEnd('/'))
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
    fun `placeOrder sends the identifier in the body and signs it into the query hash`() = runTest {
        // 해시에서 빠지면 거래소가 서명 불일치로 거절하고, 본문에서 빠지면 불명 주문을 identifier 로 찾을 수 없다(#227).
        mockServer.enqueue(MockResponse().setBody("""{"uuid":"o-9"}""").addHeader("Content-Type", "application/json"))

        client.placeOrder(OrderRequest(market = "KRW-BTC", side = "bid", ordType = "price", price = "10000", identifier = "ctb-abc"))

        val request = mockServer.takeRequest()
        assertEquals("ctb-abc", ObjectMapper().readTree(request.body.readUtf8()).get("identifier")?.asText())
        assertEquals(sha512("market=KRW-BTC&side=bid&ord_type=price&price=10000&identifier=ctb-abc"), queryHashOf(request))
    }

    @Test
    fun `placeOrder without an identifier leaves it out of the request`() = runTest {
        mockServer.enqueue(MockResponse().setBody("""{"uuid":"o-10"}""").addHeader("Content-Type", "application/json"))

        client.placeOrder(OrderRequest(market = "KRW-BTC", side = "ask", ordType = "market", volume = "0.1"))

        val request = mockServer.takeRequest()
        assertFalse(ObjectMapper().readTree(request.body.readUtf8()).has("identifier"))
        assertEquals(sha512("market=KRW-BTC&side=ask&ord_type=market&volume=0.1"), queryHashOf(request))
    }

    @Test
    fun `getOrderByIdentifier returns the order the exchange knows by that identifier`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("""{"uuid":"o-11","identifier":"ctb-abc","state":"done","executed_volume":"0.001"}""")
                .addHeader("Content-Type", "application/json"),
        )

        val order = client.getOrderByIdentifier("ctb-abc")

        assertEquals("o-11", order?.uuid)
        val request = mockServer.takeRequest()
        assertEquals("/v1/order?identifier=ctb-abc", request.path)
        assertEquals(sha512("identifier=ctb-abc"), queryHashOf(request))
    }

    @Test
    fun `getOrderByIdentifier returns null only when the exchange says the order does not exist`() = runTest {
        mockServer.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"name":"order_not_found","message":"주문을 찾지 못했습니다."}}""")
                .addHeader("Content-Type", "application/json"),
        )
        assertNull(client.getOrderByIdentifier("ctb-missing"))

        // 같은 404 라도 다른 이유면 "주문이 없다"의 증거가 아니다 — 미접수 확정으로 흘러가면 안 된다.
        mockServer.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"name":"currency_not_found","message":"x"}}""")
                .addHeader("Content-Type", "application/json"),
        )
        assertThrows<UpbitApiException> { client.getOrderByIdentifier("ctb-other") }

        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        assertThrows<UpbitApiException> { client.getOrderByIdentifier("ctb-down") }
    }

    @Test
    fun `only request validation errors prove that an order was not placed`() {
        listOf(
            400 to "insufficient_funds_bid", 400 to "insufficient_funds_ask", 400 to "under_min_total_bid",
            400 to "under_min_total_ask", 400 to "create_bid_error", 400 to "create_ask_error", 400 to "validation_error",
            400 to "invalid_parameter", 400 to "over_krw_funds_bid", 400 to "notfoundmarket", 403 to "market_offline",
        ).forEach { (status, name) ->
            assertTrue(UpbitApiException(status, name, null, "").provesOrderNotPlaced(), "$status $name")
        }
        // 접수 여부를 말해 주지 않는 실패 — 불명으로 남겨 identifier 로 확정해야 한다.
        listOf(
            UpbitApiException(400, "duplicated_identifier", null, ""),
            UpbitApiException(401, "nonce_used", null, ""),
            UpbitApiException(403, "out_of_scope", null, ""),
            UpbitApiException(429, null, null, ""),
            UpbitApiException(400, null, null, "<html>"),
            UpbitApiException(500, null, null, ""),
            UpbitApiException(500, "insufficient_funds_bid", null, ""),
            java.util.concurrent.TimeoutException("response timeout"),
            java.io.IOException("connection reset"),
        ).forEach { assertFalse(it.provesOrderNotPlaced(), it.toString()) }
    }

    @Test
    fun `order identifiers are unique and fit the exchange limit`() {
        val ids = List(100) { newOrderIdentifier() }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it.length <= 64 && it.matches(Regex("[a-z0-9-]+")) })
    }

    private fun queryHashOf(request: RecordedRequest): String? {
        val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
        val claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor("test-secret-key-that-is-long-enough".toByteArray()))
            .build().parseSignedClaims(token).payload
        return claims["query_hash"] as String?
    }

    private fun sha512(s: String): String =
        MessageDigest.getInstance("SHA-512").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

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
