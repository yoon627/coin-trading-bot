package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.events.UserEventHub
import com.trading.bot.notification.TradeNotificationPayload
import com.trading.bot.notification.TradeNotificationQueued
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.entity.TradeNotificationOutboxEntity
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import reactor.core.publisher.Mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.reactive.TransactionalOperator

class TradeExecutionServiceTest {

    private lateinit var outboxRepository: TradeNotificationOutboxRepository
    private lateinit var orderLedger: OrderLedger
    private val events = mutableListOf<Any>()
    private lateinit var transactionalOperator: TransactionalOperator
    private lateinit var service: TradeExecutionService

    @BeforeEach
    fun setup() {
        outboxRepository = mockk()
        every { outboxRepository.save(any()) } answers { Mono.just(firstArg()) }
        transactionalOperator = mockk()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers { firstArg() }
        orderLedger = mockk()
        coEvery { orderLedger.recordFill(any(), any(), any()) } returns OrderLedger.FillRecord.Recorded(11L)
        service = TradeExecutionService(outboxRepository, orderLedger, transactionalOperator, TradingProperties(), events = { events += it })
    }

    private fun record(orderId: String, side: TradeSide = TradeSide.SELL, fee: FeeBasis = FeeBasis.Estimate, totalAmount: Double = 50000.0) = TradeRecord(
        ticker = "KRW-BTC", side = side, price = 50000000.0, volume = 0.001,
        totalAmount = totalAmount, pnlPercent = 1.5, pnlAmount = 750.0, strategy = "combined",
        exchangeOrderId = orderId, userId = 1L, fee = fee, orderAmount = 50000.0,
    )

    @Test
    fun `commitFill skips an order already recorded but still commits the state`() = runTest {
        // 재시작 후 같은 주문 uuid 를 다시 기록하려 하면 저장을 건너뛰고 false 를 돌려 호출자가 알림을 보내지 않는다.
        // pending 해소는 그래도 커밋돼야 다음 tick 이 같은 주문을 또 붙잡지 않는다.
        val duplicate = record("dup-1", side = TradeSide.BUY)
        coEvery { orderLedger.recordFill(duplicate, any(), any()) } returns OrderLedger.FillRecord.AlreadyRecorded
        val statePersisted = AtomicBoolean(false)

        val recorded = service.commitFill(persistState = { statePersisted.set(true) }, record = duplicate)

        assertFalse(recorded)
        assertTrue(statePersisted.get())
        // 이미 알린 체결이라 알림 행도 더 넣지 않는다.
        verify(exactly = 0) { outboxRepository.save(any()) }
        assertTrue(events.isEmpty())
    }

    // orders 줄이 실패하면 알림 행까지 함께 되돌아가야 해 알림 행보다 먼저 쓴다.
    @Test
    fun `commitFill writes the orders row before the notification row, which points at it`() = runTest {
        val order = mutableListOf<String>()
        coEvery { orderLedger.recordFill(any(), any(), any()) } coAnswers { order += "orders"; OrderLedger.FillRecord.Recorded(11L) }
        val row = slot<TradeNotificationOutboxEntity>()
        every { outboxRepository.save(capture(row)) } answers { order += "outbox"; Mono.just(firstArg()) }
        val fill = record("fill-2", fee = FeeBasis.Measured(25.0))

        assertTrue(service.commitFill(persistState = {}, record = fill))

        assertEquals(listOf("orders", "outbox"), order)
        coVerify(exactly = 1) { orderLedger.recordFill(fill, 25.0, FeeKind.MEASURED) }
        assertEquals(11L, row.captured.orderId)
    }

    // orders 는 모르는 수수료를 NULL 로 적고 근거를 따로 둔다.
    @Test
    fun `each fee basis is recorded with its kind, and an unknown fee is null`() = runTest {
        val cases = listOf(
            FeeBasis.Measured(25.0) to (25.0 to FeeKind.MEASURED),
            // public 생성자라 파싱 가드를 우회한 NaN 이 올 수 있다. 칼럼에 들어가면 SUM(fee) 이 영구히 NaN 이 된다.
            FeeBasis.Measured(Double.NaN) to (null to FeeKind.UNRECORDED),
            FeeBasis.Estimate to (25.0 to FeeKind.ESTIMATED),
            FeeBasis.Unrecorded to (null to FeeKind.UNRECORDED),
        )
        for ((basis, expected) in cases) {
            val fill = record("fee-$basis", fee = basis).copy(estimatedFeeRate = 0.0005)

            service.commitFill(persistState = {}, record = fill)

            coVerify { orderLedger.recordFill(fill, expected.first, expected.second) }
        }
    }

