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
    ) = StockOrderIntentEntity(
        id = 100, userId = 1, clientRef = "r", accountNo = "12345678-01", symbol = "005930",
        side = side, orderType = "LIMIT", qty = qty, price = BigDecimal(70_000),
        status = status.name, odno = odno, orderDate = "20260614", createdAt = createdAt, updatedAt = createdAt,
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

        reconciler.reconcileOnce()

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

        reconciler.reconcileOnce()

        coVerify { repository.transition(100L, "PLACED", "PARTIAL", null, null, 4, null) }
        coVerify(exactly = 0) { repository.claimAudit(any()) }
        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `UNKNOWN with single match links ODNO and becomes PLACED`() = runTest {
        activate(row(StockOrderStatus.UNKNOWN, odno = null), listOf(ccld(odno = "0000117057", totCcld = "0", rmn = "10")))

        reconciler.reconcileOnce()

        coVerify { repository.transition(100L, "UNKNOWN", "PLACED", "0000117057", null, 0, null) }
    }

    @Test
    fun `UNKNOWN with zero matches after grace escalates to NEEDS_REVIEW (never FAILED)`() = runTest {
        val old = now.minusSeconds(300) // grace=120s 초과
        activate(row(StockOrderStatus.UNKNOWN, odno = null, createdAt = old), emptyList())

        reconciler.reconcileOnce()

        coVerify { repository.transition(100L, "UNKNOWN", "NEEDS_REVIEW", null, null, 0, any()) }
        coVerify(exactly = 0) { repository.transition(any(), any(), "FAILED", any(), any(), any(), any()) }
    }

    @Test
    fun `UNKNOWN with zero matches within grace stays pending`() = runTest {
        val recent = now.minusSeconds(30) // grace=120s 이내
        activate(row(StockOrderStatus.UNKNOWN, odno = null, createdAt = recent), emptyList())

        reconciler.reconcileOnce()

        coVerify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `UNKNOWN with multiple matches escalates to NEEDS_REVIEW`() = runTest {
        activate(
            row(StockOrderStatus.UNKNOWN, odno = null),
            listOf(ccld(odno = "A"), ccld(odno = "B")),
        )

        reconciler.reconcileOnce()

        coVerify { repository.transition(100L, "UNKNOWN", "NEEDS_REVIEW", null, null, 0, any()) }
    }

    @Test
    fun `cancelled with partial fill becomes CANCELLED and records the fill`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "3", rmn = "7", cncl = "Y")))

        reconciler.reconcileOnce()

        coVerify { repository.transition(100L, "PLACED", "CANCELLED", null, null, 3, null) }
        coVerify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `audit not duplicated when claim already taken`() = runTest {
        activate(row(StockOrderStatus.PLACED, odno = "0000117057"), listOf(ccld(totCcld = "10", rmn = "0")))
        every { repository.claimAudit(100L) } returns Mono.just(0L) // 이미 다른 패스가 audit 기록

        reconciler.reconcileOnce()

        coVerify(exactly = 0) { tradeExecutionRepository.save(any()) }
    }
}
