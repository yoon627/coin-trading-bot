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
     * 거래소가 실제 청구한 수수료(원). 주문 접수 직후 응답에는 체결이 반영되지 않아
     * 체결 후 `getOrder` 로 조회한 응답에서만 믿을 수 있다.
     */
    @JsonProperty("paid_fee")
    val paidFee: String? = null,
    /** 체결 내역. Upbit 개별 주문 조회에만 있고 체결금액 합계 필드가 없어 실체결 단가는 여기서 계산한다. */
    val trades: List<OrderTrade> = emptyList(),
) {
    /**
     * 실제 체결 단가(`Σfunds / Σvolume`). 얻을 수 없으면 추정하지 않고 null 이다.
     * `price × volume` 으로 다시 계산하면 부분 체결이 여러 건일 때 반올림이 누적된다.
     */
    fun filledVwap(): Double? {
        val (funds, volume) = filledTotals() ?: return null
        return (funds / volume).takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * 이 주문이 실제로 체결한 대금(`Σ trades[].funds`, 수수료 미포함). 얻을 수 없으면 null 이다.
     * funds 만 필요해도 volume 검증까지 통과한 체결 내역만 믿는다.
     */
    fun filledFunds(): Double? {
        val funds = filledTotals()?.first ?: return null
        return funds.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun filledTotals(): Pair<Double, Double>? {
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
        return funds to volume
    }

    /** 거래소가 더 바꾸지 않는 최종 상태. wait/watch 는 체결이 진행 중이라 수량·금액·수수료가 늘 수 있다. */
    fun isTerminal(): Boolean = state == "done" || state == "cancel"

    /** `paid_fee` 가 없으면 추정한다. 매도의 `totalAmount` 는 그 체결의 대금이라 추정해도 된다. */
    fun sellFeeBasis(): FeeBasis = feeBasis().takeIf { it is FeeBasis.Measured } ?: FeeBasis.Estimate

    /**
     * 유한한 0 이상의 값일 때만 실측이고 나머지는 [FeeBasis.Unrecorded] 다. 엔진 매수의 `totalAmount` 는 포지션 전체 원가라 추정하면 부풀려진다.
     * `toDoubleOrNull()` 은 "NaN"·"Infinity" 도 파싱하므로 걸러내야 `SUM(fee)` 가 NaN 이 되지 않는다.
     */
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
    /** 클라이언트 주문 식별자. 응답을 못 받은 주문을 나중에 찾는 유일한 수단이다. */
    val identifier: String? = null,
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
        identifier?.let { params["identifier"] = it }
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
