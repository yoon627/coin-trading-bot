package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 작업자는 자기 종목의 바퀴를 차례로 처리하고, 거래일이 바뀐 바퀴 처음에 리셋하고, 관리할 것이 없으면 정리돼 끝난다. */
class TickerWorkerTest {

    private val d1 = LocalDate.of(2026, 11, 19)
    private val d2 = LocalDate.of(2026, 11, 20)

    private class FakeWork : TickerWork {
        val calls = mutableListOf<String>()
        var settledAfterReset = false
        var failProcess = false
        var processGate: CompletableDeferred<Unit>? = null

        override suspend fun sync(ticker: String, state: TradingState, accounts: List<Account>?) {
            calls += "sync"
        }

        override suspend fun resetDay(state: TradingState, tradingDate: LocalDate) {
            calls += "reset $tradingDate"
        }

        override suspend fun releaseIfSettled(ticker: String, state: TradingState): Boolean =
            (settledAfterReset && calls.any { it.startsWith("reset") }).also { if (it) calls += "released" }

        override suspend fun process(ticker: String, state: TradingState, round: Round) {
            processGate?.let { gate -> processGate = null; gate.await() }
            calls += "process ${round.tradingDate} ${round.rest.price(ticker)}"
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
}
