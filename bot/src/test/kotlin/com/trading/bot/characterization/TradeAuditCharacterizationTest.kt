package com.trading.bot.characterization

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.common.config.TradingProperties
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * 거래 감사 기록(trade_executions)의 Upbit 고정값을 고정한다. exchange 는 "UPBIT", 실측 수수료는 그대로, paid_fee 없는 매도의 수수료는
 * 대금 × 0.001 / 2 로 추정하고, 이미 기록된 거래소 주문 id 는 저장하지 않고 건너뛴다.
 */
class TradeAuditCharacterizationTest {

    private val tradeRecordRepository = mockk<TradeRecordRepository>(relaxed = true)
    private val tradeExecutionRepository = mockk<TradeExecutionRepository>()
    private val service = TradeExecutionService(
        tradeRecordRepository, tradeExecutionRepository, mockk(relaxed = true), mockk(), TradingProperties(),
    )
    private val saved = slot<TradeExecutionEntity>()

    @BeforeEach
    fun setup() {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(any(), any()) } returns Mono.just(false)
        every { tradeExecutionRepository.save(capture(saved)) } answers { Mono.just(saved.captured) }
    }

    private fun sell(fee: FeeBasis, orderId: String = "s-1") = TradeRecord(
        ticker = "KRW-BTC", side = TradeSide.SELL, price = 50_000_000.0, volume = 0.002, totalAmount = 100_000.0,
        pnlPercent = 1.0, pnlAmount = 1_000.0, strategy = "combined", fee = fee, orderAmount = 100_000.0,
        reason = "TAKE_PROFIT", exchangeOrderId = orderId, userId = 1L,
    )

    @Test
    fun `every audit row is written with exchange UPBIT and a measured fee as is`() = runTest {
        // 추정치(100,000 × 0.001 / 2 = 50)와 다른 값이라야 실측을 그대로 쓰는지 가른다.
        assertTrue(service.saveAudit(sell(FeeBasis.Measured(37.0))))

        assertEquals("UPBIT", saved.captured.exchange)
        assertEquals("KRW-BTC", saved.captured.market)
        assertEquals("SELL", saved.captured.side)
        assertEquals("s-1", saved.captured.exchangeOrderId)
        assertEquals(37.0, saved.captured.fee)
    }

    @Test
    fun `a sell whose order has no paid fee is recorded with the estimated fee, amount times 1e-3 over 2`() = runTest {
        val feeBasis = Order(uuid = "s-1", state = "done", paidFee = null).sellFeeBasis()

        service.saveAudit(sell(feeBasis))

        assertEquals(FeeBasis.Estimate, feeBasis)
        assertEquals(100_000.0 * 0.001 / 2, saved.captured.fee)
    }

    @Test
    fun `an exchange order id already recorded for the user is skipped without any write`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "s-1") } returns Mono.just(true)

        assertFalse(service.saveAudit(sell(FeeBasis.Estimate)))

        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        verify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }
}
