package com.trading.bot.kis.market

import com.trading.bot.domain.OrderRequest
import com.trading.bot.exchange.MarketBuySize
import com.trading.bot.exchange.MarketRules
import kotlin.math.floor
import kotlin.math.min

/**
 * KRX 주문 규칙. 정수 주 수량으로 시장가 매수하고, 1회 주문 금액을 [maxOrderAmount](원)로 자른다.
 * 최소주문·dust 규칙을 두지 않아 저가주 소량 보유의 손절 매도도 막지 않는다. 시장가만 쓰므로 호가단위는 보지 않는다.
 */
class KrxMarketRules(private val maxOrderAmount: Long) : MarketRules {

    override val minOrderNotional: Double = 0.0
    override val tracksDust: Boolean = false

    /** 1주도 살 수 없으면 null. */
    override fun buySize(investAmount: Double, price: Double): MarketBuySize? {
        // 부정 비교라 NaN 도 걸린다.
        if (!(price > 0.0)) return null
        val qty = floor(min(investAmount, maxOrderAmount.toDouble()) / price)
        if (!(qty >= 1.0)) return null
        return MarketBuySize.Quantity(qty.toLong())
    }

    /** 엔진 공용 어휘(Upbit 형식)의 수량 시장가 매수 요청이다. KIS 현금주문(수량 시장가)으로 바꾸는 것은 주문 어댑터가 한다. */
    override fun buyOrderRequest(market: String, size: MarketBuySize, identifier: String): OrderRequest {
        require(size is MarketBuySize.Quantity) { "KRX market buy takes a share quantity: $size" }
        return OrderRequest(
            market = market,
            side = "bid",
            ordType = "market",
            volume = size.qty.toString(),
            identifier = identifier,
        )
    }
}
