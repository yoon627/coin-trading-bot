package com.trading.bot.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.entity.TradeRecordEntity
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Sort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TradeRecordRepositoryExchangeTest {

    private val r2dbc: TradeRecordR2dbcRepository = mockk()
    private val repository = TradeRecordRepository(r2dbc)

    @Test
    fun `save carries the record's exchange onto the entity`() = runTest {
        val entity = slot<TradeRecordEntity>()
        every { r2dbc.save(capture(entity)) } answers { Mono.just(firstArg()) }

        repository.save(
            TradeRecord(
                ticker = "KRX-005930", side = TradeSide.BUY, price = 70_000.0, volume = 3.0, totalAmount = 210_000.0,
                pnlPercent = null, pnlAmount = null, strategy = "combined", fee = FeeBasis.Estimate, orderAmount = 210_000.0,
                userId = 1L, exchange = Exchange.KIS_PAPER,
            ),
        )

        assertEquals("KIS_PAPER", entity.captured.exchange)
    }

    @Test
    fun `the trade history json does not gain the exchange column`() {
        val json = jacksonObjectMapper().findAndRegisterModules().valueToTree<JsonNode>(
            TradeRecordEntity(ticker = "KRW-BTC", side = "BUY", price = 1.0, volume = 1.0, totalAmount = 1.0, userId = 1L),
        )

        assertFalse(json.has("exchange"), json.toString())
    }

    @Test
    fun `history queries filter by the exchange name`() = runTest {
        val row = TradeRecordEntity(ticker = "KRX-005930", side = "BUY", price = 1.0, volume = 1.0, totalAmount = 1.0, userId = 1L, exchange = "KIS_PAPER")
        every { r2dbc.findByUserIdAndExchange(1L, "KIS_PAPER", any()) } returns Flux.just(row)
        every { r2dbc.countByUserIdAndExchange(1L, "KIS_PAPER") } returns Mono.just(1L)

        assertEquals(listOf(row), repository.findByUserIdAndExchange(1L, Exchange.KIS_PAPER, limit = 10, offset = 0))
        assertEquals(listOf(row), repository.findRecentAscending(1L, Exchange.KIS_PAPER, max = 10))
        assertEquals(1L, repository.countByUserIdAndExchange(1L, Exchange.KIS_PAPER))
        verify { r2dbc.findByUserIdAndExchange(1L, "KIS_PAPER", Sort.by(Sort.Direction.DESC, "createdAt")) }
        verify { r2dbc.findByUserIdAndExchange(1L, "KIS_PAPER", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))) }
    }
}
