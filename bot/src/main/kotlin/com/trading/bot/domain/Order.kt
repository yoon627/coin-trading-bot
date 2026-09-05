package com.trading.bot.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class Order(
    val uuid: String = "",
    val side: String = "",
    @JsonProperty("ord_type")
    val ordType: String = "",
    val price: String? = null,
    val state: String = "",
    val market: String = "",
    val volume: String? = null,
    @JsonProperty("remaining_volume")
    val remainingVolume: String? = null,
    @JsonProperty("executed_volume")
    val executedVolume: String? = null,
    @JsonProperty("trades_count")
    val tradesCount: Int = 0,
    /**
     * 거래소가 실제로 청구한 수수료(원). Upbit 개별 주문 조회가 `reserved_fee`(예약)·`remaining_fee`(잔여)와
     * 함께 준다 — 부분 체결 후 `cancel` 로 끝나도 체결분에 대해 청구된 값이다.
     *
     * 주문 **접수 직후** 응답에는 아직 체결이 안 잡혀 신뢰할 수 없다. `getOrder` 로 체결 후 조회한
     * 응답에서만 실측으로 쓸 것(#133).
     */
    @JsonProperty("paid_fee")
    val paidFee: String? = null,
    /**
     * 체결 내역. Upbit **개별 주문 조회**(`GET /v1/order`)만 준다 — 주문 접수 직후 응답에는 없다.
     * 최상위에 체결금액 합계 필드가 없으므로(공식 문서 확인, 2026-09-05) 실체결 단가는 여기서 만든다.
     */
    val trades: List<OrderTrade> = emptyList(),
) {
    /**
     * 이 주문 응답에서 얻을 수 있는 수수료 출처.
     *
     * 수수료로 쓸 수 있는 **유한한 0 이상의 수**일 때만 [FeeBasis.Measured] 다. 그 외는 전부
     * [FeeBasis.Unrecorded] — **추정으로 떨어뜨리지 않는다.** 엔진 매수의 `totalAmount` 는 포지션
     * 전체 원가라, 추정하면 고치려던 과대계상이 그대로 재발한다(#133).
     *
     * `isFinite()` 가 필요한 이유: `toDoubleOrNull()` 은 `"NaN"`·`"Infinity"` 를 **정상 파싱한다**(실측 확인).
     * 그 값이 `double precision` 컬럼에 들어가면 이후 `SUM(fee)` 이 영구히 `NaN` 이 된다 — 0 이 섞이는 것과
     * 달리 되돌릴 수 없다.
     */
    /**
     * 실제 체결 단가(VWAP) — `Σfunds / Σvolume`. 얻을 수 없으면 **null 이고 추정하지 않는다**.
     *
     * 왜 필요한가: 이 봇은 시장가로 팔고 거래 기록에는 **판단 시점 tick 가격**을 쓴다. 그 둘의 차이가
     * 실행 슬리피지이고, 백테에는 아예 없는 항목이다(wiki `query/exit-resolution-verdict-2026-09`).
     *
     * `funds` 를 쓰는 이유: `price × volume` 으로 재계산하면 부분 체결이 여러 건일 때 반올림이 누적된다.
     * [feeBasis] 와 같은 규율 — 유한한 양수일 때만 값이고, 아니면 null 로 떨어뜨려 소비자가 "모른다"를 보게 한다.
     */
    fun filledVwap(): Double? {
        if (trades.isEmpty()) return null
        var funds = 0.0
        var volume = 0.0
        for (t in trades) {
            val f = t.funds?.toDoubleOrNull() ?: return null
            val v = t.volume?.toDoubleOrNull() ?: return null
            if (!f.isFinite() || !v.isFinite() || f < 0.0 || v <= 0.0) return null
            funds += f
            volume += v
        }
        if (volume <= 0.0) return null
        return (funds / volume).takeIf { it.isFinite() && it > 0.0 }
    }

    fun feeBasis(): FeeBasis =
        paidFee?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { FeeBasis.Measured(it) }
            ?: FeeBasis.Unrecorded
}

/** 개별 주문 조회 응답의 체결 한 건. 금액·수량은 문자열로 온다(정밀도 보존). */
data class OrderTrade(
    val market: String = "",
    val uuid: String = "",
    val price: String? = null,
    val volume: String? = null,
    val funds: String? = null,
    val side: String = "",
)

data class OrderRequest(
    val market: String,
    val side: String,
    @JsonProperty("ord_type")
    val ordType: String,
    val volume: String? = null,
    val price: String? = null,
) {
    fun toQueryString(): String {
        return toParamMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    fun toParamMap(): Map<String, String> {
        val params = mutableMapOf(
            "market" to market,
            "side" to side,
            "ord_type" to ordType,
        )
        volume?.let { params["volume"] = it }
        price?.let { params["price"] = it }
        return params
    }
}

data class Ticker(
    val market: String = "",
    @JsonProperty("trade_price")
    val tradePrice: Double = 0.0,
    @JsonProperty("high_price")
    val highPrice: Double = 0.0,
    @JsonProperty("low_price")
    val lowPrice: Double = 0.0,
    @JsonProperty("acc_trade_volume_24h")
    val accTradeVolume24h: Double = 0.0,
    @JsonProperty("signed_change_rate")
    val signedChangeRate: Double = 0.0,
    @JsonProperty("acc_trade_price_24h")
    val accTradePrice24h: Double = 0.0,
)
