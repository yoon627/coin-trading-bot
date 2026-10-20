package com.trading.bot.exchange

import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.TradingState
import kotlin.math.floor

/** 시장가 매수 주문의 크기. 거래소마다 금액으로 내거나 수량으로 낸다. */
sealed interface MarketBuySize {
    data class QuoteAmount(val krw: Long) : MarketBuySize

    data class Quantity(val qty: Long) : MarketBuySize
}

interface MarketRules {
    /** 이 금액(원)보다 작은 주문은 거래소가 거절한다. */
    val minOrderNotional: Double

    /** 최소주문에 못 미쳐 팔 수 없는 잔량(dust)을 따로 다루는지. false 면 보유를 모두 매도 가능한 포지션으로 본다. */
    val tracksDust: Boolean

    fun isBelowMinOrder(volume: Double, price: Double): Boolean = volume * price < minOrderNotional

    /** 매수 금액을 주문 크기로 바꾼다. null 이면 이 금액으로는 주문할 수 없다. */
    fun buySize(investAmount: Double, price: Double): MarketBuySize?

    fun buyOrderRequest(market: String, size: MarketBuySize, identifier: String): OrderRequest

    fun isDustVolume(volume: Double, price: Double): Boolean = tracksDust && isBelowMinOrder(volume, price)

    /**
     * 기록된 보유가 이 가격에서 dust 인지. 마지막 동기화 값이라 진입 판단에만 쓰고 주문은 실잔고로 다시 본다.
     * 수량을 모르면(0) 관리 대상으로 남긴다.
     */
    fun isDust(state: TradingState, price: Double): Boolean =
        state.position && state.holdVolume > 0.0 && isDustVolume(state.holdVolume, price)
}

object UpbitMarketRules : MarketRules {
    /** Upbit 최소주문 금액(원). 매수·매도 모두 이보다 작으면 거래소가 거절한다. */
    const val MIN_ORDER_KRW = 5_000.0

    override val minOrderNotional: Double = MIN_ORDER_KRW
    override val tracksDust: Boolean = true

    override fun buySize(investAmount: Double, price: Double): MarketBuySize =
        MarketBuySize.QuoteAmount(floor(investAmount).toLong())

    /** Upbit 시장가 매수는 ord_type=price 에 총 주문 금액을 넣는다. 수량 시장가 매수는 없다. */
    override fun buyOrderRequest(market: String, size: MarketBuySize, identifier: String): OrderRequest {
        require(size is MarketBuySize.QuoteAmount) { "Upbit market buy takes a quote amount: $size" }
        return OrderRequest(
            market = market,
            side = "bid",
            ordType = "price",
            price = size.krw.toString(),
            identifier = identifier,
        )
    }
}
