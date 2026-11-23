package com.trading.bot.engine

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.notification.TradeNotificationPayload
import com.trading.bot.notification.TradeNotificationQueued
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
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

    private lateinit var tradeRecordRepository: TradeRecordRepository
    private lateinit var tradeExecutionRepository: TradeExecutionRepository
    private lateinit var outboxRepository: TradeNotificationOutboxRepository
    private lateinit var orderLedger: OrderLedger
    private val events = mutableListOf<Any>()
    private lateinit var transactionalOperator: TransactionalOperator
    private lateinit var service: TradeExecutionService

    @BeforeEach
    fun setup() {
        tradeRecordRepository = mockk(relaxed = true)
        tradeExecutionRepository = mockk(relaxed = true)
        outboxRepository = mockk()
        every { outboxRepository.save(any()) } answers { Mono.just(firstArg()) }
        // stub 이 없으면 relaxed mockk 의 Mono 가 emit 하지 않아 saveAudit 의 awaitSingle 이 끝나지 않는다.
        every { tradeExecutionRepository.save(any()) } answers { Mono.just(firstArg<TradeExecutionEntity>().copy(id = 7L)) }
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(any(), any()) } returns Mono.just(false)
        transactionalOperator = mockk()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers { firstArg() }
        orderLedger = mockk()
        coEvery { orderLedger.recordFill(any(), any(), any(), any(), any()) } returns 11L
        service = TradeExecutionService(
            tradeRecordRepository, tradeExecutionRepository, outboxRepository, orderLedger, transactionalOperator,
            TradingProperties(), events = { events += it },
        )
    }

    @Test
    fun `commitFill skips an order already recorded but still commits the state`() = runTest {
        // 재시작 후 같은 주문 uuid 를 다시 기록하려 하면 저장을 건너뛰고 false 를 돌려 호출자가 알림을 보내지 않는다.
        // pending 해소는 그래도 커밋돼야 다음 tick 이 같은 주문을 또 붙잡지 않는다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "dup-1") } returns Mono.just(true)
        val statePersisted = AtomicBoolean(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "dup-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        val recorded = service.commitFill(persistState = { statePersisted.set(true) }, record = record)

        assertFalse(recorded)
        assertTrue(statePersisted.get())
        coVerify(exactly = 0) { tradeRecordRepository.save(any()) }
        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
        coVerify(exactly = 0) { orderLedger.recordFill(any(), any(), any(), any(), any()) }
        // 이미 알린 체결이라 알림 행도 더 넣지 않는다.
        verify(exactly = 0) { outboxRepository.save(any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `commitFill records an order it has not seen before`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "new-1") } returns Mono.just(false)
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "new-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        val recorded = service.commitFill(persistState = {}, record = record)

        assertTrue(recorded)
        coVerify(exactly = 1) { tradeRecordRepository.save(record) }
        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    // orders 줄은 trade_executions 의 id 를 알아야 하고, 실패하면 알림 행까지 함께 되돌아가야 해 그 사이에 쓴다.
    @Test
    fun `commitFill writes the orders row after the execution row and before the notification row with the fee it recorded`() = runTest {
        val order = mutableListOf<String>()
        every { tradeExecutionRepository.save(any()) } answers {
            order += "execution"; Mono.just(firstArg<TradeExecutionEntity>().copy(id = 7L))
        }
        coEvery { tradeRecordRepository.save(any()) } returns mockk { every { id } returns 3L }
        coEvery { orderLedger.recordFill(any(), any(), any(), any(), any()) } coAnswers { order += "orders"; 11L }
        every { outboxRepository.save(any()) } answers { order += "outbox"; Mono.just(firstArg()) }
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.SELL, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = 1.5, pnlAmount = 750.0, strategy = "combined",
            exchangeOrderId = "fill-2", userId = 1L,
            fee = FeeBasis.Measured(25.0),
            orderAmount = 50000.0,
        )

        assertTrue(service.commitFill(persistState = {}, record = record))

        assertEquals(listOf("execution", "orders", "outbox"), order)
        coVerify(exactly = 1) { orderLedger.recordFill(record, 25.0, FeeKind.MEASURED, 7L, 3L) }
    }

    // 알림 대기열 줄은 같은 트랜잭션에서 방금 넣은 orders 줄을 가리킨다. orders 줄을 넣지 못했으면 비워 둔다.
    @Test
    fun `the notification row points at the orders row the same commit wrote`() = runTest {
        val rows = mutableListOf<TradeNotificationOutboxEntity>()
        every { outboxRepository.save(capture(rows)) } answers { Mono.just(firstArg()) }
        coEvery { orderLedger.recordFill(any(), any(), any(), any(), any()) } returnsMany listOf(11L, null)
        fun record(id: String) = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.SELL, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = 1.5, pnlAmount = 750.0, strategy = "combined",
            exchangeOrderId = id, userId = 1L, fee = FeeBasis.Estimate, orderAmount = 50000.0,
        )

        service.commitFill(persistState = {}, record = record("link-1"))
        service.commitFill(persistState = {}, record = record("link-2"))

        assertEquals(listOf(11L, null), rows.map { it.orderId })
    }

    // orders 는 모르는 수수료를 NULL 로 적고 근거를 따로 둔다. trade_executions 는 지금처럼 0 을 미기록으로 쓴다.
    @Test
    fun `each fee basis is recorded with its kind, and an unknown fee is null in orders and 0 in trade executions`() = runTest {
        val cases = listOf(
            FeeBasis.Measured(25.0) to (25.0 to FeeKind.MEASURED),
            FeeBasis.Measured(Double.NaN) to (null to FeeKind.UNRECORDED),
            FeeBasis.Estimate to (25.0 to FeeKind.ESTIMATED),
            FeeBasis.Unrecorded to (null to FeeKind.UNRECORDED),
        )
        for ((basis, expected) in cases) {
            val executionRow = slot<TradeExecutionEntity>()
            every { tradeExecutionRepository.save(capture(executionRow)) } answers { Mono.just(firstArg<TradeExecutionEntity>().copy(id = 7L)) }
            val record = TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.SELL, price = 50000000.0, volume = 0.001,
                totalAmount = 50000.0, pnlPercent = 1.5, pnlAmount = 750.0, strategy = "combined",
                exchangeOrderId = "fee-$basis", userId = 1L, fee = basis, orderAmount = 50000.0, estimatedFeeRate = 0.0005,
            )

            service.commitFill(persistState = {}, record = record)

            coVerify { orderLedger.recordFill(record, expected.first, expected.second, 7L, any()) }
            assertEquals(expected.first ?: 0.0, executionRow.captured.fee, "$basis")
        }
    }

    @Test
    fun `commitFill puts exactly one notification row in the same commit and wakes the dispatcher after it`() = runTest {
        val order = mutableListOf<String>()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers {
            firstArg<Mono<Any>>().doOnSuccess { order += "committed" }
        }
        every { outboxRepository.save(any()) } answers { order += "outbox"; Mono.just(firstArg()) }
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.SELL, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = 1.5, pnlAmount = 750.0, strategy = "combined",
            exchangeOrderId = "fill-1", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = 50000.0,
        )
        val row = slot<TradeNotificationOutboxEntity>()
        every { outboxRepository.save(capture(row)) } answers { order += "outbox"; Mono.just(firstArg()) }

        assertTrue(service.commitFill(persistState = {}, record = record))

        assertEquals(listOf("outbox", "committed"), order)
        assertEquals(7L, row.captured.tradeExecutionId)
        assertEquals(TradeNotificationOutboxEntity.PENDING, row.captured.status)
        val payload = TradeNotificationPayload.fromJson(row.captured.payload)
        assertEquals("fill-1", payload.exchangeOrderId)
        assertEquals(1.5, payload.pnlPercent)
        assertFalse(row.captured.payload.contains("webhook"))
        assertEquals(listOf<Any>(TradeNotificationQueued), events)
    }

    @Test
    fun `a commit that fails wakes nothing`() = runTest {
        every { outboxRepository.save(any()) } returns Mono.error(IllegalStateException("db down"))
        val record = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.001,
            totalAmount = 50000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
            exchangeOrderId = "fill-2", userId = 1L,
            fee = FeeBasis.Estimate,
            orderAmount = null,
        )

        assertTrue(runCatching { service.commitFill(persistState = {}, record = record) }.isFailure)
        assertTrue(events.isEmpty())
    }

    // --- 감사 기록의 수수료 ---
    // 수수료 공식은 saveAudit 한 곳에 있지만 어떤 기준을 쓸지는 경로가 정한다. totalAmount 가 그 체결의
    // 대금이 아닌 경로(엔진 매수는 포지션 전체 원가)에서 유도하면 수수료가 부풀려진다.

    @Test
    fun `saveAudit derives the fee for buy rows too`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-buy") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 50000000.0, volume = 0.002,
                totalAmount = 100000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                exchangeOrderId = "fee-buy", userId = 1L,
                fee = FeeBasis.Estimate,
                orderAmount = null,
            )
        )

        // 편도 = 왕복(0.001)의 절반 → 100,000 × 0.0005 = 50원
        assertEquals(50.0, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit stores the measured fee verbatim instead of deriving it`() = runTest {
        // totalAmount(1,040,000)로 유도하면 520원이 된다. 실측이 있으면 실측을 쓴다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-measured") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Measured(12.3),
                orderAmount = null,
                exchangeOrderId = "fee-measured", userId = 1L,
            )
        )

        assertEquals(12.3, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit leaves the fee unrecorded rather than deriving a wrong one`() = runTest {
        // basis 를 모르는 경로(주문 응답 없음). 0 은 미기록을 뜻한다.
        // 추정으로 채우면 부풀려진 값이 맞는 값과 구분되지 않는다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-unknown") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Unrecorded,
                orderAmount = null,
                exchangeOrderId = "fee-unknown", userId = 1L,
            )
        )

        assertEquals(0.0, entity.captured.fee, 1e-9)
    }

    @Test
    fun `saveAudit refuses a measured fee that would poison the column`() = runTest {
        // FeeBasis.Measured 는 public 생성자라 파싱 가드를 우회한 값이 들어올 수 있다.
        // NaN 이 double precision 컬럼에 들어가면 이후 SUM(fee) 이 영구히 NaN 이 된다.
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fee-nan") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.BUY, price = 52000000.0, volume = 0.02,
                totalAmount = 1040000.0, pnlPercent = null, pnlAmount = null, strategy = "combined",
                fee = FeeBasis.Measured(Double.NaN),
                orderAmount = null,
                exchangeOrderId = "fee-nan", userId = 1L,
            )
        )

        assertEquals(0.0, entity.captured.fee, 1e-9)
        assertFalse(entity.captured.fee.isNaN(), "NaN 은 컬럼에 절대 들어가면 안 된다")
    }

    @Test
    fun `saveAudit carries the strategy and realized amount onto the execution row`() = runTest {
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "audit-sell") } returns Mono.just(false)
        val entity = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(entity)) } returns
            Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        service.saveAudit(
            TradeRecord(
                ticker = "KRW-BTC", side = TradeSide.SELL, price = 52000000.0, volume = 0.002,
                totalAmount = 104000.0, pnlPercent = 3.9, pnlAmount = 3900.0, strategy = "knee_reversal",
                exchangeOrderId = "audit-sell", userId = 1L,
                fee = FeeBasis.Estimate,
                orderAmount = null,
            )
        )

        assertEquals("knee_reversal", entity.captured.strategy)
        assertEquals(3900.0, entity.captured.pnlAmount!!, 1e-9)
    }
}
