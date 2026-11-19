package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 결과 모르는 주문의 확인 조회가 거래소 장애로 실패하면 다음 조회까지 간격을 두 배씩(상한 5분) 늘린다. 장애 중 같은 API 를
 * 바퀴마다 두드리지 않기 위해서다. 막힘 알림은 조회 횟수가 아니라 경과 시간으로 재므로 건너뛴 바퀴에도 제때 나간다.
 */
class PositionManagerLookupBackoffTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-09-28T00:00:00Z"))
    private val properties = TradingProperties(maxInvestAmount = 100_000.0)
    private val manager = PositionManager(upbit, properties, stateService, 1L, clock = clock)
    private val alertAfter = Duration.ofSeconds(properties.reconcileAlertThreshold.toLong() * properties.intervalSeconds)

    private val saved = mutableListOf<TradingState>()
    private val logger = LoggerFactory.getLogger("com.trading.bot.engine") as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy(); 0L }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun reconcileAlerts(ticker: String) = logs.list.count { it.level == Level.ERROR && "[RECONCILE]" in it.formattedMessage && ticker in it.formattedMessage }

    private fun advance(seconds: Long) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    private fun waiting(uuid: String) = Order(uuid = uuid, state = "wait", executedVolume = "0")

    private fun pendingSell() = TradingState(
        "KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.002,
        pendingSellUuid = "s-1", pendingSellReason = SellReason.STOP_LOSS, pendingSellSince = clock.now,
        pendingSellVolume = 0.002, pendingSellPriorVolume = 0.002,
    )

    private fun pendingBuy() = TradingState("KRW-BTC").apply {
        beginBuyOrder("ctb-b-1", "test", 0.0, clock.now)
        adoptBuyOrder("b-1")
    }

    /** 실패마다 [delays] 만큼 기다려야 다음 조회가 나간다. 그 1초 전 바퀴는 조회하지 않는다. */
    private suspend fun assertBackoff(delays: List<Long>, lookups: () -> Int, reconcile: suspend () -> Unit) {
        reconcile()
        assertEquals(1, lookups())
        delays.forEachIndexed { i, d ->
            advance(d - 1)
            reconcile()
            assertEquals(i + 1, lookups(), "실패 ${i + 1}번 뒤 ${d - 1}초에는 조회하지 않는다")
            advance(1)
            reconcile()
            assertEquals(i + 2, lookups(), "실패 ${i + 1}번 뒤 ${d}초에 조회한다")
        }
    }

    @Test
    fun `a failing sell lookup waits twice as long after each failure up to five minutes`() = runTest {
        var calls = 0
        coEvery { upbit.getOrder("s-1") } coAnswers { calls++; throw RuntimeException("503") }
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingSell()

        assertBackoff(listOf(10, 20, 40, 80, 160, 300, 300), { calls }) {
            manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
            assertTrue(Duration.between(clock.now, state.nextLookupAt ?: clock.now) <= Duration.ofMinutes(5))
        }
        assertTrue(state.hasPendingSell())
    }

    @Test
    fun `a sell lookup that answers brings the interval back`() = runTest {
        var calls = 0
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        coEvery { upbit.getOrder("s-1") } coAnswers { calls++; throw RuntimeException("503") }
        val state = pendingSell()
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        advance(10)
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        assertEquals(2, calls)

        advance(20)
        coEvery { upbit.getOrder("s-1") } coAnswers { calls++; waiting("s-1") }
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)

        assertEquals(4, calls, "응답을 받으면 다음 바퀴에 바로 다시 본다")
    }

    @Test
    fun `the sell stuck alert goes out on time on a tick whose lookup is skipped`() = runTest {
        coEvery { upbit.getOrder("s-1") } throws RuntimeException("503")
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingSell()
        listOf(0L, 10, 20, 40, 80).forEach { advance(it); manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0) }
        assertTrue(state.nextLookupAt!! > state.pendingSellSince!!.plus(alertAfter), "다음 조회는 알림 시각 뒤다")
        assertTrue(logs.list.none { it.level == Level.ERROR })

        clock.now = state.pendingSellSince!!.plus(alertAfter)
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)

        assertEquals(1, logs.list.count { it.level == Level.ERROR && "KRW-BTC" in it.formattedMessage })
        coVerify(exactly = 5) { upbit.getOrder("s-1") }
    }

    @Test
    fun `the buy stuck alert goes out on time on a tick whose lookup is skipped`() = runTest {
        coEvery { upbit.getOrder("b-1") } throws RuntimeException("503")
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingBuy()
        listOf(0L, 10, 20, 40, 80).forEach { advance(it); manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }
        assertTrue(state.nextLookupAt!! > state.pendingBuySince!!.plus(alertAfter), "다음 조회는 알림 시각 뒤다")
        assertEquals(0, reconcileAlerts("KRW-BTC"))

        clock.now = state.pendingBuySince!!.plus(alertAfter)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(1, reconcileAlerts("KRW-BTC"))
        coVerify(exactly = 5) { upbit.getOrder("b-1") }
    }

    @Test
    fun `a confirmation round that throws while recording still backs off and still checks the buy alert`() = runTest {
        coEvery { upbit.getOrder("b-1") } returns Order(
            uuid = "b-1", state = "done", executedVolume = "0.002", paidFee = "50",
            trades = listOf(OrderTrade(price = "50000000", volume = "0.002", funds = "100000")),
        )
        // 장부를 몰라 체결량을 잔고로 읽어야 하는데 잔고 API 가 죽었다.
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingBuy().apply { pendingBuyBookVolume = null }
        advance(alertAfter.seconds)

        runCatching { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }
        runCatching { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }

        coVerify(exactly = 1) { upbit.getOrder("b-1") }
        assertEquals(1, state.lookupFailures)
        assertEquals(1, reconcileAlerts("KRW-BTC"))
    }

    @Test
    fun `a sell whose fill cannot be recorded for an unreadable balance backs off`() = runTest {
        coEvery { upbit.getOrder("s-1") } returns Order(
            uuid = "s-1", state = "done", executedVolume = "0.002", paidFee = "50",
            trades = listOf(OrderTrade(price = "50000000", volume = "0.002", funds = "100000")),
        )
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingSell()

        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)
        manager.reconcilePendingSell("KRW-BTC", state, 50_000_000.0)

        coVerify(exactly = 1) { upbit.getOrder("s-1") }
        assertEquals(1, state.lookupFailures)
        assertTrue(state.hasPendingSell())
    }

    @Test
    fun `a failing buy lookup backs off the same way and a fresh state looks at once`() = runTest {
        var calls = 0
        coEvery { upbit.getOrder("b-1") } coAnswers { calls++; throw RuntimeException("503") }
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = pendingBuy()

        assertBackoff(listOf(10, 20), { calls }) { manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0) }

        // 재시작: 간격은 엔티티에 없어 DB 에서 다시 읽은 상태는 기본값이고 바로 조회한다.
        val restored = state.copy(lookupFailures = 0, nextLookupAt = null)
        manager.reconcilePendingBuy("KRW-BTC", restored, 50_000_000.0)
        assertEquals(4, calls)
    }

    @Test
    fun `an unanswered order and an unseen balance both count as a failed lookup`() = runTest {
        coEvery { upbit.getOrderByIdentifier("ctb-b-2") } returns null
        coEvery { upbit.getAccounts() } throws RuntimeException("503")
        val state = TradingState("KRW-BTC").apply { beginBuyOrder("ctb-b-2", "test", 0.0, clock.now) }

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        coVerify(exactly = 1) { upbit.getOrderByIdentifier("ctb-b-2") }
        assertEquals(1, state.lookupFailures)
    }

    @Test
    fun `a buy unresolved past the alert time alerts once, not again after a restart, and again for a new order`() = runTest {
        coEvery { upbit.getOrder(any()) } coAnswers { waiting(firstArg()) }
        val state = pendingBuy()

        advance(alertAfter.seconds - 1)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(0, reconcileAlerts("KRW-BTC"))
        advance(1)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(1, reconcileAlerts("KRW-BTC"))

        val restored = saved.last().copy()
        assertTrue(restored.pendingBuyAlerted, "알림 여부는 저장된다")
        manager.reconcilePendingBuy("KRW-BTC", restored, 50_000_000.0)
        assertEquals(1, reconcileAlerts("KRW-BTC"))

        restored.clearPendingBuy()
        restored.beginBuyOrder("ctb-b-3", "test", 0.0, clock.now)
        restored.adoptBuyOrder("b-3")
        advance(alertAfter.seconds)
        manager.reconcilePendingBuy("KRW-BTC", restored, 50_000_000.0)
        assertEquals(2, reconcileAlerts("KRW-BTC"))
    }

    @Test
    fun `failures from a buy confirmed by its balance do not delay the next sell`() = runTest {
        coEvery { upbit.getOrder("b-1") } throws RuntimeException("503")
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000000"), Account(currency = "BTC", balance = "0.002", avgBuyPrice = "50000000"))
        val state = pendingBuy()
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertTrue(state.position && !state.hasPendingBuy(), "잔고로 확정된다")
        assertEquals(0, state.lookupFailures, "pending 이 풀리면 실패 횟수도 풀린다")
        assertEquals(null, state.nextLookupAt)

        coEvery { upbit.placeOrder(any()) } throws RuntimeException("connection reset")
        manager.sell("KRW-BTC", state, 49_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(
            uuid = "s-9", state = "done", executedVolume = "0.002", paidFee = "50", identifier = identifier,
            trades = listOf(OrderTrade(price = "49000000", volume = "0.002", funds = "98000")),
        )
        manager.reconcilePendingSell("KRW-BTC", state, 49_000_000.0)

        coVerify(exactly = 1) { upbit.getOrderByIdentifier(identifier) }
        assertFalse(state.hasPendingSell())
    }
}
