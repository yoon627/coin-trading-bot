package com.trading.bot.kis.market

import com.trading.bot.kis.config.KisTradingProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class KrxCostModelTest {

    @Test
    fun `수수료는 매수·매도 양쪽에, 거래세는 매도에만 붙는다`() {
        val model = KrxCostModel(commissionRate = 0.0002, sellTaxRate = 0.002)
        assertThat(model.buyFeeRate).isEqualTo(0.0002)
        assertThat(model.sellFeeRate).isCloseTo(0.0022, within(1e-12))
        assertThat(model.roundTripRate).isCloseTo(0.0024, within(1e-12))
    }

    @Test
    fun `설정의 두 요율로 만든다`() {
        assertThat(KrxCostModel.from(KisTradingProperties(commissionRate = 0.0003, sellTaxRate = 0.0015)))
            .isEqualTo(KrxCostModel(commissionRate = 0.0003, sellTaxRate = 0.0015))
        assertThat(KrxCostModel.from(KisTradingProperties())).isEqualTo(KrxCostModel(commissionRate = 0.0002, sellTaxRate = 0.002))
    }
}
