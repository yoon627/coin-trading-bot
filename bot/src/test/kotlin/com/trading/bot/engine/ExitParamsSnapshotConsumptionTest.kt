package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import io.mockk.mockk
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * 청산 판정은 진입 시점의 스냅샷을 따른다. 보유 중 전역 설정이 바뀌어도 그 포지션의 규칙은 그대로다.
 * 스냅샷이 없는 포지션은 전역값으로 판정한다.
 */
class ExitParamsSnapshotConsumptionTest {

    /** 스냅샷 값과 구분되도록 전역은 느슨하게 둔다. */
    private val globalConfig = TradingProperties(
        takeProfitPct = 10.0, maxLossPct = 10.0, trailingStopPct = 5.0, trailingArmPct = 8.0, maxHoldDays = 5,
    )

    private val dummyClient = UpbitClientImpl(
        WebClient.builder().build(),
        UpbitAuthProvider(accessKey = "x", secretKey = "x"),
    )
    private val manager = PositionManager(dummyClient, globalConfig, mockk(relaxed = true), 1L)

    private val tightSnapshot = ExitParamsSnapshot(
        takeProfitPct = 2.0, maxLossPct = 3.0, trailingStopPct = 1.5, trailingArmPct = 0.0, maxHoldDays = 1,
    )

    private fun heldWithSnapshot(snapshot: ExitParamsSnapshot?) = TradingState(
        ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0, exitParams = snapshot,
    )

    @Test
    fun `take profit follows the entry snapshot, not the looser global`() {
        val state = heldWithSnapshot(tightSnapshot)
        // +3% 는 스냅샷 익절(2%)은 넘지만 전역(10%)은 못 넘는다.
        assertTrue(manager.checkTakeProfit(state, 103.0)) { "진입 시점 익절 2% 를 넘었는데 발동하지 않았다" }
    }

    @Test
    fun `stop loss follows the entry snapshot, not the looser global`() {
        val state = heldWithSnapshot(tightSnapshot)
        // −4% 는 스냅샷 손절(3%)은 넘지만 전역(10%)은 못 넘는다.
        assertTrue(manager.checkStopLoss(state, 96.0)) { "진입 시점 손절 3% 를 넘었는데 발동하지 않았다" }
    }

    @Test
    fun `trailing stop follows the entry snapshot, not the looser global`() {
        val state = heldWithSnapshot(tightSnapshot).apply { peakPrice = 110.0 }
        // 고점 110 → 현재 108: 고점 대비 −1.82%. 스냅샷 트레일(1.5·arm 0)은 발동, 전역(5·arm 8)은 미발동.
        assertTrue(manager.checkTrailingStop(state, 108.0)) { "진입 시점 트레일링 1.5% 를 넘었는데 발동하지 않았다" }
    }

    @Test
    fun `falls back to the global config when no snapshot was taken`() {
        val state = heldWithSnapshot(null)
        // 스냅샷이 없으면 전역(익절 10%)을 따른다.
        assertFalse(manager.checkTakeProfit(state, 103.0)) { "스냅샷이 없으면 전역을 따라야 한다" }
        assertTrue(manager.checkTakeProfit(state, 111.0))
    }

    @Test
    fun `a snapshot taken from the global config carries each field in place`() {
        // 새 진입의 스냅샷과 스냅샷 없는 포지션의 폴백이 모두 이 매핑을 쓴다.
        val global = TradingProperties(takeProfitPct = 1.0, maxLossPct = 2.0, trailingStopPct = 3.0, trailingArmPct = 4.0, maxHoldDays = 5)

        assertEquals(
            ExitParamsSnapshot(takeProfitPct = 1.0, maxLossPct = 2.0, trailingStopPct = 3.0, trailingArmPct = 4.0, maxHoldDays = 5),
            global.exitParamsSnapshot(),
        )
    }

    @Test
    fun `daily reset uses the entry snapshot hold limit`() {
        val kst = ZoneId.of("Asia/Seoul")
        val clock = Clock.fixed(LocalDateTime.parse("2026-09-10T12:00:00").atZone(kst).toInstant(), kst)
        val manager = DailyResetManager(globalConfig, clock) // 전역 보유상한 5일
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0, exitParams = tightSnapshot)
            .apply { buyDate = LocalDate.parse("2026-09-08") } // 2 거래일 경과

        // 스냅샷 상한 1일이면 발동, 전역 5일이면 미발동.
        assertTrue(manager.shouldSellForDailyReset(state)) { "진입 시점 보유상한 1일을 넘었는데 청산하지 않았다" }
    }

    @Test
    fun `daily reset falls back to the global hold limit without a snapshot`() {
        val kst = ZoneId.of("Asia/Seoul")
        val clock = Clock.fixed(LocalDateTime.parse("2026-09-10T12:00:00").atZone(kst).toInstant(), kst)
        val manager = DailyResetManager(globalConfig, clock)
        val state = TradingState(ticker = "KRW-BTC", position = true, avgBuyPrice = 100.0)
            .apply { buyDate = LocalDate.parse("2026-09-08") }

        assertFalse(manager.shouldSellForDailyReset(state)) { "스냅샷이 없으면 전역 5일을 따라야 한다" }
        assertTrue(TradingDay.of(LocalDateTime.now(clock)) == LocalDate.parse("2026-09-10"))
    }
}
