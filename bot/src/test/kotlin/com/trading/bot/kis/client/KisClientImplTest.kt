package com.trading.bot.kis.client

import com.trading.bot.kis.domain.KisOrderRequest
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class KisClientImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KisClientImpl

    private val buyReq = KisOrderRequest(
        cano = "12345678", acntPrdtCd = "01", symbol = "005930",
        side = KisSide.BUY, orderType = KisOrderType.LIMIT, qty = 10, price = 70_000,
    )

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        val webClient = WebClient.builder().baseUrl(server.url("/").toString().trimEnd('/')).build()
        val tokenProvider = mockk<KisTokenProvider>()
        coEvery { tokenProvider.token() } returns "tok"
        client = KisClientImpl(
            webClient, tokenProvider, appKey = "ak", appSecret = "sk",
            cano = "12345678", acntPrdtCd = "01", paper = false, custType = "P",
        )
    }

    @AfterEach
    fun teardown() = server.shutdown()

    @Test
    fun `daily candles are returned newest-first to match strategy convention`() = runTest {
        // KIS 응답 순서와 무관하게 candles[0]=최신이어야 한다 — Indicators 가 candles[0]을 오늘,
        // candles[1]을 전일로 읽고(calculateTargetPrice) take(period)로 최신 N개를 자른다.
        server.enqueue(
            MockResponse().setBody(
                """
                {"rt_cd":"0","output2":[
                  {"stck_bsop_date":"20260612","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_clpr":"105","acml_vol":"1000"},
                  {"stck_bsop_date":"20260615","stck_oprc":"200","stck_hgpr":"210","stck_lwpr":"190","stck_clpr":"205","acml_vol":"2000"},
                  {"stck_bsop_date":"20260613","stck_oprc":"300","stck_hgpr":"310","stck_lwpr":"290","stck_clpr":"305","acml_vol":"3000"}
                ]}
                """.trimIndent(),
            ).addHeader("Content-Type", "application/json"),
        )

        val candles = client.getDailyCandles("005930", "20260601", "20260615")

        assertEquals(listOf("20260615", "20260613", "20260612"), candles.map { it.date })
    }

    @Test
    fun `daily candles map adjusted flag to KIS adjusted-price parameter`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"rt_cd":"0","output2":[]}""")
                .addHeader("Content-Type", "application/json"),
        )

        client.getDailyCandles("005930", "20260601", "20260615", adjusted = true)

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("FID_ORG_ADJ_PRC=0"), "expected adjusted-price flag, path=${req.path}")
    }

    @Test
    fun `buyable qty is read from nrcvb_buy_qty`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"rt_cd":"0","output":{"nrcvb_buy_qty":"12","nrcvb_buy_amt":"840000"}}""")
                .addHeader("Content-Type", "application/json"),
        )

        val qty = client.getBuyableQty("005930", 70_000)

        assertEquals(12L, qty)
        val req = server.takeRequest()
        // 지정가(00)로 조회하면 종목 증거금률이 반영되지 않아 과대 수량이 나온다 — 반드시 시장가(01).
        assertTrue(req.path!!.contains("ORD_DVSN=01"), "expected market-order division, path=${req.path}")
        assertTrue(req.path!!.contains("PDNO=005930"))
    }

    @Test
    fun `buyable qty raises on business error so callers can fail closed`() {
        server.enqueue(
            MockResponse().setBody("""{"rt_cd":"1","msg1":"조회 실패"}""")
                .addHeader("Content-Type", "application/json"),
        )

        assertThrows(com.trading.bot.kis.client.KisApiException::class.java) {
            runBlocking { client.getBuyableQty("005930", 70_000) }
        }
    }

    @Test
    fun `placeOrder maps ODNO and sends real-buy tr_id + body`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"0","msg_cd":"APBK0013","msg1":"주문 완료","output":{"KRX_FWDG_ORD_ORGNO":"91252","ODNO":"0000117057","ORD_TMD":"101010"}}""")
                .addHeader("Content-Type", "application/json"),
        )

        val ack = client.placeOrder(buyReq)

        assertEquals("0000117057", ack.odno)
        assertEquals("91252", ack.orgNo)
        val req = server.takeRequest()
        assertEquals("/uapi/domestic-stock/v1/trading/order-cash", req.path)
        assertEquals("TTTC0012U", req.getHeader("tr_id")) // 실전 매수 신버전
        assertEquals("Bearer tok", req.getHeader("authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"PDNO\":\"005930\""))
        assertTrue(body.contains("\"EXCG_ID_DVSN_CD\":\"KRX\""))
    }

    @Test
    fun `placeOrder rt_cd not zero throws definitive reject`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"1","msg_cd":"40310000","msg1":"매수가능금액 초과","output":null}""")
                .addHeader("Content-Type", "application/json"),
        )

        val ex = assertThrows(KisApiException::class.java) { runBlocking { client.placeOrder(buyReq) } }
        assertTrue(ex.definitiveReject)
        assertEquals("1", ex.rtCd)
    }

    @Test
    fun `placeOrder HTTP 500 is ambiguous (not definitive)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("err"))

        val ex = assertThrows(KisApiException::class.java) { runBlocking { client.placeOrder(buyReq) } }
        assertEquals(false, ex.definitiveReject)
        assertEquals(500, ex.statusCode)
    }

    @Test
    fun `inquireDailyConclusions parses rows`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"0","output1":[{"odno":"0000117057","pdno":"005930","sll_buy_dvsn_cd":"02","ord_qty":"10","tot_ccld_qty":"10","rmn_qty":"0","cncl_yn":"N","avg_prvs":"70000"}]}""")
                .addHeader("Content-Type", "application/json"),
        )

        val rows = client.inquireDailyConclusions("20260614")

        assertEquals(1, rows.size)
        assertEquals("0000117057", rows[0].odno)
        assertEquals(10, rows[0].executedQty())
        assertEquals(70_000.0, rows[0].avgFillPrice())
    }

    @Test
    fun `getCurrentPrice parses stck_prpr`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"0","output":{"stck_prpr":"70500"}}""")
                .addHeader("Content-Type", "application/json"),
        )

        assertEquals(70_500, client.getCurrentPrice("005930"))
    }

    @Test
    fun `getCurrentPrice throws on rt_cd error (no silent zero)`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"1","output":null}""")
                .addHeader("Content-Type", "application/json"),
        )

        assertThrows(KisApiException::class.java) { runBlocking { client.getCurrentPrice("005930") } }
    }

    @Test
    fun `getHoldings parses output1`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"rt_cd":"0","output1":[{"pdno":"005930","hldg_qty":"15","ord_psbl_qty":"15","pchs_avg_pric":"68000"}],"output2":[{"dnca_tot_amt":"1000000","prvs_rcdl_excc_amt":"950000"}]}""")
                .addHeader("Content-Type", "application/json"),
        )

        val holdings = client.getHoldings()

        assertEquals(1, holdings.size)
        assertEquals("005930", holdings[0].pdno)
        assertEquals(15, holdings[0].heldQty())
        assertEquals(68_000.0, holdings[0].avgBuyPrice())
    }
}
