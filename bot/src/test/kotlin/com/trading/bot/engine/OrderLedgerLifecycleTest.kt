package com.trading.bot.engine

import com.trading.bot.domain.TradeSide
import com.trading.bot.persistence.OrderRowRepository
import com.trading.common.domain.Exchange
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono

/** 생애 줄은 최선이다 — DB 가 던지거나 멈춰도 매매 흐름으로 번지지 않는다. 호출자의 취소만은 그대로 간다. */
class OrderLedgerLifecycleTest {

    private val repository = mockk<OrderRowRepository>()
    private val ledger = OrderLedger(repository)
    private val order = LifecycleOrder(1L, Exchange.UPBIT, "KRW-BTC", TradeSide.BUY, clientOrderId = "ctb-1")

    private fun insertReturns(result: Mono<Long>) {
        every { repository.insertLifecycle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns result
    }

    @Test
    fun `a failing write is swallowed`() = runTest {
        insertReturns(Mono.error(IllegalStateException("db down")))

        ledger.pending(order)
    }

    @Test
    fun `a stalled write gives up after the timeout`() = runTest {
        insertReturns(Mono.never())

        ledger.pending(order)

        assertTrue(testScheduler.currentTime in 2_000..2_100, "gave up at ${testScheduler.currentTime} ms")
    }

    @Test
    fun `the caller's cancellation still propagates`() = runTest {
        insertReturns(Mono.never())

        val write = async { ledger.pending(order) }
        yield()
        write.cancel()

        assertThrows<CancellationException> { write.await() }
        assertTrue(testScheduler.currentTime < 2_000)
    }
}
