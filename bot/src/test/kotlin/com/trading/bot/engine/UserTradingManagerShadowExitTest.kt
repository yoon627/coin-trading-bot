package com.trading.bot.engine

import com.trading.bot.persistence.ShadowExitObservationRepository
import com.trading.common.config.ShadowExitProperties
import com.trading.common.config.TradingProperties
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 그림자 관측 설정이 구간 밖이면 기동을 막는 대신 관측기만 만들지 않는다(#271). 저장소는 있어야 구간 판정에 닿는다. */
class UserTradingManagerShadowExitTest {

    private val repository: ShadowExitObservationRepository = mockk(relaxed = true)

    private fun manager(shadow: ShadowExitProperties) = UserTradingManager(
        mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), mockk(), TradingProperties(),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        shadowExitProperties = shadow, shadowExitObservationRepository = repository,
    )

    @Test
    fun `켜진 그림자 관측은 구간 안이면 만들고 구간 밖이면 만들지 않는다`() {
        assertThat(manager(ShadowExitProperties(enabled = true)).shadowExitObserver(1L)).isNotNull()
        listOf(
            ShadowExitProperties(enabled = true, trailingStopPct = 0.0),
            ShadowExitProperties(enabled = true, trailingArmPct = -1.0),
            ShadowExitProperties(enabled = true, trailingStopPct = Double.NaN),
            ShadowExitProperties(enabled = true, trailingArmPct = Double.POSITIVE_INFINITY),
        ).forEach { assertThat(manager(it).shadowExitObserver(1L)).describedAs(it.toString()).isNull() }
    }
}
