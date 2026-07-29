package com.trading.bot.persistence

import com.trading.bot.kis.engine.StockPosition
import com.trading.bot.persistence.entity.StockPositionStateEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StockPositionStateServiceTest {

    private val repository = mockk<StockPositionStateRepository>()
    private val service = StockPositionStateService(repository)
    private val today = LocalDate.of(2026, 7, 29)

    private fun row(symbol: String, peak: Double, boughtDate: LocalDate?, strategy: String? = "rsi") =
        StockPositionStateEntity(
            id = 1, userId = 1L, exchange = "KIS", symbol = symbol,
            peakPrice = peak, boughtDate = boughtDate, entryStrategy = strategy,
        )

    @Test
    fun `restores trailing peak and entry strategy`() = runTest {
        every { repository.findByUserIdAndExchange(1L, "KIS") } returns
            Flux.just(row("005930", peak = 82_000.0, boughtDate = today))
        val pos = StockPosition("005930")

        service.loadInto(1L, mapOf("005930" to pos), today)

        // 고점을 잃으면 진입가로 리셋돼 트레일링이 발동하지 않는다 — #64 의 핵심.
        assertEquals(82_000.0, pos.peakPrice)
        assertEquals("rsi", pos.entryStrategy)
    }

    @Test
    fun `bought flag is recomputed from stored trading day, not restored blindly`() = runTest {
        every { repository.findByUserIdAndExchange(1L, "KIS") } returns
            Flux.just(
                row("005930", peak = 0.0, boughtDate = today),                  // 오늘 매수 → 재진입 차단 유지
                row("000660", peak = 0.0, boughtDate = today.minusDays(1)),     // 어제 매수 → 오늘은 진입 가능
            )
        val a = StockPosition("005930")
        val b = StockPosition("000660")

        service.loadInto(1L, mapOf("005930" to a, "000660" to b), today)

        assertTrue(a.boughtToday, "같은 거래일 재시작이면 당일 진입 게이트가 유지돼야 한다")
        assertFalse(b.boughtToday, "거래일이 바뀌었으면 게이트가 열려야 한다")
    }

    @Test
    fun `symbols no longer in watchlist are ignored`() = runTest {
        every { repository.findByUserIdAndExchange(1L, "KIS") } returns
            Flux.just(row("999999", peak = 100.0, boughtDate = today))
        val pos = StockPosition("005930")

        service.loadInto(1L, mapOf("005930" to pos), today)

        assertEquals(0.0, pos.peakPrice)
    }

    @Test
    fun `upsert reuses existing row id`() = runTest {
        every { repository.findByUserIdAndExchangeAndSymbol(1L, "KIS", "005930") } returns
            Mono.just(row("005930", peak = 1.0, boughtDate = null).copy(id = 42))
        val saved = slot<StockPositionStateEntity>()
        coEvery { repository.save(capture(saved)) } answers { Mono.just(saved.captured) }

        val pos = StockPosition("005930").apply {
            peakPrice = 90_000.0
            markBoughtAccepted("macd", today)
        }
        service.upsert(1L, pos)

        // id 를 실으면 UPDATE, 비우면 INSERT — unique index 위반으로 저장이 통째로 실패한다.
        assertEquals(42L, saved.captured.id)
        assertEquals(90_000.0, saved.captured.peakPrice)
        assertEquals(today, saved.captured.boughtDate)
        assertEquals("macd", saved.captured.entryStrategy)
    }

    @Test
    fun `upsert inserts when no row exists`() = runTest {
        every { repository.findByUserIdAndExchangeAndSymbol(1L, "KIS", "005930") } returns Mono.empty()
        val saved = slot<StockPositionStateEntity>()
        coEvery { repository.save(capture(saved)) } answers { Mono.just(saved.captured) }

        service.upsert(1L, StockPosition("005930").apply { peakPrice = 100.0 })

        assertNull(saved.captured.id)
    }
}
