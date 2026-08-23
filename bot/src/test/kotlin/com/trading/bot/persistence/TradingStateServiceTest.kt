package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.SellReason
import com.trading.bot.persistence.entity.TradingStateEntity
import io.mockk.every
import io.mockk.slot
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

    @Test
    fun `stuck-sell alert fields survive a round trip`() = runTest {
        // 이 repo 는 매핑 누락으로 컬럼이 통째로 유실된 전례가 있다(market_tickers.volume_24h).
        // 새 필드는 재시작 후 알림 판정의 근거이므로 왕복을 고정한다(#55).
        val since = java.time.Instant.parse("2026-08-22T00:00:00Z")
        val domain = com.trading.bot.domain.TradingState(
            ticker = "KRW-BTC",
            pendingSellUuid = "s1",
            pendingSellReason = SellReason.TAKE_PROFIT,
            pendingSellSince = since,
            pendingSellAlerted = true,
        )

        // 저장 방향: upsert 가 만드는 엔티티에 두 필드가 실려야 한다.
        val saved = slot<TradingStateEntity>()
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns reactor.core.publisher.Mono.empty()
        every { repository.save(capture(saved)) } answers {
            reactor.core.publisher.Mono.just(saved.captured)
        }
        service.upsert(1L, domain)
        assertEquals(since, saved.captured.pendingSellSince)
        assertEquals(true, saved.captured.pendingSellAlerted)

        // 복원 방향: 그 엔티티가 도메인으로 되돌아와야 한다.
        rows(saved.captured)
        val restored = service.loadStates(1L)["KRW-BTC"]!!

        assertEquals(since, restored.pendingSellSince)
        assertEquals(true, restored.pendingSellAlerted)
    }

    @Test
    fun `a pending sell without a recorded start time restores as null`() = runTest {
        // V20 이전에 시작된 pending — 판정은 "지금부터" 세도록 PositionManager 가 처리한다.
        rows(TradingStateEntity(userId = 1L, ticker = "KRW-BTC", pendingSellUuid = "old"))

        val restored = service.loadStates(1L)["KRW-BTC"]!!

        assertNull(restored.pendingSellSince)
        assertEquals(false, restored.pendingSellAlerted)
    }
}
