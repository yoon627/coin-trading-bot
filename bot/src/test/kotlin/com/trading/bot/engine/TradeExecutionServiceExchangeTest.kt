package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/** 감사 행의 거래소와 추정 수수료는 기록이 실어 온 값을 쓰고, 없으면 지금(Upbit) 값이다. */
class TradeExecutionServiceExchangeTest {

    private val executions = mockk<TradeExecutionRepository>()
    private val properties = TradingProperties()
    private val service = TradeExecutionService(mockk<TradeRecordRepository>(relaxed = true), executions, mockk(relaxed = true), mockk(), properties)
    private val saved = slot<TradeExecutionEntity>()

    init {
        every { executions.existsByUserIdAndExchangeOrderId(any(), any()) } returns Mono.just(false)
        every { executions.save(capture(saved)) } answers { Mono.just(firstArg()) }
    }

    private fun sell(exchange: Exchange = Exchange.UPBIT, estimatedFeeRate: Double? = null) = TradeRecord(
        ticker = "KRX-005930", side = TradeSide.SELL, price = 70_000.0, volume = 10.0, totalAmount = 700_000.0,
        pnlPercent = 1.0, pnlAmount = 7_000.0, strategy = "combined", fee = FeeBasis.Estimate, orderAmount = null,
        exchangeOrderId = "o-1", userId = 1L, exchange = exchange, estimatedFeeRate = estimatedFeeRate,
    )

    @Test
    fun `the audit row takes the record's exchange and fee rate`() = runTest {
        service.saveAudit(sell(Exchange.KIS_PAPER, estimatedFeeRate = 0.0022))

        assertEquals("KIS_PAPER", saved.captured.exchange)
        assertEquals(700_000.0 * 0.0022, saved.captured.fee, 1e-9)
    }

    @Test
    fun `without a rate the estimate is half the configured round trip and the exchange is UPBIT`() = runTest {
        service.saveAudit(sell())

        assertEquals("UPBIT", saved.captured.exchange)
        assertEquals(700_000.0 * properties.roundTripFeeRate / 2.0, saved.captured.fee, 1e-9)
    }

    @Test
    fun `a rate that is not a finite non-negative number falls back to the configured estimate`() = runTest {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.001)) {
            service.saveAudit(sell(Exchange.KIS_PAPER, estimatedFeeRate = rate))

            assertEquals(700_000.0 * properties.roundTripFeeRate / 2.0, saved.captured.fee, 1e-9, "rate=$rate")
        }
    }
}
