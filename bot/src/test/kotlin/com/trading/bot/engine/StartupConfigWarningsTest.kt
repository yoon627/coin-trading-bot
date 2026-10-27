package com.trading.bot.engine

import com.trading.bot.config.ExitParamRangesTest
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.kis.market.KrxMarketRules
import com.trading.common.config.TradingProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.slf4j.event.Level

/** 엔진 시작 때 알리는 설정 조합과, 매수 직전과 같은 매수 건너뛰기 판정. */
class StartupConfigWarningsTest {

    private fun warnings(properties: TradingProperties, minOrderNotional: Double = UpbitMarketRules.minOrderNotional) =
        TradingEngine.startupConfigWarnings(properties, minOrderNotional)

    @Test
    fun `기본값과 운영 조합에는 경고가 없다`() {
        assertThat(warnings(TradingProperties())).isEmpty()
        assertThat(warnings(ExitParamRangesTest.PRODUCTION)).isEmpty()
    }

    @Test
    fun `익절이 왕복 수수료 이하이면 WARN 한다`() {
        // 청산 판정은 수수료 전 수익률이라 익절이 수수료 이하면 익절해도 순손실이다.
        val fee = 0.001
        val atFee = warnings(TradingProperties(takeProfitPct = fee * 100, trailingStopPct = 0.05, trailingArmPct = 0.0, roundTripFeeRate = fee))
        assertThat(atFee).hasSize(1)
        assertThat(atFee.single().level).isEqualTo(Level.WARN)
        assertThat(atFee.single().message).contains("수수료")
        assertThat(warnings(TradingProperties(takeProfitPct = 0.2, trailingStopPct = 0.05, trailingArmPct = 0.0, roundTripFeeRate = fee))).isEmpty()
    }

    @Test
    fun `1회 매수 상한이 최소 주문 미만이면 ERROR 한다`() {
        val belowMin = warnings(TradingProperties(maxInvestAmount = 4_000.0))
        assertThat(belowMin).hasSize(1)
        assertThat(belowMin.single().level).isEqualTo(Level.ERROR)
        assertThat(belowMin.single().message).contains("최소 주문", "모든 매수")
    }

    @Test
    fun `손절가에서 최소 주문 미만이 되는 상한이면 ERROR 한다`() {
        // 손절 5% 면 5,000 / 0.95 ≈ 5,263원 미만은 손절가에서 팔 수 없어 매수를 건너뛴다.
        val atStopLoss = warnings(TradingProperties(maxInvestAmount = 5_200.0, maxLossPct = 5.0))
        assertThat(atStopLoss).hasSize(1)
        assertThat(atStopLoss.single().level).isEqualTo(Level.ERROR)
        assertThat(atStopLoss.single().message).contains("손절", "모든 매수")
        assertThat(warnings(TradingProperties(maxInvestAmount = 5_300.0, maxLossPct = 5.0))).isEmpty()
    }

    @Test
    fun `최소 주문이 0 인 거래소 규칙에서는 매수 상한 경고가 없다`() {
        assertThat(warnings(TradingProperties(maxInvestAmount = 1_000.0), KrxMarketRules(2_000_000).minOrderNotional)).isEmpty()
    }

    @Test
    fun `어떤 값에도 예외를 던지지 않는다 — 엔진 시작 경로에서 불린다`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { odd ->
            listOf(
                TradingProperties(takeProfitPct = odd),
                TradingProperties(maxLossPct = odd),
                TradingProperties(trailingStopPct = odd),
                TradingProperties(trailingArmPct = odd),
                TradingProperties(roundTripFeeRate = odd),
                TradingProperties(maxInvestAmount = odd),
            ).forEach { properties ->
                assertThatCode { warnings(properties) }.describedAs("$properties").doesNotThrowAnyException()
            }
            assertThatCode { warnings(TradingProperties(), odd) }.describedAs("minOrderNotional=$odd").doesNotThrowAnyException()
        }
    }

    @Test
    fun `매수 건너뛰기 판정은 최소 주문을 먼저, 손절가 매도를 다음에 본다`() {
        assertThat(buySkip(4_999.0, 5.0, 5_000.0)).isEqualTo(BuySkip.BELOW_MIN_ORDER)
        assertThat(buySkip(5_000.0, 0.0, 5_000.0)).isNull()
        assertThat(buySkip(5_000.0, 5.0, 5_000.0)).isEqualTo(BuySkip.UNSELLABLE_AT_STOP_LOSS)
        assertThat(buySkip(10_000.0, 50.0, 5_000.0)).isNull()
        assertThat(buySkip(9_999.0, 50.0, 5_000.0)).isEqualTo(BuySkip.UNSELLABLE_AT_STOP_LOSS)
    }
}
