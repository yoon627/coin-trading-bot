package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 작업자는 자기 종목의 바퀴를 차례로 처리하고, 거래일이 바뀐 바퀴 처음에 리셋하고, 관리할 것이 없으면 정리돼 끝난다. */
class TickerWorkerTest {

    private val d1 = LocalDate.of(2026, 11, 19)
    private val d2 = LocalDate.of(2026, 11, 20)

    private class FakeWork : TickerWork {
        val calls = mutableListOf<String>()
        var settledAfterReset = false
        var failProcess = false
        var processGate: CompletableDeferred<Unit>? = null
        var processOpensPosition = false

        override suspend fun sync(ticker: String, state: TradingState, accounts: List<Account>?) {
            calls += "sync"
        }

        override suspend fun resetDay(state: TradingState, tradingDate: LocalDate) {
            calls += "reset $tradingDate"
        }

        override suspend fun releaseIfSettled(ticker: String, state: TradingState): Boolean =
            (settledAfterReset && calls.any { it.startsWith("reset") }).also { if (it) calls += "released" }

        override suspend fun onTrade(ticker: String, state: TradingState, trade: PriceSample, orders: TradeOrders): TradeOutcome {
            calls += "trade ${trade.price} $orders"
            return TradeOutcome.NO_ORDER
        }

        override suspend fun process(ticker: String, state: TradingState, round: Round, lastTrade: PriceSample?) {
            processGate?.let { gate -> processGate = null; gate.await() }
            calls += "process ${round.tradingDate} ${round.rest.price(ticker)}"
            if (processOpensPosition) state.position = !state.position
            if (failProcess) throw IllegalStateException("boom")
        }
    }

    private fun round(date: LocalDate, price: Double? = null) =
        Round(date, RestRound(if (price == null) emptyMap() else mapOf("KRW-BTC" to price)))

    private fun worker(work: FakeWork) = TickerWorker("KRW-BTC", TradingState("KRW-BTC"), work, Clock.systemUTC())

    @Test
    fun `the first round resets, the same trading date does not reset again, a new one does`() = runTest {
        val work = FakeWork()
        val worker = worker(work)
        val job = launch { worker.run(null) }

        worker.offer(round(d1)); testScheduler.advanceUntilIdle()
        worker.offer(round(d1)); testScheduler.advanceUntilIdle()
        worker.offer(round(d2)); testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(
            listOf("sync", "reset $d1", "process $d1 null", "process $d1 null", "reset $d2", "process $d2 null"),
            work.calls,
        )
    }

    @Test
    fun `a ticker with nothing left after its reset is released and the worker ends normally`() = runTest {
        val work = FakeWork().apply { settledAfterReset = true }
        val worker = worker(work)
        val done = async { worker.run(null) }

        worker.offer(round(d1))
        done.await()

        assertTrue(worker.released)
        assertEquals(listOf("sync", "reset $d1", "released"), work.calls)
    }

    @Test
    fun `a round that throws is logged and the worker keeps taking rounds`() = runTest {
        val work = FakeWork().apply { failProcess = true }
        val worker = worker(work)
        val job = launch { worker.run(null) }

        worker.offer(round(d1)); testScheduler.advanceUntilIdle()
        worker.offer(round(d1)); testScheduler.advanceUntilIdle()
        val active = job.isActive
        job.cancel()

        assertTrue(active)
        assertFalse(worker.released)
        assertEquals(2, work.calls.count { it.startsWith("process") })
    }

    @Test
    fun `rounds that arrive while one is being processed fold into the latest`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val work = FakeWork().apply { processGate = gate }
        val worker = worker(work)
        val job = launch { worker.run(null) }

        worker.offer(round(d1, 1.0)); testScheduler.advanceUntilIdle() // 첫 바퀴가 처리 중에 멈춘다
        worker.offer(round(d1, 2.0))
        worker.offer(round(d1, 3.0))
        gate.complete(Unit); testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("process $d1 1.0", "process $d1 3.0"), work.calls.filter { it.startsWith("process") })
    }

    @Test
    fun `a trade queued behind a round may order unless the round changed the position, and one from before the first round may not`() = runTest {
        val work = FakeWork()
        val worker = worker(work)
        val job = launch { worker.run(null) }

        worker.offerTrade(0.5, Instant.now()); testScheduler.advanceUntilIdle()
        worker.offer(round(d1)); testScheduler.advanceUntilIdle() // 시작 바퀴 — 그 앞의 체결로는 주문하지 않는다
        worker.offer(round(d1)); worker.offerTrade(1.0, Instant.now()); testScheduler.advanceUntilIdle()
        work.processOpensPosition = true
        worker.offer(round(d1)); worker.offerTrade(2.0, Instant.now()); testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("trade 0.5 NONE", "trade 1.0 ALL", "trade 2.0 NONE"), work.calls.filter { it.startsWith("trade") })
    }

    @Test
    fun `trades dropped while waiting for a round are reported by the round`() = runTest {
        val logger = LoggerFactory.getLogger(TickerWorker::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val work = FakeWork().apply { processGate = CompletableDeferred() }
            val worker = worker(work)
            val job = launch { worker.run(null) }
            testScheduler.advanceUntilIdle()
            repeat(TickerWorker.TRADE_MAILBOX_CAPACITY + 100) { worker.offerTrade(1.0, Instant.now()) }

            worker.offer(round(d1)); testScheduler.advanceUntilIdle() // 바퀴가 처리 중에 멈춰 체결은 아직 처리되지 않았다
            val warns = logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            job.cancel()

            assertTrue(work.calls.none { it.startsWith("trade") })
            assertEquals(1, warns.size, warns.toString())
            assertTrue("dropped 100 " in warns.single(), warns.toString())
        } finally {
            logger.detachAppender(logs)
        }
    }
}
