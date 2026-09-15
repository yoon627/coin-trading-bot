package com.trading.bot.kis.order

import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.domain.KisCcldRow
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.bot.persistence.entity.UserEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class StockOrderReconcilerTest {

    private lateinit var repository: StockOrderIntentRepository
    private lateinit var tradeExecutionRepository: TradeExecutionRepository
    private lateinit var userRepository: UserRepository
    private lateinit var clientFactory: KisClientFactory
    private lateinit var transactionalOperator: org.springframework.transaction.reactive.TransactionalOperator
    private lateinit var client: KisClient
    private lateinit var reconciler: StockOrderReconciler

    private val now = Instant.parse("2026-06-14T01:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"))

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = false)
        tradeExecutionRepository = mockk()
        userRepository = mockk()
        clientFactory = mockk()
        client = mockk()
        transactionalOperator = mockk()
        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers { firstArg() }
        every { userRepository.findById(1L) } returns Mono.just(UserEntity(id = 1L, username = "u", password = "p"))
        every { clientFactory.forUser(any()) } returns client
        every { repository.transition(any(), any(), any(), any(), any(), any(), any()) } returns Mono.just(1L)
        every { repository.claimAudit(any()) } returns Mono.just(1L)
        every { repository.findKnownOdnos(any(), any()) } returns Flux.empty()
        every { tradeExecutionRepository.save(any()) } returns Mono.just(mockk<TradeExecutionEntity>(relaxed = true))

        reconciler = StockOrderReconciler(
            repository, tradeExecutionRepository, userRepository, clientFactory,
            transactionalOperator, KisProperties(), clock,
        )
    }

    private fun row(
        status: StockOrderStatus,
        odno: String? = null,
        qty: Long = 10,
        createdAt: Instant = now,
        side: String = "BUY",
        strategy: String? = null,
        reason: String? = null,
        userId: Long = 1,
        orderDate: String = "20260614",
        id: Long = 100,
    ) = StockOrderIntentEntity(
        id = id, userId = userId, clientRef = "r", accountNo = "12345678-01", symbol = "005930",
        side = side, orderType = "LIMIT", qty = qty, price = BigDecimal(70_000),
        status = status.name, odno = odno, orderDate = orderDate, createdAt = createdAt, updatedAt = createdAt,
        strategy = strategy, reason = reason,
    )

    private fun ccld(
        odno: String = "0000117057", side: String = "02", ordQty: String = "10",
        totCcld: String = "0", rmn: String = "10", cncl: String = "N", avg: String = "70000",
    ) = KisCcldRow(odno = odno, pdno = "005930", sllBuyDvsnCd = side, ordQty = ordQty, totCcldQty = totCcld, rmnQty = rmn, cnclYn = cncl, avgPrvs = avg)

    private fun activate(r: StockOrderIntentEntity, conclusions: List<KisCcldRow>) {
        every { repository.findActive(any(), any()) } returns Flux.just(r)
        coEvery { client.inquireDailyConclusions("20260614") } returns conclusions
    }

    @Test
    fun `PLACED fully filled transitions to FILLED and records audit once`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "10", rmn = "0")))
        val exec = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(exec)) } returns Mono.just(mockk(relaxed = true))

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "PLACED", "FILLED", null, null, 10, null) }
        coVerify(exactly = 1) { repository.claimAudit(100L) }
        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
        assertEquals(10.0, exec.captured.volume)
        assertEquals(70_000.0, exec.captured.price)
        assertEquals("0000117057", exec.captured.exchangeOrderId)
    }

    @Test
    fun `PLACED partial fill stays PARTIAL without audit`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "4", rmn = "6")))

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "PLACED", "PARTIAL", null, null, 4, null) }
        coVerify(exactly = 0) { repository.claimAudit(any()) }
        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `UNKNOWN with single match links ODNO and becomes PLACED`() = runTest {
        activate(row(StockOrderStatus.UNKNOWN, odno = null), listOf(ccld(odno = "0000117057", totCcld = "0", rmn = "10")))

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "UNKNOWN", "PLACED", "0000117057", null, 0, null) }
    }

    @Test
    fun `UNKNOWN with zero matches after grace escalates to NEEDS_REVIEW (never FAILED)`() = runTest {
        val old = now.minusSeconds(300) // grace=120s 초과
        activate(row(StockOrderStatus.UNKNOWN, odno = null, createdAt = old), emptyList())

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "UNKNOWN", "NEEDS_REVIEW", null, null, 0, any()) }
        coVerify(exactly = 0) { repository.transition(any(), any(), "FAILED", any(), any(), any(), any()) }
    }

    @Test
    fun `UNKNOWN with zero matches within grace stays pending`() = runTest {
        val recent = now.minusSeconds(30) // grace=120s 이내
        activate(row(StockOrderStatus.UNKNOWN, odno = null, createdAt = recent), emptyList())

        reconciler.reconcileNow()

        coVerify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `UNKNOWN with multiple matches escalates to NEEDS_REVIEW`() = runTest {
        activate(
            row(StockOrderStatus.UNKNOWN, odno = null),
            listOf(ccld(odno = "A"), ccld(odno = "B")),
        )

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "UNKNOWN", "NEEDS_REVIEW", null, null, 0, any()) }
    }

    @Test
    fun `cancelled with partial fill becomes CANCELLED and records the fill`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "3", rmn = "7", cncl = "Y")))

        reconciler.reconcileNow()

        coVerify { repository.transition(100L, "PLACED", "CANCELLED", null, null, 3, null) }
        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `audit not duplicated when claim already taken`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "10", rmn = "0")))
        every { repository.claimAudit(100L) } returns Mono.just(0L) // 이미 다른 패스가 audit 기록

        reconciler.reconcileNow()

        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `execution record carries the strategy the order was placed with`() = runTest {
        activate(
            row(StockOrderStatus.PLACED, odno = "0000117057", strategy = "rsi_bounce"),
            listOf(ccld(totCcld = "10", rmn = "0")),
        )
        val exec = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(exec)) } returns Mono.just(mockk(relaxed = true))

        reconciler.reconcileNow()

        assertEquals("rsi_bounce", exec.captured.strategy)
    }

    /**
     * 매도 귀속이 이 수정의 핵심이다. 값이 WAL 에서 오므로 stock_position_state 가 이미 청산으로
     * 비워졌든 아니든 결과가 같다 — reconciler 는 포지션 상태를 아예 조회하지 않는다.
     */
    @Test
    fun `sell execution carries entry strategy and sell reason from the WAL`() = runTest {
        activate(
            row(StockOrderStatus.PLACED, odno = "0000117057", side = "SELL", strategy = "combined", reason = "STOP_LOSS"),
            listOf(ccld(side = "01", totCcld = "10", rmn = "0")),
        )
        val exec = slot<TradeExecutionEntity>()
        every { tradeExecutionRepository.save(capture(exec)) } returns Mono.just(mockk(relaxed = true))

        reconciler.reconcileNow()

        assertEquals("combined", exec.captured.strategy)
        assertEquals("STOP_LOSS", exec.captured.reason)
    }

    // --- reconcile 결과: 진입 게이트가 "이 사용자의 활성 주문이 확정됐는가"를 읽는다 (#67) ---
    // 부분 실패의 단위는 사용자. 전체 실패·절단은 전원 미해소.

    @Test
    fun `활성 주문이 없으면 clean 이다`() = runTest {
        every { repository.findActive(any(), any()) } returns Flux.empty()

        val result = reconciler.reconcileNow()

        assertTrue(result.isCleanFor(1L))
        assertTrue(result.isCleanFor(2L))
    }

    @Test
    fun `조회 실패는 그 사용자만 미해소로 남기고 다른 사용자는 clean 이다`() = runTest {
        every { userRepository.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "v", password = "p"))
        every { repository.findActive(any(), any()) } returns Flux.just(
            row(StockOrderStatus.UNKNOWN, userId = 1, orderDate = "20260614", id = 1),
            row(StockOrderStatus.UNKNOWN, userId = 2, orderDate = "20260615", id = 2),
        )
        coEvery { client.inquireDailyConclusions("20260614") } throws RuntimeException("KIS 500")
        coEvery { client.inquireDailyConclusions("20260615") } returns emptyList()

        val result = reconciler.reconcileNow()

        assertFalse(result.isCleanFor(1L))
        assertTrue(result.isCleanFor(2L))
        assertNotNull(result.unresolvedUsers[1L])
    }

    @Test
    fun `활성 주문 조회 자체가 실패하면 전원 미해소다`() = runTest {
        every { repository.findActive(any(), any()) } returns Flux.error(RuntimeException("db down"))

        val result = reconciler.reconcileNow()

        assertNotNull(result.passError)
        assertFalse(result.isCleanFor(1L))
        assertFalse(result.isCleanFor(2L))
    }

    @Test
    fun `배치 상한만큼 잘리면 조회되지 않은 사용자가 있을 수 있어 전원 미해소다`() = runTest {
        val rows = (1..StockOrderReconciler.BATCH_LIMIT).map { row(StockOrderStatus.UNKNOWN, id = it.toLong()) }
        every { repository.findActive(any(), any()) } returns Flux.fromIterable(rows)
        coEvery { client.inquireDailyConclusions("20260614") } returns emptyList()

        val result = reconciler.reconcileNow()

        assertTrue(result.truncated)
        assertFalse(result.isCleanFor(1L))
        assertFalse(result.isCleanFor(2L))
    }

    @Test
    fun `진입 게이트는 마지막 완료 패스를 따른다 — 미실행이면 차단, 실패 뒤 성공하면 해제`() = runTest {
        assertNotNull(reconciler.entryBlockReason(1L), "패스가 한 번도 완료되지 않았으면 차단")

        every { repository.findActive(any(), any()) } returns Flux.just(row(StockOrderStatus.UNKNOWN))
        coEvery { client.inquireDailyConclusions("20260614") } throws RuntimeException("KIS 500")
        reconciler.reconcileNow()
        assertNotNull(reconciler.entryBlockReason(1L))
        assertEquals(null, reconciler.entryBlockReason(2L), "다른 사용자는 차단되지 않는다")

        coEvery { client.inquireDailyConclusions("20260614") } returns emptyList()
        reconciler.reconcileNow()
        assertEquals(null, reconciler.entryBlockReason(1L), "성공 패스가 차단을 푼다")
    }

    @Test
    fun `사용자 처리 중 DB 오류도 그 사용자만 미해소로 격리한다`() = runTest {
        every { userRepository.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "v", password = "p"))
        every { repository.findActive(any(), any()) } returns Flux.just(
            row(StockOrderStatus.UNKNOWN, userId = 1, orderDate = "20260614", id = 1),
            row(StockOrderStatus.UNKNOWN, userId = 2, orderDate = "20260615", id = 2),
        )
        coEvery { client.inquireDailyConclusions(any()) } returns emptyList()
        every { repository.findKnownOdnos(1L, any()) } returns Flux.error(RuntimeException("db timeout"))
        every { repository.findKnownOdnos(2L, any()) } returns Flux.empty()

        val result = reconciler.reconcileNow()

        assertFalse(result.isCleanFor(1L))
        assertTrue(result.isCleanFor(2L))
    }
}