    // 수수료 공식은 한 곳에 있지만 어떤 기준을 쓸지는 경로가 정한다. totalAmount 가 그 체결의 대금이 아닌 경로(엔진 매수는
    // 포지션 전체 원가)에서 유도하면 수수료가 부풀려진다 — 실측이 있으면 실측을, 모르면 비워 둔다.
    @Test
    fun `the estimate is derived for buys too, a measured fee is kept verbatim, and an unknown one is not derived`() = runTest {
        val estimated = record("fee-buy", side = TradeSide.BUY, totalAmount = 100000.0)
        val measured = record("fee-measured", side = TradeSide.BUY, fee = FeeBasis.Measured(12.3), totalAmount = 1040000.0)
        val unknown = record("fee-unknown", side = TradeSide.BUY, fee = FeeBasis.Unrecorded, totalAmount = 1040000.0)

        for (fill in listOf(estimated, measured, unknown)) service.saveAudit(fill)

        // 편도 = 왕복(0.001)의 절반 → 100,000 × 0.0005 = 50원
        coVerify { orderLedger.recordFill(estimated, 50.0, FeeKind.ESTIMATED) }
        coVerify { orderLedger.recordFill(measured, 12.3, FeeKind.MEASURED) }
        coVerify { orderLedger.recordFill(unknown, null, FeeKind.UNRECORDED) }
    }

    @Test
    fun `commitFill puts exactly one notification row in the same commit and wakes the dispatcher after it`() = runTest {
        val order = mutableListOf<String>()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers {
            firstArg<Mono<Any>>().doOnSuccess { order += "committed" }
        }
        val row = slot<TradeNotificationOutboxEntity>()
        every { outboxRepository.save(capture(row)) } answers { order += "outbox"; Mono.just(firstArg()) }

        assertTrue(service.commitFill(persistState = {}, record = record("fill-1")))

        assertEquals(listOf("outbox", "committed"), order)
        assertEquals(TradeNotificationOutboxEntity.PENDING, row.captured.status)
        val payload = TradeNotificationPayload.fromJson(row.captured.payload)
        assertEquals("fill-1", payload.exchangeOrderId)
        assertEquals(1.5, payload.pnlPercent)
        assertFalse(row.captured.payload.contains("webhook"))
        assertEquals(listOf<Any>(TradeNotificationQueued), events)
    }

    // 신호는 커밋된 뒤에만 — 롤백된 체결을 화면이 다시 읽어도 바뀐 것이 없다.
    @Test
    fun `a recorded fill signals trade and state, a fill already recorded only state, a failed commit nothing`() = runTest {
        val hub = UserEventHub()
        val signaling = TradeExecutionService(outboxRepository, orderLedger, transactionalOperator, TradingProperties(), userEvents = hub)

        signaling.commitFill(persistState = {}, record = record("fill-3"))
        assertEquals(1L, hub.versions(1L)[UserEventHub.Kind.TRADE.ordinal])
        assertEquals(1L, hub.versions(1L)[UserEventHub.Kind.STATE.ordinal])

        coEvery { orderLedger.recordFill(any(), any(), any()) } returns OrderLedger.FillRecord.AlreadyRecorded
        signaling.commitFill(persistState = {}, record = record("fill-3"))
        assertEquals(1L, hub.versions(1L)[UserEventHub.Kind.TRADE.ordinal])
        assertEquals(2L, hub.versions(1L)[UserEventHub.Kind.STATE.ordinal])

        coEvery { orderLedger.recordFill(any(), any(), any()) } returns OrderLedger.FillRecord.Recorded(12L)
        every { outboxRepository.save(any()) } returns Mono.error(IllegalStateException("db down"))
        assertTrue(runCatching { signaling.commitFill(persistState = {}, record = record("fill-4")) }.isFailure)
        assertEquals(1L, hub.versions(1L)[UserEventHub.Kind.TRADE.ordinal])
        assertEquals(2L, hub.versions(1L)[UserEventHub.Kind.STATE.ordinal])
    }

    @Test
    fun `a commit that fails wakes nothing`() = runTest {
        every { outboxRepository.save(any()) } returns Mono.error(IllegalStateException("db down"))

        assertTrue(runCatching { service.commitFill(persistState = {}, record = record("fill-2", side = TradeSide.BUY)) }.isFailure)
        assertTrue(events.isEmpty())
    }
}
