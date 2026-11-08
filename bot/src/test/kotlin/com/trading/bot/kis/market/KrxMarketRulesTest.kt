package com.trading.bot.kis.market

import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.MarketBuySize
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KrxMarketRulesTest {

    private val rules = KrxMarketRules(maxOrderAmount = 2_000_000)

    @Test
    fun `매수 수량은 투자금을 가격으로 나눈 정수 주로 내린다`() {
        assertThat(rules.buySize(investAmount = 100_000.0, price = 33_000.0)).isEqualTo(MarketBuySize.Quantity(3))
        assertThat(rules.buySize(investAmount = 99_999.0, price = 33_334.0)).isEqualTo(MarketBuySize.Quantity(2))
    }

    @Test
    fun `1회 주문 금액은 maxOrderAmount 를 넘지 않는다`() {
        assertThat(KrxMarketRules(maxOrderAmount = 100_000).buySize(investAmount = 1_000_000.0, price = 10_000.0))
            .isEqualTo(MarketBuySize.Quantity(10))
        assertThat(KrxMarketRules(maxOrderAmount = 100_000).buySize(investAmount = 50_000.0, price = 10_000.0))
            .isEqualTo(MarketBuySize.Quantity(5))
    }

    @Test
    fun `1주도 살 수 없거나 가격이 0 이하·NaN 이면 null 이다`() {
        assertThat(rules.buySize(investAmount = 9_999.0, price = 10_000.0)).isNull()
        assertThat(KrxMarketRules(maxOrderAmount = 9_999).buySize(investAmount = 1_000_000.0, price = 10_000.0)).isNull()
        assertThat(listOf(0.0, -1.0, Double.NaN).map { rules.buySize(investAmount = 100_000.0, price = it) }).containsOnlyNulls()
        assertThat(rules.buySize(investAmount = Double.NaN, price = 10_000.0)).isNull()
    }

    @Test
    fun `매수 요청은 수량 시장가이고 금액 크기는 받지 않는다`() {
        assertThat(rules.buyOrderRequest("KRX-005930", MarketBuySize.Quantity(3), "ctb-1")).isEqualTo(
            OrderRequest(market = "KRX-005930", side = "bid", ordType = "market", volume = "3", identifier = "ctb-1"),
        )
        assertThatThrownBy { rules.buyOrderRequest("KRX-005930", MarketBuySize.QuoteAmount(100_000), "ctb-2") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `최소주문과 dust 규칙이 없어 1주 저가 보유도 매도 대상이다`() {
        assertThat(rules.minOrderNotional).isZero()
        assertThat(rules.tracksDust).isFalse()
        assertThat(rules.isBelowMinOrder(volume = 1.0, price = 100.0)).isFalse()
        assertThat(rules.isDust(TradingState("KRX-005930", position = true, holdVolume = 1.0), price = 100.0)).isFalse()
    }

    // 매도 수량은 엔진 장부(Double)에서 오므로 주 단위로 내린다. 소수점이 붙으면 주문 어댑터가 거절한다.
    @Test
    fun `sell volume is whole shares rounded down`() {
        assertThat(rules.sellVolume(3.0)).isEqualTo("3")
        assertThat(rules.sellVolume(2.9999)).isEqualTo("2")
    }
}
