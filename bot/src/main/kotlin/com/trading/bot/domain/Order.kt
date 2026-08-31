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
    fun feeBasis(): FeeBasis =
        paidFee?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { FeeBasis.Measured(it) }
            ?: FeeBasis.Unrecorded
}

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
