package com.trading.bot.kis.order

import com.trading.bot.kis.client.KisApiException
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.domain.KisOrderAck
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class StockOrderServiceTest {

    private lateinit var repository: StockOrderIntentRepository
    private lateinit var client: KisClient
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-14T01:00:00Z"), ZoneId.of("Asia/Seoul"))

    private val cmd = SubmitOrderCommand(
        userId = 1L, cano = "12345678", acntPrdtCd = "01", symbol = "005930",
        side = KisSide.BUY, orderType = KisOrderType.LIMIT, qty = 10, price = 70_000,
    )

    @BeforeEach
    fun setup() {
        repository = mockk()
        client = mockk()
        // 활성 주문 없음 (사전 가드 통과).
        every { repository.findActiveByKey(any(), any(), any(), any(), any(), any()) } returns Mono.empty()
        // save 는 id 부여된 SUBMITTING/DRY_RUN 엔티티 반환.
        every { repository.save(any()) } answers { Mono.just((firstArg() as StockOrderIntentEntity).copy(id = 100L)) }
        every { repository.transition(any(), any(), any(), any(), any(), any(), any()) } returns Mono.just(1L)
    }

    private fun service(liveEnabled: Boolean, maxOrderAmount: Long = 10_000_000) =
        StockOrderService(repository, KisProperties(liveEnabled = liveEnabled, maxOrderAmount = maxOrderAmount), clock)

    @Test
    fun `dry-run writes DRY_RUN intent and does NOT call placeOrder`() = runTest {
        val saved = slot<StockOrderIntentEntity>()
        every { repository.save(capture(saved)) } answers { Mono.just(saved.captured.copy(id = 100L)) }

        val result = service(liveEnabled = false).submit(client, cmd)

        assertEquals(StockOrderStatus.DRY_RUN.name, saved.captured.status)
        assertEquals(StockOrderStatus.DRY_RUN.name, result.status)
        coVerify(exactly = 0) { client.placeOrder(any()) }
        coVerify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `live success writes SUBMITTING before place then transitions to PLACED`() = runTest {
        val saved = slot<StockOrderIntentEntity>()
        every { repository.save(capture(saved)) } answers { Mono.just(saved.captured.copy(id = 100L)) }
        coEvery { client.placeOrder(any()) } returns KisOrderAck(odno = "0000117057", orgNo = "91252", ordTmd = "101010")

        val result = service(liveEnabled = true).submit(client, cmd)

        // WAL: INSERT 는 SUBMITTING 으로(placeOrder 전).
        assertEquals(StockOrderStatus.SUBMITTING.name, saved.captured.status)
        assertEquals(StockOrderStatus.PLACED.name, result.status)
        assertEquals("0000117057", result.odno)
        // 순서: save(INSERT) 가 placeOrder 보다 먼저.
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            repository.save(any())
            client.placeOrder(any())
            repository.transition(100L, StockOrderStatus.SUBMITTING.name, StockOrderStatus.PLACED.name, "0000117057", "91252", 0, null)
        }
    }

    @Test
    fun `definitive broker reject transitions to FAILED`() = runTest {
        coEvery { client.placeOrder(any()) } throws KisApiException(definitiveReject = true, rtCd = "1", msg = "주문가능금액 부족")

        val result = service(liveEnabled = true).submit(client, cmd)

        assertEquals(StockOrderStatus.FAILED.name, result.status)
        coVerify { repository.transition(100L, StockOrderStatus.SUBMITTING.name, StockOrderStatus.FAILED.name, null, null, 0, any()) }
    }

    @Test
    fun `ambiguous send error transitions to UNKNOWN (not FAILED)`() = runTest {
        coEvery { client.placeOrder(any()) } throws KisApiException(definitiveReject = false, msg = "timeout")

        val result = service(liveEnabled = true).submit(client, cmd)

        assertEquals(StockOrderStatus.UNKNOWN.name, result.status)
        coVerify { repository.transition(100L, StockOrderStatus.SUBMITTING.name, StockOrderStatus.UNKNOWN.name, null, null, 0, any()) }
    }

    @Test
    fun `unclassified exception is treated as UNKNOWN`() = runTest {
        coEvery { client.placeOrder(any()) } throws RuntimeException("boom")

        val result = service(liveEnabled = true).submit(client, cmd)

        assertEquals(StockOrderStatus.UNKNOWN.name, result.status)
    }

    @Test
    fun `notional over cap throws before any WAL write or place`() = runTest {
        // qty 10 * 70,000 = 700,000 > cap 500,000
        assertThrows(StockOrderValidationException::class.java) {
            kotlinx.coroutines.runBlocking { service(liveEnabled = true, maxOrderAmount = 500_000).submit(client, cmd) }
        }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { client.placeOrder(any()) }
    }

    @Test
    fun `overflowing qty cannot slip past the cap`() = runTest {
        // qty * unitPrice 가 Long 을 넘으면 곱셈이 접혀 음수가 되고, 단순 비교(notional > cap)를 통과한다.
        // 수동 주문에는 qty 상한이 없으므로 이 경로가 maxOrderAmount 가드를 무력화하는 실제 우회로였다.
        val hugeQty = cmd.copy(qty = Long.MAX_VALUE / 1000)

        assertThrows(StockOrderValidationException::class.java) {
            kotlinx.coroutines.runBlocking { service(liveEnabled = true, maxOrderAmount = 500_000).submit(client, hugeQty) }
        }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { client.placeOrder(any()) }
    }

    @Test
    fun `market order notional uses buffered current price for cap`() = runTest {
        val marketCmd = cmd.copy(orderType = KisOrderType.MARKET, price = null)
        coEvery { client.getCurrentPrice("005930") } returns 70_000
        coEvery { client.placeOrder(any()) } returns KisOrderAck(odno = "1", orgNo = null, ordTmd = null)

        // 10주 * 70,000 * 1.1(버퍼) = 770,000 > cap 700,000 → 거부
        assertThrows(StockOrderValidationException::class.java) {
            kotlinx.coroutines.runBlocking { service(liveEnabled = true, maxOrderAmount = 700_000).submit(client, marketCmd) }
        }
        coVerify(exactly = 0) { client.placeOrder(any()) }
    }

    @Test
    fun `market order rejected when current price unavailable (no guard bypass)`() = runTest {
        val marketCmd = cmd.copy(orderType = KisOrderType.MARKET, price = null)
        coEvery { client.getCurrentPrice("005930") } throws
            KisApiException(definitiveReject = false, msg = "invalid current price")

        assertThrows(StockOrderValidationException::class.java) {
            kotlinx.coroutines.runBlocking { service(liveEnabled = true).submit(client, marketCmd) }
        }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { client.placeOrder(any()) }
    }

    @Test
    fun `existing active order is rejected`() = runTest {
        every { repository.findActiveByKey(any(), any(), any(), any(), any(), any()) } returns
            Mono.just(StockOrderIntentEntity(id = 7, userId = 1, clientRef = "x", accountNo = "12345678-01", symbol = "005930", side = "BUY", orderType = "LIMIT", qty = 10, status = StockOrderStatus.PLACED.name, orderDate = "20260614"))

        assertThrows(StockOrderValidationException::class.java) {
            kotlinx.coroutines.runBlocking { service(liveEnabled = true).submit(client, cmd) }
        }
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { client.placeOrder(any()) }
    }
}
