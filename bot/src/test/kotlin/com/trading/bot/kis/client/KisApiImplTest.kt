package com.trading.bot.kis.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.kis.config.KisApiProperties
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 엔드포인트마다 경로·tr_id·필수 파라미터·헤더·응답 파싱, 연속조회. */
class KisApiImplTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() = server.shutdown()

    @Test
    fun `현재가는 시세 tr_id 를 두 환경에서 그대로 쓰고 인증 헤더와 빈 tr_cont 를 보낸다`() = runTest {
        repeat(2) {
            server.enqueue(kisJson("""{"rt_cd":"0","msg_cd":"MCA00000","output":{"stck_prpr":"70500","stck_oprc":"70000","stck_hgpr":"71000","stck_lwpr":"69500"}}"""))
        }

        val quote = server.kisApi(this, KisEnvironment.PAPER).currentPrice("005930", KisLane.HELD_QUOTE)
        server.kisApi(this, KisEnvironment.LIVE).currentPrice("005930", KisLane.QUOTE)

        assertThat(quote).isEqualTo(KisQuote("005930", price = 70_500, open = 70_000, high = 71_000, low = 69_500))
        val paper = server.next()
        assertThat(paper.method).isEqualTo("GET")
        assertThat(paper.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price")
        assertThat(paper.query("FID_COND_MRKT_DIV_CODE")).isEqualTo("J")
        assertThat(paper.query("FID_INPUT_ISCD")).isEqualTo("005930")
        assertThat(paper.getHeader("tr_id")).isEqualTo("FHKST01010100")
        assertThat(paper.getHeader("authorization")).isEqualTo("Bearer tok-1")
        assertThat(paper.getHeader("appkey")).isEqualTo(TEST_ACCESS.appKey)
        assertThat(paper.getHeader("appsecret")).isEqualTo(TEST_ACCESS.appSecret)
        assertThat(paper.getHeader("custtype")).isEqualTo("P")
        assertThat(paper.getHeader("tr_cont")).isEqualTo("")
        assertThat(server.next().getHeader("tr_id")).isEqualTo("FHKST01010100")
    }

    @Test
    fun `현재가가 0 이거나 숫자가 아니면 Malformed 이고 값은 메시지에 넣지 않는다`() = runTest {
        server.enqueue(kisJson("""{"rt_cd":"0","output":{"stck_prpr":"0","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0"}}"""))
        server.enqueue(kisJson("""{"rt_cd":"0","output":{"stck_prpr":"7O500","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0"}}"""))
        val api = server.kisApi(this)

        assertThrows<KisMalformedResponseException> { api.currentPrice("005930", KisLane.QUOTE) }
        val notNumber = assertThrows<KisMalformedResponseException> { api.currentPrice("005930", KisLane.QUOTE) }
        assertThat(notNumber.message).contains("stck_prpr").doesNotContain("7O500")
    }

    @Test
    fun `일봉은 기간·일봉·수정주가 파라미터를 보내고 최신 봉을 앞에 둔다`() = runTest {
        val row = { date: String, close: Int ->
            """{"stck_bsop_date":"$date","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_clpr":"$close","acml_vol":"1000","acml_tr_pbmn":"100000"}"""
        }
        server.enqueue(kisJson("""{"rt_cd":"0","output1":{},"output2":[${row("20261015", 1)},${row("20261020", 3)},${row("20261016", 2)}]}"""))
        server.enqueue(kisJson("""{"rt_cd":"0","output1":{},"output2":[]}"""))
        val api = server.kisApi(this)

        val candles = api.dailyCandles("005930", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 20), lane = KisLane.QUERY)
        api.dailyCandles("005930", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 20), adjusted = false, lane = KisLane.QUERY)

        assertThat(candles.map { it.date }).containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 15))
        assertThat(candles[0]).isEqualTo(KisDailyCandle(LocalDate.of(2026, 10, 20), 100, 110, 90, 3, 1000))
        val adjusted = server.next()
        assertThat(adjusted.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
        assertThat(adjusted.getHeader("tr_id")).isEqualTo("FHKST03010100")
        assertThat(adjusted.query("FID_INPUT_DATE_1")).isEqualTo("20260920")
        assertThat(adjusted.query("FID_INPUT_DATE_2")).isEqualTo("20261020")
        assertThat(adjusted.query("FID_PERIOD_DIV_CODE")).isEqualTo("D")
        assertThat(adjusted.query("FID_ORG_ADJ_PRC")).isEqualTo("0")
        assertThat(server.next().query("FID_ORG_ADJ_PRC")).isEqualTo("1")
    }

    @Test
    fun `잔고는 모의 tr_id·조회 파라미터를 보내고 수량 0 인 행과 계좌 요약을 그대로 돌려준다`() = runTest {
        server.enqueue(
            kisJson(
                """{"rt_cd":"0","ctx_area_fk100":"","ctx_area_nk100":"",
                   "output1":[
                     {"pdno":"005930","prdt_name":"삼성전자","hldg_qty":"15","ord_psbl_qty":"10","pchs_avg_pric":"68000.5000"},
                     {"pdno":"000660","prdt_name":"SK하이닉스","hldg_qty":"0","ord_psbl_qty":"0","pchs_avg_pric":"0.0000"}],
                   "output2":[{"dnca_tot_amt":"1000000","nxdy_excc_amt":"990000","prvs_rcdl_excc_amt":"950000","tot_evlu_amt":"2020000"}]}""",
                trCont = "D",
            ),
        )

        val balance = server.kisApi(this, KisEnvironment.PAPER).balance(KisLane.RECONCILE)

        assertThat(balance.pages).isEqualTo(1)
        assertThat(balance.holdings).containsExactly(
            KisHolding("005930", "삼성전자", holdingQty = 15, orderableQty = 10, avgPrice = BigDecimal("68000.5000")),
            KisHolding("000660", "SK하이닉스", holdingQty = 0, orderableQty = 0, avgPrice = BigDecimal("0.0000")),
        )
        assertThat(balance.summary).isEqualTo(KisAccountSummary(1_000_000, 990_000, 950_000, 2_020_000))
        val request = server.next()
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance")
        assertThat(request.getHeader("tr_id")).isEqualTo("VTTC8434R")
        assertThat(
            listOf("CANO", "ACNT_PRDT_CD", "AFHR_FLPR_YN", "OFL_YN", "INQR_DVSN", "UNPR_DVSN", "FUND_STTL_ICLD_YN", "FNCG_AMT_AUTO_RDPT_YN", "PRCS_DVSN", "CTX_AREA_FK100", "CTX_AREA_NK100")
                .map { it to request.query(it) },
        ).containsExactly(
            "CANO" to "12345678", "ACNT_PRDT_CD" to "01", "AFHR_FLPR_YN" to "N", "OFL_YN" to "", "INQR_DVSN" to "02",
            "UNPR_DVSN" to "01", "FUND_STTL_ICLD_YN" to "N", "FNCG_AMT_AUTO_RDPT_YN" to "N", "PRCS_DVSN" to "00",
            "CTX_AREA_FK100" to "", "CTX_AREA_NK100" to "",
        )
    }

    @Test
    fun `잔고 연속조회는 F·M 이면 tr_cont N 과 받은 ctx 값으로 다음 페이지를 읽는다`() = runTest {
        server.enqueue(kisJson(balancePage("005930", fk = "FK^1 ", nk = "NK^1 ", deposit = 1), trCont = "M"))
        server.enqueue(kisJson(balancePage("000660", fk = "FK^2", nk = "NK^2", deposit = null), trCont = "F"))
        server.enqueue(kisJson(balancePage("035420", fk = "", nk = "", deposit = 3), trCont = "D"))

        val balance = server.kisApi(this, KisEnvironment.LIVE).balance(KisLane.RECONCILE)

        assertThat(balance.holdings.map { it.code }).containsExactly("005930", "000660", "035420")
        assertThat(balance.pages).isEqualTo(3)
        assertThat(balance.summary.depositTotal).isEqualTo(3)
        val first = server.next()
        assertThat(first.getHeader("tr_id")).isEqualTo("TTTC8434R")
        assertThat(first.getHeader("tr_cont")).isEqualTo("")
        val second = server.next()
        assertThat(second.getHeader("tr_cont")).isEqualTo("N")
        assertThat(second.query("CTX_AREA_FK100")).isEqualTo("FK^1 ")
        assertThat(second.query("CTX_AREA_NK100")).isEqualTo("NK^1 ")
        assertThat(server.next().query("CTX_AREA_NK100")).isEqualTo("NK^2")
    }

    @Test
    fun `잔고 중간 페이지가 실패하면 부분 결과 없이 예외다`() = runTest {
        server.enqueue(kisJson(balancePage("005930", fk = "FK", nk = "NK"), trCont = "M"))
        server.enqueue(kisJson("""{"rt_cd":"1","msg_cd":"OPSQ0001","msg1":"조회 오류"}"""))

        val error = assertThrows<KisRejectedException> { server.kisApi(this).balance(KisLane.RECONCILE) }

        assertThat(error.msgCd).isEqualTo("OPSQ0001")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `연속조회가 페이지 상한 안에 끝나지 않으면 Incomplete 다`() = runTest {
        repeat(2) { server.enqueue(kisJson(balancePage("005930", fk = "FK", nk = "NK"), trCont = "M")) }

        assertThrows<KisIncompleteResponseException> {
            server.kisApi(this, properties = KisApiProperties(maxContinuationPages = 2)).balance(KisLane.RECONCILE)
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `매수가능은 시장가 구분으로 조회하고 미수없는 매수수량을 읽는다`() = runTest {
        server.enqueue(
            kisJson(
                """{"rt_cd":"0","output":{"ord_psbl_cash":"1000000","nrcvb_buy_amt":"840000","nrcvb_buy_qty":"12",
                   "max_buy_amt":"900000","max_buy_qty":"13","psbl_qty_calc_unpr":"70000"}}""",
            ),
        )

        val buyable = server.kisApi(this, KisEnvironment.PAPER).buyable("005930", 70_000, KisLane.ORDER)

        assertThat(buyable).isEqualTo(KisBuyable(1_000_000, 840_000, 12, 900_000, 13))
        val request = server.next()
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/trading/inquire-psbl-order")
        assertThat(request.getHeader("tr_id")).isEqualTo("VTTC8908R")
        assertThat(request.query("PDNO")).isEqualTo("005930")
        assertThat(request.query("ORD_UNPR")).isEqualTo("70000")
        assertThat(request.query("ORD_DVSN")).isEqualTo("01")
        assertThat(request.query("CMA_EVLU_AMT_ICLD_YN")).isEqualTo("N")
        assertThat(request.query("OVRS_ICLD_YN")).isEqualTo("N")
    }

    @Test
    fun `현금주문은 매수·매도 tr_id 와 대문자 body 를 보내고 ODNO 를 돌려준다`() = runTest {
        repeat(2) {
            server.enqueue(kisJson("""{"rt_cd":"0","msg_cd":"APBK0013","msg1":"주문 전송 완료","output":{"KRX_FWDG_ORD_ORGNO":"91252","ODNO":"0000117057","ORD_TMD":"101010"}}"""))
        }
        val api = server.kisApi(this, KisEnvironment.PAPER)

        val ack = api.placeCashOrder(KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.LIMIT, qty = 3, limitPrice = 70_000))
        api.placeCashOrder(KisCashOrderRequest("005930", KisSide.SELL, KisOrderType.MARKET, qty = 2))

        assertThat(ack).isEqualTo(KisOrderAck("0000117057", "91252", "101010"))
        val buy = server.next()
        assertThat(buy.method).isEqualTo("POST")
        assertThat(buy.path).isEqualTo("/uapi/domestic-stock/v1/trading/order-cash")
        assertThat(buy.getHeader("tr_id")).isEqualTo("VTTC0012U")
        assertThat(buy.bodyMap()).isEqualTo(
            mapOf(
                "CANO" to "12345678", "ACNT_PRDT_CD" to "01", "PDNO" to "005930", "ORD_DVSN" to "00", "ORD_QTY" to "3",
                "ORD_UNPR" to "70000", "EXCG_ID_DVSN_CD" to "KRX", "SLL_TYPE" to "", "CNDT_PRIC" to "",
            ),
        )
        val sell = server.next()
        assertThat(sell.getHeader("tr_id")).isEqualTo("VTTC0011U")
        assertThat(sell.bodyMap()).containsEntry("ORD_DVSN", "01").containsEntry("ORD_UNPR", "0").containsEntry("SLL_TYPE", "01")
    }

    @Test
    fun `실전 매수가능·주문·일별 주문 조회 tr_id 는 T 로 시작한다`() = runTest {
        repeat(2) { server.enqueue(kisJson("""{"rt_cd":"0","output":{"ODNO":"1"}}""")) }
        server.enqueue(
            kisJson("""{"rt_cd":"0","output":{"ord_psbl_cash":"1","nrcvb_buy_amt":"1","nrcvb_buy_qty":"1","max_buy_amt":"1","max_buy_qty":"1"}}"""),
        )
        server.enqueue(kisJson(ordersPage("1", fk = "", nk = "")))
        val api = server.kisApi(this, KisEnvironment.LIVE)

        api.placeCashOrder(KisCashOrderRequest("005930", KisSide.BUY, KisOrderType.MARKET, qty = 1))
        api.placeCashOrder(KisCashOrderRequest("005930", KisSide.SELL, KisOrderType.MARKET, qty = 1))
        api.buyable("005930", 70_000, KisLane.ORDER)
        api.dailyOrders(LocalDate.of(2026, 10, 21), lane = KisLane.RECONCILE)

        assertThat((1..4).map { server.next().getHeader("tr_id") }).containsExactly("TTTC0012U", "TTTC0011U", "TTTC8908R", "TTTC0081R")
    }

    @Test
    fun `일별 주문 조회는 하루·ODNO 필터로 묻고 연속조회로 모든 행을 읽는다`() = runTest {
        server.enqueue(kisJson(ordersPage("0000000001", fk = "F1", nk = "N1"), trCont = "F"))
        server.enqueue(kisJson(ordersPage("0000000002", fk = "", nk = ""), trCont = "E"))

        val rows = server.kisApi(this, KisEnvironment.PAPER).dailyOrders(LocalDate.of(2026, 10, 21), odno = "0000000001", lane = KisLane.RECONCILE)

        assertThat(rows.map { it.odno }).containsExactly("0000000001", "0000000002")
        assertThat(rows[0]).isEqualTo(
            KisOrderRow(
                orderDate = LocalDate.of(2026, 10, 21), odno = "0000000001", originalOdno = null, side = KisSide.BUY, code = "005930",
                orderQty = 10, orderPrice = BigDecimal("0"), orderTime = "090105", filledQty = 7, avgPrice = BigDecimal("70100.5"),
                filledAmount = 490_700, remainingQty = 3, rejectedQty = 0, cancelConfirmedQty = 0, cancelled = false, orderDivisionCode = "01",
            ),
        )
        val first = server.next()
        assertThat(first.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
        assertThat(first.getHeader("tr_id")).isEqualTo("VTTC0081R")
        assertThat(
            listOf("INQR_STRT_DT", "INQR_END_DT", "SLL_BUY_DVSN_CD", "CCLD_DVSN", "INQR_DVSN", "INQR_DVSN_3", "ODNO", "EXCG_ID_DVSN_CD")
                .map { first.query(it) },
        ).containsExactly("20261021", "20261021", "00", "00", "00", "00", "0000000001", "KRX")
        val second = server.next()
        assertThat(second.getHeader("tr_cont")).isEqualTo("N")
        assertThat(second.query("CTX_AREA_FK100")).isEqualTo("F1")
        assertThat(second.query("CTX_AREA_NK100")).isEqualTo("N1")
    }

    @Test
    fun `휴장일 조회는 다음 페이지가 있어도 한 번만 부르고 Y·N 을 읽는다`() = runTest {
        server.enqueue(kisJson(holidayPage("20261021", "Y", fk = "HF", nk = "HN"), trCont = "M"))
        server.enqueue(kisJson(holidayPage("20261022", "N", fk = "", nk = "")))

        val rows = server.kisApi(this, KisEnvironment.LIVE).holidays(LocalDate.of(2026, 10, 21), KisLane.QUERY)
        val paperRows = server.kisApi(this, KisEnvironment.PAPER).holidays(LocalDate.of(2026, 10, 21), KisLane.QUERY)

        assertThat(rows).containsExactly(
            KisHolidayRow(LocalDate.of(2026, 10, 21), "04", businessDay = true, tradingDay = true, openDay = true, settlementDay = true),
        )
        assertThat(paperRows).containsExactly(
            KisHolidayRow(LocalDate.of(2026, 10, 22), "04", businessDay = false, tradingDay = false, openDay = false, settlementDay = false),
        )
        val first = server.next()
        assertThat(first.requestUrl!!.encodedPath).isEqualTo("/uapi/domestic-stock/v1/quotations/chk-holiday")
        assertThat(first.getHeader("tr_id")).isEqualTo("CTCA0903R")
        assertThat(first.query("BASS_DT")).isEqualTo("20261021")
        assertThat(first.query("CTX_AREA_FK")).isEqualTo("")
        assertThat(first.query("CTX_AREA_NK")).isEqualTo("")
        // 두 번째 요청은 다음 페이지가 아니라 PAPER 호출이다.
        val second = server.next()
        assertThat(second.getHeader("tr_id")).isEqualTo("VTCA0903R")
        assertThat(second.query("CTX_AREA_FK")).isEqualTo("")
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** [deposit] 이 null 이면 그 페이지의 계좌 요약(output2)은 빈 배열이다. */
    private fun balancePage(code: String, fk: String, nk: String, deposit: Long? = 1) =
        """{"rt_cd":"0","ctx_area_fk100":"$fk","ctx_area_nk100":"$nk",
           "output1":[{"pdno":"$code","hldg_qty":"1","ord_psbl_qty":"1","pchs_avg_pric":"100"}],
           "output2":[${deposit?.let { """{"dnca_tot_amt":"$it","nxdy_excc_amt":"1","prvs_rcdl_excc_amt":"1","tot_evlu_amt":"1"}""" } ?: ""}]}"""

    private fun ordersPage(odno: String, fk: String, nk: String) =
        """{"rt_cd":"0","ctx_area_fk100":"$fk","ctx_area_nk100":"$nk","output2":{"tot_ord_qty":"10"},
           "output1":[{"ord_dt":"20261021","ord_gno_brno":"","odno":"$odno","orgn_odno":"","ord_dvsn_name":"시장가",
             "sll_buy_dvsn_cd":"02","pdno":"005930","ord_qty":"10","ord_unpr":"0","ord_tmd":"090105","tot_ccld_qty":"7",
             "avg_prvs":"70100.5","cncl_yn":"N","tot_ccld_amt":"490700","rmn_qty":"3","rjct_qty":"0","cnc_cfrm_qty":"0","ord_dvsn_cd":"01"}]}"""

    private fun holidayPage(date: String, yn: String, fk: String, nk: String) =
        """{"rt_cd":"0","ctx_area_fk":"$fk","ctx_area_nk":"$nk",
           "output":[{"bass_dt":"$date","wday_dvsn_cd":"04","bzdy_yn":"$yn","tr_day_yn":"$yn","opnd_yn":"$yn","sttl_day_yn":"$yn"}]}"""

    @Suppress("UNCHECKED_CAST")
    private fun okhttp3.mockwebserver.RecordedRequest.bodyMap(): Map<String, String> =
        ObjectMapper().readValue(body.readUtf8(), Map::class.java) as Map<String, String>
}
