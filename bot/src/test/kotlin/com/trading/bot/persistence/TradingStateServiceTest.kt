package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.SellReason
import com.trading.bot.persistence.entity.TradingStateEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class TradingStateServiceTest {

    private val repository: TradingStateRepository = mockk()
    private val service = TradingStateService(repository, ObjectMapper())

    private fun rows(vararg entities: TradingStateEntity) {
        every { repository.findByUserId(1L) } returns Flux.fromIterable(entities.toList())
    }

    @Test
    fun `corrupt exit params json drops only the snapshot, never the pending order`() = runTest {
        // row 를 통째로 버리면 미체결 주문 uuid 가 사라져 reconcile 대상에서 빠지고, 같은 자리에 재매수가 들어간다.
        rows(
            TradingStateEntity(
                userId = 1L,
                ticker = "KRW-BTC",
                pendingBuyUuid = "pending-1",
                pendingBuyStrategy = "volatility_breakout",
                halted = true,
                haltReason = "reconcile 실패",
                exitParamsJson = "{not valid json",
            ),
        )

        val states = service.loadStates(1L)

        val state = states["KRW-BTC"]!!
        assertEquals("pending-1", state.pendingBuyUuid)
        assertEquals("volatility_breakout", state.pendingBuyStrategy)
        assertEquals(true, state.halted)
        assertNull(state.exitParams) // 스냅샷만 버린다(소비는 Phase 2 라 당장 영향 없음)
    }

    @Test
    fun `unknown sell reason falls back to MANUAL instead of dropping the pending sell`() = runTest {
        rows(
            TradingStateEntity(
                userId = 1L,
                ticker = "KRW-ETH",
                pendingSellUuid = "sell-1",
                pendingSellReason = "NO_SUCH_REASON",
            ),
        )

        val states = service.loadStates(1L)

        val state = states["KRW-ETH"]!!
        assertEquals("sell-1", state.pendingSellUuid)
        assertEquals(SellReason.MANUAL, state.pendingSellReason)
    }

    @Test
    fun `a corrupt row does not block the other tickers`() = runTest {
        rows(
            TradingStateEntity(userId = 1L, ticker = "KRW-BTC", exitParamsJson = "{broken"),
            TradingStateEntity(userId = 1L, ticker = "KRW-ETH", pendingBuyUuid = "pending-2"),
        )

        val states = service.loadStates(1L)

        assertEquals(setOf("KRW-BTC", "KRW-ETH"), states.keys)
        assertEquals("pending-2", states["KRW-ETH"]!!.pendingBuyUuid)
    }
}
