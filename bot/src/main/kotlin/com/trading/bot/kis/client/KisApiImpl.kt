package com.trading.bot.kis.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.kis.config.KisApiProperties
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient

/**
 * [KisApi] 의 WebClient 구현. 요청마다 [tokens] 의 토큰을 받고 [limiter] 허가를 받은 뒤 보낸다(조회 재시도마다 다시 받는다).
 * backoff 대기는 [sleep] 으로 해 가상 시간 테스트가 가능하다.
 */
class KisApiImpl(
    private val environment: KisEnvironment,
    private val access: KisAccess,
    private val webClient: WebClient,
    private val tokens: KisTokenSource,
    private val limiter: KisRateLimiter,
    private val properties: KisApiProperties,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : KisApi {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Endpoint(val name: String, val path: String, val liveTrId: String)

    private class Reply(val status: Int, val trCont: String?, val body: JsonNode)

    override suspend fun currentPrice(code: String, lane: KisLane): KisQuote {
        val reply = query(
            CURRENT_PRICE, lane,
            mapOf("FID_COND_MRKT_DIV_CODE" to MARKET_KRX, "FID_INPUT_ISCD" to code),
        )
        val p = Parser(CURRENT_PRICE, reply.status)
        val output = p.obj(reply.body, "output")
        val price = p.long(output, "stck_prpr")
        if (price <= 0) throw KisMalformedResponseException(CURRENT_PRICE.name, "stck_prpr 가 0 이하다", reply.status)
        return KisQuote(
            code = code,
            price = price,
            open = p.nonNegativeLong(output, "stck_oprc"),
            high = p.nonNegativeLong(output, "stck_hgpr"),
            low = p.nonNegativeLong(output, "stck_lwpr"),
        )
    }

    override suspend fun dailyCandles(code: String, from: LocalDate, to: LocalDate, adjusted: Boolean, lane: KisLane): List<KisDailyCandle> {
        val reply = query(
            DAILY_CANDLES, lane,
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to MARKET_KRX,
                "FID_INPUT_ISCD" to code,
                "FID_INPUT_DATE_1" to from.format(DATE),
                "FID_INPUT_DATE_2" to to.format(DATE),
                "FID_PERIOD_DIV_CODE" to "D",
                // 0 수정주가, 1 원주가.
                "FID_ORG_ADJ_PRC" to if (adjusted) "0" else "1",
            ),
        )
        val p = Parser(DAILY_CANDLES, reply.status)
        return p.array(reply.body, "output2").map { row ->
            KisDailyCandle(
                date = p.date(row, "stck_bsop_date"),
                open = p.nonNegativeLong(row, "stck_oprc"),
                high = p.nonNegativeLong(row, "stck_hgpr"),
                low = p.nonNegativeLong(row, "stck_lwpr"),
                close = p.nonNegativeLong(row, "stck_clpr"),
                volume = p.nonNegativeLong(row, "acml_vol"),
            )
        }.sortedByDescending { it.date }
    }

    override suspend fun balance(lane: KisLane): KisBalance {
        val params = mapOf(
            "CANO" to access.cano,
            "ACNT_PRDT_CD" to access.productCode,
            "AFHR_FLPR_YN" to "N",
            "OFL_YN" to "",
            // 02 종목별.
            "INQR_DVSN" to "02",
            "UNPR_DVSN" to "01",
            "FUND_STTL_ICLD_YN" to "N",
            "FNCG_AMT_AUTO_RDPT_YN" to "N",
            // 00 전일매매 포함.
            "PRCS_DVSN" to "00",
        )
        val p = Parser(BALANCE, null)
        val holdings = mutableListOf<KisHolding>()
        var summary: JsonNode? = null
        val pages = paged(BALANCE, lane, params) { reply ->
            val pp = Parser(BALANCE, reply.status)
            pp.array(reply.body, "output1").mapTo(holdings) { row ->
                KisHolding(
                    code = pp.text(row, "pdno"),
                    name = pp.optionalText(row, "prdt_name"),
                    holdingQty = pp.nonNegativeLong(row, "hldg_qty"),
                    orderableQty = pp.nonNegativeLong(row, "ord_psbl_qty"),
                    avgPrice = pp.decimal(row, "pchs_avg_pric"),
                )
            }
            // 계좌 요약이 모든 페이지에 오는지 확인하지 못해, 빈 페이지는 건너뛰고 마지막으로 받은 요약을 쓴다.
            if (reply.body.has("output2")) pp.rows(reply.body, "output2").lastOrNull()?.let { summary = it }
        }
        val s = summary ?: throw KisMalformedResponseException(BALANCE.name, "output2 없음")
        return KisBalance(
            holdings = holdings,
            summary = KisAccountSummary(
                depositTotal = p.long(s, "dnca_tot_amt"),
                nextDaySettlement = p.long(s, "nxdy_excc_amt"),
                d2Settlement = p.long(s, "prvs_rcdl_excc_amt"),
                totalEvaluation = p.long(s, "tot_evlu_amt"),
            ),
            pages = pages,
        )
    }

    override suspend fun buyable(code: String, price: Long, lane: KisLane): KisBuyable {
        val reply = query(
            BUYABLE, lane,
            mapOf(
                "CANO" to access.cano,
                "ACNT_PRDT_CD" to access.productCode,
                "PDNO" to code,
                "ORD_UNPR" to price.toString(),
                // 지정가(00)로 조회하면 종목 증거금률이 반영되지 않아 수량이 크게 나온다.
                "ORD_DVSN" to KisOrderType.MARKET.ordDvsn,
                "CMA_EVLU_AMT_ICLD_YN" to "N",
                "OVRS_ICLD_YN" to "N",
            ),
        )
        val p = Parser(BUYABLE, reply.status)
        val output = p.obj(reply.body, "output")
        return KisBuyable(
            orderableCash = p.long(output, "ord_psbl_cash"),
            noReceivableBuyAmount = p.long(output, "nrcvb_buy_amt"),
            noReceivableBuyQty = p.nonNegativeLong(output, "nrcvb_buy_qty"),
            maxBuyAmount = p.long(output, "max_buy_amt"),
            maxBuyQty = p.nonNegativeLong(output, "max_buy_qty"),
        )
    }

    override suspend fun placeCashOrder(request: KisCashOrderRequest): KisOrderAck {
        val endpoint = if (request.side == KisSide.BUY) ORDER_BUY else ORDER_SELL
        limiter.checkOpen(endpoint.name)
        val token = tokens.token()
        limiter.acquire(KisLane.ORDER, endpoint.name)
        val body = mapOf(
            "CANO" to access.cano,
            "ACNT_PRDT_CD" to access.productCode,
            "PDNO" to request.code,
            "ORD_DVSN" to request.type.ordDvsn,
            "ORD_QTY" to request.qty.toString(),
            "ORD_UNPR" to (request.limitPrice ?: 0L).toString(),
            "EXCG_ID_DVSN_CD" to EXCHANGE_KRX,
            // 01 일반매도. 매수에는 비운다.
            "SLL_TYPE" to if (request.side == KisSide.SELL) "01" else "",
            "CNDT_PRIC" to "",
        )
        val response = try {
            webClient.post()
                .uri(endpoint.path)
                .headers { it.authorize(token, endpoint, trCont = "") }
                .bodyValue(body)
                .exchangeToMono { it.toEntity(String::class.java) }
                .awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("KIS {} outcome unknown: transport failure {}", endpoint.name, e.javaClass.simpleName)
            throw KisOutcomeUnknownException(endpoint.name, "전송 중 실패 (${e.javaClass.simpleName})")
        }
        val status = response.statusCode.value()
        val json = readJson(response)
        val rtCd = json?.textOrNull("rt_cd")
        val msgCd = json?.textOrNull("msg_cd")
        val msg1 = json?.textOrNull("msg1")
        if (msgCd in TOKEN_ERROR_CODES) tokens.invalidate(token)
        // 비 2xx 는 본문에 msg_cd 가 있어도 접수되지 않았다고 단정하지 않는다. 유량 초과는 상태와 무관하게 미접수가 확인되지 않았다.
        if (!response.statusCode.is2xxSuccessful || json == null || rtCd == null || msgCd == RATE_LIMITED) {
            log.warn("KIS {} outcome unknown: http={} msg_cd={}", endpoint.name, status, msgCd)
            throw KisOutcomeUnknownException(endpoint.name, "응답으로 접수 여부를 알 수 없다", status, msgCd, msg1)
        }
        if (rtCd != SUCCESS) {
            log.warn("KIS {} rejected: http={} rt_cd={} msg_cd={} msg1={}", endpoint.name, status, rtCd, msgCd, msg1)
            throw KisRejectedException(endpoint.name, status, rtCd, msgCd, msg1)
        }
        val output = json.get("output")
        val odno = output?.textOrNull("ODNO")
            ?: throw KisOutcomeUnknownException(endpoint.name, "rt_cd=0 인데 ODNO 가 없다", status, msgCd, msg1)
        return KisOrderAck(
            odno = odno,
            orgNo = output.textOrNull("KRX_FWDG_ORD_ORGNO"),
            orderTime = output.textOrNull("ORD_TMD"),
        )
    }

    override suspend fun dailyOrders(date: LocalDate, odno: String?, lane: KisLane): List<KisOrderRow> {
        val day = date.format(DATE)
        val params = mapOf(
            "CANO" to access.cano,
            "ACNT_PRDT_CD" to access.productCode,
            "INQR_STRT_DT" to day,
            "INQR_END_DT" to day,
            // 00 전체(매도·매수).
            "SLL_BUY_DVSN_CD" to "00",
            "PDNO" to "",
            // 00 전체(체결·미체결).
            "CCLD_DVSN" to "00",
            "INQR_DVSN" to "00",
            "INQR_DVSN_3" to "00",
            "ORD_GNO_BRNO" to "",
            "ODNO" to (odno ?: ""),
            "INQR_DVSN_1" to "",
            "EXCG_ID_DVSN_CD" to EXCHANGE_KRX,
        )
        val rows = mutableListOf<KisOrderRow>()
        paged(DAILY_ORDERS, lane, params) { reply ->
            val p = Parser(DAILY_ORDERS, reply.status)
            p.array(reply.body, "output1").mapTo(rows) { row -> orderRow(p, row) }
        }
        return rows
    }

    override suspend fun holidays(baseDate: LocalDate, lane: KisLane): List<KisHolidayRow> {
        // 원장 서비스라 단시간 다수 호출을 KIS 가 막아 달라고 한다. 연속조회를 따라가지 않고 한 번만 부른다.
        val reply = query(HOLIDAYS, lane, mapOf("BASS_DT" to baseDate.format(DATE), "CTX_AREA_FK" to "", "CTX_AREA_NK" to ""))
        val p = Parser(HOLIDAYS, reply.status)
        return p.rows(reply.body, "output").map { row ->
            KisHolidayRow(
                date = p.date(row, "bass_dt"),
                weekdayCode = p.optionalText(row, "wday_dvsn_cd"),
                businessDay = p.yesNo(row, "bzdy_yn"),
                tradingDay = p.yesNo(row, "tr_day_yn"),
                openDay = p.yesNo(row, "opnd_yn"),
                settlementDay = p.yesNo(row, "sttl_day_yn"),
            )
        }
    }

    private fun orderRow(p: Parser, row: JsonNode): KisOrderRow {
        val sideCode = p.text(row, "sll_buy_dvsn_cd")
        val side = KisSide.entries.firstOrNull { it.code == sideCode }
            ?: throw KisMalformedResponseException(DAILY_ORDERS.name, "sll_buy_dvsn_cd 가 01·02 가 아니다", p.status)
        return KisOrderRow(
            orderDate = p.date(row, "ord_dt"),
            odno = p.text(row, "odno"),
            originalOdno = p.optionalText(row, "orgn_odno"),
            side = side,
            code = p.text(row, "pdno"),
            orderQty = p.nonNegativeLong(row, "ord_qty"),
            orderPrice = p.optionalDecimal(row, "ord_unpr"),
            orderTime = p.optionalText(row, "ord_tmd"),
            filledQty = p.nonNegativeLong(row, "tot_ccld_qty"),
            avgPrice = p.decimal(row, "avg_prvs"),
            filledAmount = p.optionalLong(row, "tot_ccld_amt"),
            remainingQty = p.nonNegativeLong(row, "rmn_qty"),
            rejectedQty = p.optionalLong(row, "rjct_qty"),
            cancelConfirmedQty = p.optionalLong(row, "cnc_cfrm_qty"),
            cancelled = p.yesNo(row, "cncl_yn"),
            orderDivisionCode = p.optionalText(row, "ord_dvsn_cd"),
        )
    }

    /**
     * 응답 헤더 tr_cont 가 F·M 이면 다음 페이지가 있다. 페이지 상한을 넘거나 중간에 실패하면 부분 결과 없이 예외다.
     * 다음 페이지 키는 응답 body 의 소문자 필드로 오고 요청에는 대문자 파라미터로 보낸다.
     */
    private suspend fun paged(endpoint: Endpoint, lane: KisLane, params: Map<String, String>, onPage: (Reply) -> Unit): Int {
        var fk = ""
        var nk = ""
        for (page in 1..properties.maxContinuationPages) {
            val reply = query(endpoint, lane, params + mapOf(CTX_FK to fk, CTX_NK to nk), trCont = if (page == 1) "" else "N")
            onPage(reply)
            if (reply.trCont != "F" && reply.trCont != "M") return page
            fk = reply.body.rawText(CTX_FK.lowercase())
                ?: throw KisMalformedResponseException(endpoint.name, "다음 페이지가 있는데 ${CTX_FK.lowercase()} 가 없다", reply.status)
            nk = reply.body.rawText(CTX_NK.lowercase())
                ?: throw KisMalformedResponseException(endpoint.name, "다음 페이지가 있는데 ${CTX_NK.lowercase()} 가 없다", reply.status)
        }
        throw KisIncompleteResponseException(endpoint.name, "연속조회 ${properties.maxContinuationPages} 페이지 안에 끝나지 않았다")
    }

    /** rt_cd=0 응답만 돌려준다. 재시도 규칙은 [KisApi] 문서와 같다. */
    private suspend fun query(endpoint: Endpoint, lane: KisLane, params: Map<String, String>, trCont: String = ""): Reply {
        var attempt = 0
        var tokenRetried = false
        while (true) {
            attempt++
            limiter.checkOpen(endpoint.name)
            val token = tokens.token()
            limiter.acquire(lane, endpoint.name)
            val retry: KisTransientException = try {
                val response = webClient.get()
                    .uri { b ->
                        b.path(endpoint.path)
                        params.keys.forEach { b.queryParam(it, "{$it}") }
                        b.build(params)
                    }
                    .headers { it.authorize(token, endpoint, trCont) }
                    .exchangeToMono { it.toEntity(String::class.java) }
                    .awaitSingle()
                val status = response.statusCode.value()
                val json = readJson(response)
                val rtCd = json?.textOrNull("rt_cd")
                val msgCd = json?.textOrNull("msg_cd")
                val msg1 = json?.textOrNull("msg1")
                when {
                    msgCd in TOKEN_ERROR_CODES -> {
                        tokens.invalidate(token)
                        if (tokenRetried) throw KisRejectedException(endpoint.name, status, rtCd, msgCd, msg1, "토큰을 새로 받아도 토큰 오류")
                        tokenRetried = true
                        attempt--
                        log.warn("KIS {} token rejected (msg_cd={}), retrying once with a new token", endpoint.name, msgCd)
                        continue
                    }
                    msgCd == RATE_LIMITED ->
                        KisTransientException(endpoint.name, throttled = true, reason = "유량 초과", httpStatus = status, msgCd = msgCd, msg1 = msg1)
                    response.statusCode.is5xxServerError ->
                        KisTransientException(endpoint.name, throttled = false, reason = "서버 오류", httpStatus = status, msgCd = msgCd, msg1 = msg1)
                    !response.statusCode.is2xxSuccessful ->
                        throw KisRejectedException(endpoint.name, status, rtCd, msgCd, msg1, "HTTP 오류")
                    json == null -> throw KisMalformedResponseException(endpoint.name, "본문이 JSON 이 아니다", status)
                    rtCd == null -> throw KisMalformedResponseException(endpoint.name, "rt_cd 없음", status)
                    rtCd != SUCCESS -> throw KisRejectedException(endpoint.name, status, rtCd, msgCd, msg1)
                    else -> return Reply(status, response.headers.getFirst(TR_CONT), json)
                }
            } catch (e: KisException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KisTransientException(endpoint.name, throttled = false, reason = "전송 실패 (${e.javaClass.simpleName})")
            }
            if (attempt >= properties.queryMaxAttempts) {
                throw KisTransientException(
                    endpoint.name, retry.throttled, "재시도 ${properties.queryMaxAttempts}회 소진 — ${retry.reason}",
                    retry.httpStatus, retry.msgCd, retry.msg1,
                )
            }
            val backoffMs = properties.queryRetryInitialBackoffMs shl (attempt - 1)
            log.warn(
                "KIS {} attempt {}/{} failed ({}), retrying in {}ms",
                endpoint.name, attempt, properties.queryMaxAttempts, retry.reason, backoffMs,
            )
            sleep(backoffMs)
        }
    }

    private fun HttpHeaders.authorize(token: String, endpoint: Endpoint, trCont: String) {
        set("authorization", "Bearer $token")
        set("appkey", access.appKey)
        set("appsecret", access.appSecret)
        set("tr_id", environment.trId(endpoint.liveTrId))
        set("custtype", properties.custType)
        set(TR_CONT, trCont)
        contentType = MediaType.APPLICATION_JSON
    }

    private fun readJson(response: ResponseEntity<String>): JsonNode? =
        response.body?.takeIf { it.isNotBlank() }?.let { runCatching { MAPPER.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }

    /** 필드 값은 예외 메시지에 넣지 않는다. */
    private class Parser(val endpoint: Endpoint, val status: Int?) {
        fun malformed(reason: String) = KisMalformedResponseException(endpoint.name, reason, status)

        fun obj(body: JsonNode, field: String): JsonNode =
            body.get(field)?.takeIf { it.isObject } ?: throw malformed("$field 객체가 없다")

        fun array(body: JsonNode, field: String): List<JsonNode> {
            val node = body.get(field)?.takeIf { it.isArray } ?: throw malformed("$field 배열이 없다")
            return node.toList()
        }

        /** 배열이나 객체 하나로 오는 필드. */
        fun rows(body: JsonNode, field: String): List<JsonNode> {
            val node = body.get(field) ?: throw malformed("$field 가 없다")
            return when {
                node.isArray -> node.toList()
                node.isObject -> listOf(node)
                else -> throw malformed("$field 가 배열·객체가 아니다")
            }
        }

        fun text(row: JsonNode, field: String): String = optionalText(row, field) ?: throw malformed("$field 가 비었다")

        fun optionalText(row: JsonNode, field: String): String? = row.textOrNull(field)

        fun decimal(row: JsonNode, field: String): BigDecimal = optionalDecimal(row, field) ?: throw malformed("$field 가 비었다")

        fun optionalDecimal(row: JsonNode, field: String): BigDecimal? {
            val text = optionalText(row, field) ?: return null
            return text.toBigDecimalOrNull() ?: throw malformed("$field 가 숫자가 아니다")
        }

        fun long(row: JsonNode, field: String): Long = optionalLong(row, field) ?: throw malformed("$field 가 비었다")

        fun nonNegativeLong(row: JsonNode, field: String): Long =
            long(row, field).also { if (it < 0) throw malformed("$field 가 음수다") }

        fun optionalLong(row: JsonNode, field: String): Long? {
            val value = optionalDecimal(row, field) ?: return null
            return try {
                value.stripTrailingZeros().longValueExact()
            } catch (_: ArithmeticException) {
                throw malformed("$field 가 정수가 아니다")
            }
        }

        fun yesNo(row: JsonNode, field: String): Boolean = when (text(row, field).uppercase()) {
            "Y" -> true
            "N" -> false
            else -> throw malformed("$field 가 Y·N 이 아니다")
        }

        fun date(row: JsonNode, field: String): LocalDate = try {
            LocalDate.parse(text(row, field), DATE)
        } catch (_: DateTimeParseException) {
            throw malformed("$field 가 yyyyMMdd 가 아니다")
        }
    }

    private companion object {
        const val SUCCESS = "0"
        const val TR_CONT = "tr_cont"
        const val MARKET_KRX = "J"
        const val EXCHANGE_KRX = "KRX"

        // 초당 거래건수 초과. HTTP 상태가 확인되지 않아 msg_cd 로만 판정한다.
        const val RATE_LIMITED = "EGW00201"

        // 토큰 만료·무효. 공식 샘플에서 확인하지 못한 값이라 모의 스모크로 확인할 때까지 이 한 곳에만 둔다.
        val TOKEN_ERROR_CODES = setOf("EGW00123", "EGW00121")

        const val CTX_FK = "CTX_AREA_FK100"
        const val CTX_NK = "CTX_AREA_NK100"

        const val QUOTATIONS = "/uapi/domestic-stock/v1/quotations"
        const val TRADING = "/uapi/domestic-stock/v1/trading"
        val CURRENT_PRICE = Endpoint("current-price", "$QUOTATIONS/inquire-price", "FHKST01010100")
        val DAILY_CANDLES = Endpoint("daily-candles", "$QUOTATIONS/inquire-daily-itemchartprice", "FHKST03010100")
        val HOLIDAYS = Endpoint("holidays", "$QUOTATIONS/chk-holiday", "CTCA0903R")
        val BALANCE = Endpoint("balance", "$TRADING/inquire-balance", "TTTC8434R")
        val BUYABLE = Endpoint("buyable", "$TRADING/inquire-psbl-order", "TTTC8908R")
        val ORDER_BUY = Endpoint("order-cash-buy", "$TRADING/order-cash", "TTTC0012U")
        val ORDER_SELL = Endpoint("order-cash-sell", "$TRADING/order-cash", "TTTC0011U")
        val DAILY_ORDERS = Endpoint("daily-orders", "$TRADING/inquire-daily-ccld", "TTTC0081R")

        val MAPPER = ObjectMapper()
        val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun JsonNode.textOrNull(field: String): String? =
            get(field)?.takeUnless { it.isNull || it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

        /** 연속조회 키는 받은 값을 다듬지 않고 그대로 다음 요청에 넣는다. */
        fun JsonNode.rawText(field: String): String? = get(field)?.takeUnless { it.isNull || it.isContainerNode }?.asText()
    }
}
