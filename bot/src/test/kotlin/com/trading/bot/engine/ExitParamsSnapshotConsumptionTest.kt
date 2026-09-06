package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.config.UpbitProperties
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import io.mockk.mockk
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * 청산 게이트는 **진입 시점 스냅샷**을 따른다 — 보유 중 전역 설정이 바뀌어도 그 포지션의 규칙은 그대로다.
 *
 * 왜 필요한가: 2026-09-06 에 트레일링을 승격했을 때 **열려 있던 포지션도 즉시 새 규칙으로 판정**됐다.
 * 진입은 옛 규칙, 청산은 새 규칙이라 성과 귀속이 깨진다 — 파라미터를 자주 바꾸는 개선 루프에서는 구조적 문제다.
 * 스냅샷 저장·복원은 이미 있었고(`TradingState.exitParams`), **소비만 없었다**(#177).
 *
 * 스냅샷이 없으면(이 변경 이전에 열린 포지션·복원 실패) **전역값으로 폴백**한다 — 동작 보존이 기본이다.
 */
class ExitParamsSnapshotConsumptionTest {

    /** 전역은 느슨한 값. 스냅샷이 소비되면 이 값들은 쓰이지 않아야 한다. */
    private val globalConfig = TradingProperties(
        takeProfitPct = 10.0, maxLossPct = 10.0, trailingStopPct = 5.0, trailingArmPct = 8.0, maxHoldDays = 5,
    )

    private val dummyClient = UpbitClientImpl(
        WebClient.builder().build(),
        UpbitAuthProvider(UpbitProperties(accessKey = "x", secretKey = "x")),
    )
    private val manager = PositionManager(dummyClient, globalConfig, mockk(relaxed = true), 1L)

    /** 진입 시점은 타이트한 값. */
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
        // 스냅샷이 없으면 전역(익절 10%)을 따른다 — 이 변경 이전에 열린 포지션의 동작 보존.
        assertFalse(manager.checkTakeProfit(state, 103.0)) { "스냅샷이 없으면 전역을 따라야 한다" }
        assertTrue(manager.checkTakeProfit(state, 111.0))
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
