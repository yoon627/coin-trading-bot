package com.trading.bot.characterization

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.engine.OrderLedger
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.common.config.TradingProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * 체결 기록(orders 줄)의 Upbit 고정값을 고정한다. exchange 는 "UPBIT", 실측 수수료는 그대로, paid_fee 없는 매도의 수수료는
 * 대금 × 0.001 / 2 로 추정하고, 같은 사용자·거래소에 이미 체결로 기록된 거래소 주문 id 는 쓰지 않고 건너뛴다.
 */
class TradeAuditCharacterizationTest {

    private val orders = mockk<OrderRowRepository>()
    private val outbox = mockk<TradeNotificationOutboxRepository> { every { save(any()) } answers { Mono.just(firstArg()) } }
    private val service = TradeExecutionService(outbox, OrderLedger(orders), mockk(), TradingProperties())
    private val written = mutableListOf<Map<String, Any?>>()

    @BeforeEach
    fun setup() {
        every { orders.findByExchangeOrderId(any(), any(), any()) } returns Mono.empty()
        every { orders.findByClientOrderId(any(), any(), any()) } returns Mono.empty()
        every {
            orders.insertFilled(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(),
            )
        } answers {
            written += mapOf("exchange" to args[1], "market" to args[2], "side" to args[3], "exchangeOrderId" to args[5], "fee" to args[11])
            Mono.just(1L)
        }
    }

    private fun sell(fee: FeeBasis, orderId: String = "s-1") = TradeRecord(
        ticker = "KRW-BTC", side = TradeSide.SELL, price = 50_000_000.0, volume = 0.002, totalAmount = 100_000.0,
        pnlPercent = 1.0, pnlAmount = 1_000.0, strategy = "combined", fee = fee, orderAmount = 100_000.0,
        reason = "TAKE_PROFIT", exchangeOrderId = orderId, userId = 1L,
    )

    @Test
    fun `every fill row is written with exchange UPBIT and a measured fee as is`() = runTest {
        // 추정치(100,000 × 0.001 / 2 = 50)와 다른 값이라야 실측을 그대로 쓰는지 가른다.
        assertTrue(service.saveAudit(sell(FeeBasis.Measured(37.0))))

        assertEquals(mapOf("exchange" to "UPBIT", "market" to "KRW-BTC", "side" to "SELL", "exchangeOrderId" to "s-1", "fee" to 37.0), written.single())
    }

    @Test
    fun `a sell whose order has no paid fee is recorded with the estimated fee, amount times 1e-3 over 2`() = runTest {
        val feeBasis = Order(uuid = "s-1", state = "done", paidFee = null).sellFeeBasis()

        service.saveAudit(sell(feeBasis))

        assertEquals(FeeBasis.Estimate, feeBasis)
        assertEquals(100_000.0 * 0.001 / 2, written.single()["fee"])
    }

    @Test
    fun `an exchange order id already filled for the user on that exchange is skipped without any write`() = runTest {
        every { orders.findByExchangeOrderId(1L, "UPBIT", "s-1") } returns
            Mono.just(OrderRowEntity(id = 3, userId = 1, exchange = "UPBIT", market = "KRW-BTC", side = "SELL", status = "FILLED", exchangeOrderId = "s-1"))

        assertFalse(service.saveAudit(sell(FeeBasis.Estimate)))

        assertTrue(written.isEmpty())
        verify(exactly = 0) { outbox.save(any()) }
    }
}
