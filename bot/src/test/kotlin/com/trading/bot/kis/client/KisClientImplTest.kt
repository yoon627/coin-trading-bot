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
