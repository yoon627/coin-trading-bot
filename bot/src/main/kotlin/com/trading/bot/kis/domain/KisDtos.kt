package com.trading.bot.kis.domain

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * KIS REST DTO. 요청 body 는 KIS 코드명(UPPER_SNAKE)이라 toBody() 로 직접 Map 구성(Jackson SNAKE_CASE 우회).
 * 응답은 @JsonProperty 로 KIS 응답 필드명(lower_snake / UPPER)을 명시. KIS 는 수량/금액을 문자열로 반환.
 * 필드/경로 출처: github.com/koreainvestment/open-trading-api (plan D14).
 */

enum class KisSide { BUY, SELL }

enum class KisOrderType(val ordDvsn: String) {
    LIMIT("00"),   // 지정가
    MARKET("01"),  // 시장가
}

/** 현금주문 요청. price 는 지정가만 의미(시장가는 "0"). */
data class KisOrderRequest(
    val cano: String,
    val acntPrdtCd: String,
    val symbol: String,        // PDNO
    val side: KisSide,
    val orderType: KisOrderType,
    val qty: Long,             // ORD_QTY
    val price: Long?,          // ORD_UNPR (지정가 KRW)
) {
    fun toBody(): Map<String, String> = mapOf(
        "CANO" to cano,
        "ACNT_PRDT_CD" to acntPrdtCd,
        "PDNO" to symbol,
        "ORD_DVSN" to orderType.ordDvsn,
        "ORD_QTY" to qty.toString(),
        "ORD_UNPR" to (if (orderType == KisOrderType.MARKET) "0" else (price ?: 0L).toString()),
        "EXCG_ID_DVSN_CD" to "KRX", // 차세대 개편 신규 필수 필드
    )
}

// ---- 주문 응답 (/uapi/domestic-stock/v1/trading/order-cash) ----

data class KisOrderResponse(
    @JsonProperty("rt_cd") val rtCd: String = "",
    @JsonProperty("msg_cd") val msgCd: String? = null,
    @JsonProperty("msg1") val msg1: String? = null,
    @JsonProperty("output") val output: KisOrderOutput? = null,
) {
    fun isSuccess(): Boolean = rtCd == "0"
}

data class KisOrderOutput(
    @JsonProperty("KRX_FWDG_ORD_ORGNO") val orgNo: String? = null,
    @JsonProperty("ODNO") val odno: String? = null,
    @JsonProperty("ORD_TMD") val ordTmd: String? = null,
)

/** placeOrder 정상 결과 (rt_cd=0 + ODNO 존재). */
data class KisOrderAck(
    val odno: String,
    val orgNo: String?,
    val ordTmd: String?,
)

// ---- 당일 주문체결 조회 (inquire-daily-ccld) — reconcile 핵심 ----

data class KisDailyCcldResponse(
    @JsonProperty("rt_cd") val rtCd: String = "",
    @JsonProperty("msg_cd") val msgCd: String? = null,
    @JsonProperty("msg1") val msg1: String? = null,
    @JsonProperty("ctx_area_fk100") val ctxAreaFk100: String = "",
    @JsonProperty("ctx_area_nk100") val ctxAreaNk100: String = "",
    @JsonProperty("output1") val output1: List<KisCcldRow> = emptyList(),
) {
    fun isSuccess(): Boolean = rtCd == "0"
}

data class KisCcldRow(
    @JsonProperty("odno") val odno: String = "",
    @JsonProperty("pdno") val pdno: String = "",
    @JsonProperty("sll_buy_dvsn_cd") val sllBuyDvsnCd: String = "", // 01 매도 / 02 매수
    @JsonProperty("ord_qty") val ordQty: String = "0",
    @JsonProperty("tot_ccld_qty") val totCcldQty: String = "0",
    @JsonProperty("rmn_qty") val rmnQty: String = "0",
    @JsonProperty("cncl_yn") val cnclYn: String = "N",
    @JsonProperty("avg_prvs") val avgPrvs: String = "0", // 평균체결가
) {
    fun side(): KisSide? = when (sllBuyDvsnCd) {
        "02" -> KisSide.BUY
        "01" -> KisSide.SELL
        else -> null
    }

    fun orderQty(): Long = ordQty.toLongOrNull() ?: 0
    fun executedQty(): Long = totCcldQty.toLongOrNull() ?: 0
    fun remainingQty(): Long = rmnQty.toLongOrNull() ?: 0
    fun cancelled(): Boolean = cnclYn.equals("Y", ignoreCase = true)
    fun avgFillPrice(): Double = avgPrvs.toDoubleOrNull() ?: 0.0
}

// ---- 잔고 조회 (inquire-balance) ----

data class KisBalanceResponse(
    @JsonProperty("rt_cd") val rtCd: String = "",
    @JsonProperty("output1") val holdings: List<KisHolding> = emptyList(),
    @JsonProperty("output2") val summary: List<KisAccountSummary> = emptyList(),
) {
    fun isSuccess(): Boolean = rtCd == "0"
}

data class KisHolding(
    @JsonProperty("pdno") val pdno: String = "",
    @JsonProperty("hldg_qty") val hldgQty: String = "0",
    @JsonProperty("ord_psbl_qty") val ordPsblQty: String = "0",
    @JsonProperty("pchs_avg_pric") val pchsAvgPric: String = "0",
) {
    fun heldQty(): Long = hldgQty.toLongOrNull() ?: 0
    fun avgBuyPrice(): Double = pchsAvgPric.toDoubleOrNull() ?: 0.0
}

data class KisAccountSummary(
    @JsonProperty("dnca_tot_amt") val dncaTotAmt: String = "0",         // 예수금총금액
    @JsonProperty("prvs_rcdl_excc_amt") val prvsRcdlExccAmt: String = "0", // 가수도정산금액(D+2)
) {
    fun depositTotal(): Long = dncaTotAmt.toLongOrNull() ?: 0
}

// ---- 현재가 (inquire-price) ----

data class KisPriceResponse(
    @JsonProperty("rt_cd") val rtCd: String = "",
    @JsonProperty("output") val output: KisPriceOutput? = null,
) {
    fun isSuccess(): Boolean = rtCd == "0"
}

data class KisPriceOutput(
    @JsonProperty("stck_prpr") val stckPrpr: String = "0",
) {
    fun price(): Long = stckPrpr.toLongOrNull() ?: 0
}

// ---- 토큰 ----

data class KisTokenResponse(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("token_type") val tokenType: String = "Bearer",
    @JsonProperty("expires_in") val expiresIn: Long = 0,
    @JsonProperty("access_token_token_expired") val accessTokenExpired: String? = null,
)
