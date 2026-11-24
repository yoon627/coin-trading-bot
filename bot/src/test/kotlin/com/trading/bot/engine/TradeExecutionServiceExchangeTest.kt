package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/** 기록할 추정 수수료는 기록이 실어 온 거래소 요율을 쓰고, 없으면 지금(Upbit) 값이다. 알림 줄은 기록의 거래소를 쓴다. */
class TradeExecutionServiceExchangeTest {

    private val properties = TradingProperties()
    private val row = slot<TradeNotificationOutboxEntity>()
    private val outbox = mockk<TradeNotificationOutboxRepository> { every { save(capture(row)) } answers { Mono.just(firstArg()) } }
    private val ledger = mockk<OrderLedger>()
    private val service = TradeExecutionService(outbox, ledger, mockk(), properties)
    private val fee = slot<Double>()

    init {
        coEvery { ledger.recordFill(any(), capture(fee), FeeKind.ESTIMATED) } returns OrderLedger.FillRecord.Recorded(1L)
    }

    private fun sell(exchange: Exchange = Exchange.UPBIT, estimatedFeeRate: Double? = null) = TradeRecord(
        ticker = "KRX-005930", side = TradeSide.SELL, price = 70_000.0, volume = 10.0, totalAmount = 700_000.0,
        pnlPercent = 1.0, pnlAmount = 7_000.0, strategy = "combined", fee = FeeBasis.Estimate, orderAmount = null,
        exchangeOrderId = "o-1", userId = 1L, exchange = exchange, estimatedFeeRate = estimatedFeeRate,
    )

    @Test
    fun `the estimate takes the record's fee rate and the notification row its exchange`() = runTest {
        service.saveAudit(sell(Exchange.KIS_PAPER, estimatedFeeRate = 0.0022))

        assertEquals(700_000.0 * 0.0022, fee.captured, 1e-9)
        assertEquals("KIS_PAPER", row.captured.exchange)
    }

    @Test
    fun `without a rate the estimate is half the configured round trip`() = runTest {
        service.saveAudit(sell())

        assertEquals(700_000.0 * properties.roundTripFeeRate / 2.0, fee.captured, 1e-9)
    }

    @Test
    fun `a rate that is not a finite non-negative number falls back to the configured estimate`() = runTest {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.001)) {
            service.saveAudit(sell(Exchange.KIS_PAPER, estimatedFeeRate = rate))

            assertEquals(700_000.0 * properties.roundTripFeeRate / 2.0, fee.captured, 1e-9, "rate=$rate")
        }
    }
}
