package com.trading.bot.characterization

import ch.qos.logback.classic.Level
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.PositionManager
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 응답을 못 받은 Upbit 매도의 확정 규칙을 고정한다. 흔적은 주문 전보다 free 가 줄었는지로 보고(locked 는 세지 않는다),
 * 미접수로 확정되면 포지션을 실잔고로 되살린 뒤 pending 을 지운다. 판단을 못 한 조회는 halt 카운터에 세지 않고,
 * 대신 pendingSellSince 부터 reconcileHaltThreshold × intervalSeconds 가 지나면 ERROR 로 한 번 알린다.
 */
class UnknownSellOrderCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-10-18T00:00:00Z"), TradingDay.KST)
    private val sent = mutableListOf<OrderRequest>()

    private fun manager(props: TradingProperties = TradingProperties()) = PositionManager(upbit, props, stateService, 1L, clock = clock)

    /** free 0.002 를 판 매도의 응답이 끊긴 상태를 만들고 그 identifier 를 돌려준다. */
    private suspend fun lostSell(manager: PositionManager, state: TradingState): String {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); throw RuntimeException("ReadTimeout") }
        manager.sell("KRW-BTC", state, 50_000_000.0, SellReason.STOP_LOSS)
        return state.pendingSellIdentifier!!
    }

    private fun holding() = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002)

    @Test
    fun `a drop in free balance is a trace even when the total is unchanged, so the pending is kept`() = runTest {
        val manager = manager()
        val state = holding()
        val identifier = lostSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        // free 는 줄었지만 free + locked 는 주문 전 0.002 그대로다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.001", locked = "0.001"))

        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        clock.advance(60)
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)

        assertEquals(identifier, state.pendingSellIdentifier)
        assertTrue(state.position)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a sell confirmed as never placed revives the position from the balance and clears the pending`() = runTest {
        val manager = manager()
        // 재시작 뒤 복원된 행. 잔고가 주문에 잠겨 보였을 때처럼 position 이 내려가 있다.
        val state = TradingState(
            "KRW-BTC",
            pendingSellIdentifier = "ctb-s",
            pendingSellReason = SellReason.STOP_LOSS,
            pendingSellVolume = 0.002,
            pendingSellPriorVolume = 0.002,
            pendingSellSince = clock.instant(),
        )
        coEvery { upbit.getOrderByIdentifier("ctb-s") } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002", avg = "48000000"))

        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        assertTrue(state.hasPendingSell())
        clock.advance(60)
        val record = manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)

        assertEquals(null, record)
        assertFalse(state.hasPendingSell())
        assertTrue(state.position)
        assertEquals(0.002, state.holdVolume)
        assertEquals(48_000_000.0, state.avgBuyPrice)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `undecided sell lookups are not counted toward the halt threshold`() = runTest {
        val manager = manager(TradingProperties(reconcileHaltThreshold = 2))
        val state = holding()
        val identifier = lostSell(manager, state)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("503")

        repeat(3) { manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0) }

        assertEquals(0, state.reconcileFailureCount)
        assertFalse(state.halted)
        assertEquals(identifier, state.pendingSellIdentifier)
    }

    @Test
    fun `a sell stuck for threshold times interval seconds since it was sent is reported once at ERROR`() = runTest {
        // 2 tick × 10초 = 20초
        val manager = manager(TradingProperties(reconcileHaltThreshold = 2, intervalSeconds = 10))
        val state = holding()
        val identifier = lostSell(manager, state)
        assertEquals(clock.instant(), state.pendingSellSince)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("503")

        capturingLogs(PositionManager::class.java) { logs ->
            fun stuckAlerts() = logs.list.count { it.level == Level.ERROR && "매도 reconcile 미해소" in it.formattedMessage }

            clock.advance(19)
            manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
            assertEquals(0, stuckAlerts())
            assertFalse(state.pendingSellAlerted)

            clock.advance(1)
            manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
            assertEquals(1, stuckAlerts())
            assertTrue(state.pendingSellAlerted)

            clock.advance(60)
            manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
            assertEquals(1, stuckAlerts())
        }
    }
}
