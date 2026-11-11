package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.TradingStateEntity
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TradingStateServiceTest {

    private val repository: TradingStateRepository = mockk()
    private val service = TradingStateService(repository, ObjectMapper())

    private fun rows(vararg entities: TradingStateEntity) {
        every { repository.findByUserId(1L) } returns Flux.fromIterable(entities.toList())
    }

    @Test
    fun `a single ticker row is read with the same mapping, and a missing row is null`() = runTest {
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns
            Mono.just(TradingStateEntity(userId = 1L, ticker = "KRW-BTC", pendingBuyIdentifier = "ctb-1", pendingBuyStrategy = "combined"))
        every { repository.findByUserIdAndTicker(1L, "KRW-ETH") } returns Mono.empty()

        assertEquals("ctb-1", service.loadState(1L, "KRW-BTC")?.pendingBuyIdentifier)
        assertNull(service.loadState(1L, "KRW-ETH"))
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
        assertNull(state.exitParams) // 스냅샷만 버리고 전역 청산값으로 폴백한다
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
    fun `order identifiers survive a round trip`() = runTest {
        // uuid 를 받기 전에 끊긴 주문을 재시작 뒤 찾을 유일한 근거다. 두 방향이 서로 바뀌지 않게 값을 다르게 둔다.
        val domain = com.trading.bot.domain.TradingState(
            ticker = "KRW-BTC",
            pendingBuyIdentifier = "ctb-buy",
            pendingSellIdentifier = "ctb-sell",
        )
        val saved = slot<TradingStateEntity>()
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns reactor.core.publisher.Mono.empty()
        every { repository.save(capture(saved)) } answers { reactor.core.publisher.Mono.just(saved.captured) }

        service.upsert(1L, domain)
        rows(saved.captured)
        val restored = service.loadStates(1L)["KRW-BTC"]!!

        assertEquals("ctb-buy", restored.pendingBuyIdentifier)
        assertEquals("ctb-sell", restored.pendingSellIdentifier)
    }

    @Test
    fun `stuck-sell alert fields survive a round trip`() = runTest {
        // 재시작 후 알림 판정의 근거라 매핑이 빠지지 않게 왕복을 본다.
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
        // 시작 시각이 없는 pending 은 PositionManager 가 지금부터 센다.
        rows(TradingStateEntity(userId = 1L, ticker = "KRW-BTC", pendingSellUuid = "old"))

        val restored = service.loadStates(1L)["KRW-BTC"]!!

        assertNull(restored.pendingSellSince)
        assertEquals(false, restored.pendingSellAlerted)
    }

    // --- 봇 장부(V34) ---

    private val writtenAt = Instant.parse("2026-11-10T00:00:00Z")

    private fun storedRow(book: Double?, bookWrittenAt: Instant? = writtenAt, updatedAt: Instant = writtenAt, pendingBuy: Boolean = false) =
        TradingStateEntity(
            id = 7L, userId = 1L, ticker = "KRW-BTC", bookVolume = book, bookAvgPrice = 50_000_000.0,
            bookWrittenAt = bookWrittenAt, updatedAt = updatedAt, pendingBuyUuid = if (pendingBuy) "b-1" else null,
        )

    private fun savedOnUpsert(existing: TradingStateEntity?, state: TradingState): TradingStateEntity {
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns Mono.justOrEmpty(existing)
        val saved = slot<TradingStateEntity>()
        every { repository.save(capture(saved)) } answers { Mono.just(saved.captured) }
        kotlinx.coroutines.runBlocking { service.upsert(1L, state) }
        return saved.captured
    }

    @Test
    fun `a known book is written with the same stamp as the row`() {
        val row = savedOnUpsert(storedRow(0.01), TradingState("KRW-BTC", position = true, holdVolume = 0.02, avgBuyPrice = 1.0, bookKnown = true))

        assertEquals(0.02, row.bookVolume)
        assertEquals(1.0, row.bookAvgPrice)
        assertEquals(row.updatedAt, row.bookWrittenAt)
    }

    // 로드 직후·동기화 실패 상태를 저장할 때 0 을 쓰면 봇 포지션이 관리 밖으로 빠진다. 옛 버전이 쓴 낡은 표시도 그대로 둔다.
    @Test
    fun `an unknown book keeps the row's book and its stamp`() {
        val stale = storedRow(0.01, bookWrittenAt = writtenAt, updatedAt = writtenAt.plusSeconds(60))

        val row = savedOnUpsert(stale, TradingState("KRW-BTC"))

        assertEquals(0.01, row.bookVolume)
        assertEquals(writtenAt, row.bookWrittenAt)
        assertTrue(row.updatedAt != row.bookWrittenAt)
    }

    @Test
    fun `a book last saved by an older version is not trusted, and a row without one reads as no book`() = runTest {
        rows(
            storedRow(0.01).copy(ticker = "KRW-BTC"),
            storedRow(0.02, updatedAt = writtenAt.plusSeconds(60)).copy(id = 8L, ticker = "KRW-ETH"),
            storedRow(null, bookWrittenAt = null).copy(id = 9L, ticker = "KRW-XRP"),
        )

        val states = service.loadStates(1L)

        assertEquals(0.01, states.getValue("KRW-BTC").storedBook?.volume)
        assertNull(states.getValue("KRW-ETH").storedBook)
        assertNull(states.getValue("KRW-XRP").storedBook)
    }

    // 새 버전이 동기화 전에 저장해도 믿을 수 있던 장부는 계속 믿는다. 표시를 옮기지 않으면 옛 버전이 쓴 행과 구분이 안 된다.
    @Test
    fun `an unknown book carried from a valid row stays valid`() {
        val row = savedOnUpsert(storedRow(0.01), TradingState("KRW-BTC"))

        assertEquals(0.01, row.bookVolume)
        assertEquals(row.updatedAt, row.bookWrittenAt)
    }

    // pending/clear 는 봇이 멈춘 채 행을 읽어 정리하고 다시 저장한다. 그 저장이 장부를 낡게 만들면 다음 기동이 잔고 전체를 편입한다.
    @Test
    fun `a pending clear saved before the first sync keeps the stored book for the next load`() = runTest {
        val stored = storedRow(0.01).copy(pendingSellUuid = "s-1", pendingSellVolume = 0.01, pendingSellPriorVolume = 0.06)
        every { repository.findByUserIdAndTicker(1L, "KRW-BTC") } returns Mono.just(stored)
        val state = service.loadState(1L, "KRW-BTC")!!.apply { releasePending(TradeSide.SELL, LocalDate.of(2026, 11, 10)) }

        val row = savedOnUpsert(stored, state)
        rows(row)

        val next = service.loadStates(1L).getValue("KRW-BTC")
        assertEquals(0.01, next.storedBook?.volume)
        assertEquals(0.06, next.sellClearPriorVolume)
    }

    // 재시작 전 걸어 둔 매수의 체결은 주문 때 장부 위에만 더한다. 그 값은 pending 과 함께 저장된다.
    @Test
    fun `the order-time book of a pending buy is saved and restored with the pending`() = runTest {
        val row = savedOnUpsert(null, TradingState("KRW-BTC", pendingBuyUuid = "b-1", pendingBuyBookVolume = 0.0001, pendingBuyBookAvg = 4.0))
        rows(row)

        val restored = service.loadStates(1L).getValue("KRW-BTC")

        assertEquals(0.0001, restored.pendingBuyBookVolume)
        assertEquals(4.0, restored.pendingBuyBookAvg)
    }
}
