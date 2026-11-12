package com.trading.bot.persistence

import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.entity.ManualInterventionEntity
import com.trading.bot.persistence.entity.TradingStateEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

class ManualInterventionServiceTest {
    private val repository = mockk<ManualInterventionRepository>()
    private val tradingStateService = mockk<TradingStateService>()
    private val operator = mockk<TransactionalOperator> { every { transactional(any<Mono<Any>>()) } answers { firstArg() } }
    private val mapper = productionObjectMapper()
    private val now = Instant.parse("2026-11-12T00:00:00Z")
    private val service = ManualInterventionService(repository, tradingStateService, operator, mapper, Clock.fixed(now, ZoneOffset.UTC))
    private val state = TradingState("KRW-BTC")

    @Test
    fun `the state and a record holding the row before the change go into one transaction`() = runTest {
        coEvery { tradingStateService.findRow(1L, state) } returns
            TradingStateEntity(
                id = 7L, userId = 1L, ticker = "KRW-BTC", reviewReason = "unknown order", pendingSellIdentifier = "ctb-s",
                reconcileFailureCount = 2, pendingBuyBookVolume = 0.002, buyDate = java.time.LocalDate.of(2026, 11, 12),
            )
        coEvery { tradingStateService.upsert(1L, state) } just Runs
        val row = slot<ManualInterventionEntity>()
        every { repository.save(capture(row)) } answers { Mono.just(firstArg()) }

        service.saveWithRecord(1L, state, ManualInterventionEntity.PENDING_CLEAR, mapOf("side" to "SELL"))

        assertEquals("UPBIT", row.captured.exchange)
        assertEquals(now, row.captured.createdAt)
        val detail = mapper.readTree(row.captured.detail)
        assertEquals("SELL", detail["side"].asText())
        val before = detail["before"]
        assertEquals("unknown order", before["review_reason"].asText())
        assertEquals("ctb-s", before["pending_sell_identifier"].asText())
        assertEquals(2, before["reconcile_failure_count"].asInt())
        assertEquals(0.002, before["pending_buy_book_volume"].asDouble())
        assertEquals("2026-11-12", before["buy_date"].asText())
        coVerify(exactly = 1) { operator.transactional(any<Mono<Any>>()) }
    }

    @Test
    fun `a record that cannot be saved fails the whole save`() = runTest {
        coEvery { tradingStateService.findRow(1L, state) } returns null
        coEvery { tradingStateService.upsert(1L, state) } just Runs
        every { repository.save(any()) } returns Mono.error(IllegalStateException("insert failed"))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { service.saveWithRecord(1L, state, ManualInterventionEntity.HALT_CLEAR, emptyMap()) }
        }
    }
}
