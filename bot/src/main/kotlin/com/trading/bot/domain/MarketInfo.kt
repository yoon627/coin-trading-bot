package com.trading.bot.domain

import com.fasterxml.jackson.annotation.JsonProperty

/** `GET /v1/market/all?is_details=true` 한 행. 자동 유니버스가 쓰는 필드만 담는다. */
data class MarketInfo(
    val market: String = "",
    @JsonProperty("korean_name")
    val koreanName: String = "",
    @JsonProperty("market_event")
    val marketEvent: MarketEvent? = null,
) {
    /** 투자유의 종목. `caution`("주의") 은 유의가 아니라 제외하지 않는다. */
    val warning: Boolean get() = marketEvent?.warning == true
}

data class MarketEvent(
    val warning: Boolean = false,
)
