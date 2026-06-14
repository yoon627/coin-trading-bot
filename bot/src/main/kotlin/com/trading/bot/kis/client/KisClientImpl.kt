package com.trading.bot.kis.client

import com.trading.bot.kis.domain.KisBalanceResponse
import com.trading.bot.kis.domain.KisCandle
import com.trading.bot.kis.domain.KisCandlePeriod
import com.trading.bot.kis.domain.KisCcldRow
import com.trading.bot.kis.domain.KisDailyCandleResponse
import com.trading.bot.kis.domain.KisMinuteCandleResponse
import com.trading.bot.kis.domain.KisDailyCcldResponse
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.domain.KisOrderAck
import com.trading.bot.kis.domain.KisOrderRequest
import com.trading.bot.kis.domain.KisOrderResponse
import com.trading.bot.kis.domain.KisPriceResponse
import com.trading.bot.kis.domain.KisSide
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

/**
 * KIS 국내주식 REST 구현. 사용자별 키/계좌/모의여부가 바인딩된 인스턴스([KisClientFactory] 생성).
 * tr_id 는 신버전(KRX 차세대) — 매수 TTTC0012U/매도 TTTC0011U(모의 VTTC...). 출처 plan D14.
 */
class KisClientImpl(
    private val webClient: WebClient,
    private val tokenProvider: KisTokenProvider,
    private val appKey: String,
    private val appSecret: String,
    private val cano: String,
    private val acntPrdtCd: String,
    private val paper: Boolean,
    private val custType: String,
) : KisClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun placeOrder(req: KisOrderRequest): KisOrderAck {
        val trId = when (req.side) {
            KisSide.BUY -> if (paper) "VTTC0012U" else "TTTC0012U"
            KisSide.SELL -> if (paper) "VTTC0011U" else "TTTC0011U"
        }
        val token = tokenProvider.token()
        val response = try {
            webClient.post()
                .uri("/uapi/domestic-stock/v1/trading/order-cash")
                .headers(authHeaders(token, trId))
                .bodyValue(req.toBody())
                .retrieve()
                .onStatus(HttpStatusCode::isError) { transportError(it) }
                .bodyToMono<KisOrderResponse>()
                .awaitSingle()
        } catch (e: KisApiException) {
            throw e
        } catch (e: Exception) {
            // 타임아웃/연결오류 등 — 접수 여부 불명(UNKNOWN 으로 흘러야 함).
            throw KisApiException(definitiveReject = false, msg = e.message, cause = e)
        }
        val odno = response.output?.odno
        if (!response.isSuccess() || odno.isNullOrBlank()) {
            // 브로커가 명시 거부(rt_cd!=0) — 미접수 확정.
            throw KisApiException(
                definitiveReject = true,
                rtCd = response.rtCd,
                msgCd = response.msgCd,
                msg = response.msg1,
            )
        }
        return KisOrderAck(odno = odno, orgNo = response.output?.orgNo, ordTmd = response.output?.ordTmd)
    }

    override suspend fun inquireDailyConclusions(date: String): List<KisCcldRow> {
        val trId = if (paper) "VTTC0081R" else "TTTC0081R"
        // 연속조회(pagination): tr_cont=F/M 이면 다음 페이지가 있다. CTX_AREA_*100 을 이어 받아 전 페이지 수집.
        // (체결 누적 계좌에서 첫 페이지만 읽으면 reconcile 이 ODNO 를 못 찾아 거짓 NEEDS_REVIEW 발생 — C1.)
        // tr_cont 값/연속조회 헤더는 공식 문서 기준 best-effort — 실계정 스모크로 최종 확인(plan 검증 한계).
        val all = mutableListOf<KisCcldRow>()
        var fk = ""
        var nk = ""
        var page = 0
        while (page < MAX_INQUIRY_PAGES) {
            val token = tokenProvider.token()
            val continuation = page > 0
            val entity = webClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
                        .queryParam("CANO", cano)
                        .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                        .queryParam("INQR_STRT_DT", date)
                        .queryParam("INQR_END_DT", date)
                        .queryParam("SLL_BUY_DVSN_CD", "00")
                        .queryParam("INQR_DVSN", "00")
                        .queryParam("PDNO", "")
                        .queryParam("CCLD_DVSN", "00")
                        .queryParam("ORD_GNO_BRNO", "")
                        .queryParam("ODNO", "")
                        .queryParam("INQR_DVSN_3", "00")
                        .queryParam("INQR_DVSN_1", "")
                        .queryParam("CTX_AREA_FK100", fk)
                        .queryParam("CTX_AREA_NK100", nk)
                        .build()
                }
                .headers {
                    authHeaders(token, trId)(it)
                    if (continuation) it.set("tr_cont", "N") // 다음 페이지 요청
                }
                .retrieve()
                .onStatus(HttpStatusCode::isError) { transportError(it) }
                .toEntity(KisDailyCcldResponse::class.java)
                .awaitSingle()
            val body = entity.body ?: break
            all += body.output1
            val trCont = entity.headers.getFirst("tr_cont") ?: ""
            if (trCont != "F" && trCont != "M") break // D/E/빈값 = 마지막 페이지
            fk = body.ctxAreaFk100
            nk = body.ctxAreaNk100
            page++
        }
        return all
    }

    override suspend fun getHoldings(): List<KisHolding> {
        val trId = if (paper) "VTTC8434R" else "TTTC8434R"
        val token = tokenProvider.token()
        val resp = webClient.get()
            .uri { b ->
                b.path("/uapi/domestic-stock/v1/trading/inquire-balance")
                    .queryParam("CANO", cano)
                    .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                    .queryParam("AFHR_FLPR_YN", "N")
                    .queryParam("OFL_YN", "")
                    .queryParam("INQR_DVSN", "02")
                    .queryParam("UNPR_DVSN", "01")
                    .queryParam("FUND_STTL_ICLD_YN", "N")
                    .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                    .queryParam("PRCS_DVSN", "00")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .headers(authHeaders(token, trId))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { transportError(it) }
            .bodyToMono<KisBalanceResponse>()
            .awaitSingle()
        return resp.holdings
    }

    override suspend fun getCurrentPrice(symbol: String): Long {
        val token = tokenProvider.token()
        val resp = webClient.get()
            .uri { b ->
                b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", symbol)
                    .build()
            }
            .headers(authHeaders(token, "FHKST01010100"))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { transportError(it) }
            .bodyToMono<KisPriceResponse>()
            .awaitSingle()
        val price = resp.output?.price() ?: 0
        // 0/실패를 정상값처럼 넘기면 호출부(notional 가드 등)가 우회된다 — 예외로 가시화(M4).
        if (!resp.isSuccess() || price <= 0) {
            throw KisApiException(definitiveReject = false, rtCd = resp.rtCd, msg = "invalid current price for $symbol (rt_cd=${resp.rtCd}, price=$price)")
        }
        return price
    }

    override suspend fun getDailyCandles(
        symbol: String,
        from: String,
        to: String,
        period: KisCandlePeriod,
        adjusted: Boolean,
    ): List<KisCandle> {
        val token = tokenProvider.token()
        val resp = webClient.get()
            .uri { b ->
                b.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", symbol)
                    .queryParam("FID_INPUT_DATE_1", from)
                    .queryParam("FID_INPUT_DATE_2", to)
                    .queryParam("FID_PERIOD_DIV_CODE", period.code)
                    .queryParam("FID_ORG_ADJ_PRC", if (adjusted) "0" else "1")
                    .build()
            }
            .headers(authHeaders(token, "FHKST03010100"))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { transportError(it) }
            .bodyToMono<KisDailyCandleResponse>()
            .awaitSingle()
        if (!resp.isSuccess()) {
            throw KisApiException(definitiveReject = false, rtCd = resp.rtCd, msg = resp.msg1 ?: "daily candle query failed")
        }
        // 오래된→최신 순으로 정렬(KIS 는 최신순 반환) — 지표 계산 편의.
        return resp.output2.map { it.toCandle() }.sortedBy { it.date }
    }

    override suspend fun getMinuteCandles(symbol: String, baseTime: String): List<KisCandle> {
        val token = tokenProvider.token()
        val resp = webClient.get()
            .uri { b ->
                b.path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                    .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", symbol)
                    .queryParam("FID_INPUT_HOUR_1", baseTime)
                    .queryParam("FID_PW_DATA_INCU_YN", "N")
                    .queryParam("FID_ETC_CLS_CODE", "")
                    .build()
            }
            .headers(authHeaders(token, "FHKST03010200"))
            .retrieve()
            .onStatus(HttpStatusCode::isError) { transportError(it) }
            .bodyToMono<KisMinuteCandleResponse>()
            .awaitSingle()
        if (!resp.isSuccess()) {
            throw KisApiException(definitiveReject = false, rtCd = resp.rtCd, msg = "minute candle query failed")
        }
        return resp.output2.map { it.toCandle() }.sortedWith(compareBy({ it.date }, { it.time }))
    }

    private fun authHeaders(token: String, trId: String): (HttpHeaders) -> Unit = { h ->
        h.set("authorization", "Bearer $token")
        h.set("appkey", appKey)
        h.set("appsecret", appSecret)
        h.set("tr_id", trId)
        h.set("custtype", custType)
        h.contentType = MediaType.APPLICATION_JSON
    }

    private fun transportError(response: ClientResponse): Mono<Throwable> =
        response.bodyToMono<String>().defaultIfEmpty("(no body)").map { body ->
            log.error("KIS HTTP error: {} - {}", response.statusCode(), body)
            KisApiException(
                definitiveReject = false,
                statusCode = response.statusCode().value(),
                rawBody = body,
                msg = "KIS HTTP error",
            )
        }

    companion object {
        private const val MAX_INQUIRY_PAGES = 20 // 연속조회 무한루프 방어
    }
}
