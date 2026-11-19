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
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 저장은 메모리가 들고 있는 버전으로만 된다(#365). 버전은 저장이 커밋된 뒤에만 오르고,
 * 한 번 충돌한 종목은 ERROR 한 번으로 알린 뒤 더 쓰지 않는다. 손절 매도는 계속 나간다.
 */
class PositionManagerStateVersionTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>()
    private val store = VersionedStateStore(stateService)
    private val sent = mutableListOf<OrderRequest>()
    private val price = 10_000_000.0

    // 체결 커밋의 트랜잭션. 롤백이면 저장소도 커밋 전 버전으로 돌린다.
    private var commitOutcome: (suspend (suspend () -> Unit) -> Boolean) = { persist -> persist(); true }
    private val manager = PositionManager(
        upbit, TradingProperties(), stateService, 1L,
        commitFill = { persist, _: TradeRecord -> commitOutcome(persist) },
    )

    private val logger = LoggerFactory.getLogger("com.trading.bot") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(appender)
        coEvery { upbit.placeOrder(any()) } coAnswers {
            val request = firstArg<OrderRequest>()
            sent += request
            Order(uuid = if (request.side == "bid") "b-1" else "s-1", state = "wait")
        }
        coEvery { upbit.getOrder("b-1") } returns done("b-1", "0.01")
        coEvery { upbit.getOrder("s-1") } returns done("s-1", "0.01")
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(appender)
    }

    private fun done(uuid: String, volume: String) = Order(
        uuid = uuid, state = "done", executedVolume = volume, paidFee = "50",
        trades = listOf(OrderTrade(price = "10000000", volume = volume, funds = "100000")),
    )

    private fun balances(btc: String) {
        coEvery { upbit.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000"),
            Account(currency = "BTC", balance = btc, locked = "0", avgBuyPrice = "10000000"),
        )
    }

    /** 로드한 보유 상태. 저장소에는 같은 버전의 행이 있다. */
    private fun loadedHolding(version: Long = 5L) = TradingState(
        "KRW-BTC", position = true, holdVolume = 0.01, avgBuyPrice = price, bookKnown = true, rowVersion = version,
    ).also { store.versions["KRW-BTC"] = version }

    private fun errors() = appender.list.filter { it.level == Level.ERROR }

    @Test
    fun `a new ticker's buy, fill, new peak and stop-loss sell all save with the version they hold`() = runTest {
        balances("0")
        val state = TradingState("KRW-BTC")

        assertNotNull(manager.buy("KRW-BTC", state, price, "test"))
        assertTrue(state.position)
        state.updatePeakPrice(price * 1.1)
        manager.persistPeak(state)
        balances("0.01")
        manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

        assertFalse(state.position)
        assertFalse(state.versionConflict)
        assertEquals(store.versions["KRW-BTC"], state.rowVersion)
        assertTrue(store.writes >= 5, "선기록·uuid·체결·고점·매도 선기록·uuid·체결")
        assertTrue(errors().isEmpty(), errors().toString())
    }

    // 체결 커밋은 다른 테이블과 한 트랜잭션이다. 롤백 뒤에도 메모리 버전이 올라 있으면 그 뒤 저장이 전부 충돌한다.
    @Test
    fun `a fill commit that rolls back leaves the version where the database has it`() = runTest {
        balances("0")
        val state = loadedHolding().apply { adoptSellOrder("s-1"); pendingSellReason = SellReason.STOP_LOSS }
        commitOutcome = { persist ->
            val before = store.versions.toMap()
            persist()
            store.versions.putAll(before)
            throw IllegalStateException("audit insert failed")
        }

        manager.reconcilePendingSell("KRW-BTC", state, price)
        assertTrue(state.hasPendingSell(), "기록하지 못한 체결은 pending 으로 남는다")
        assertEquals(store.versions["KRW-BTC"], state.rowVersion)
        assertFalse(state.versionConflict)

        commitOutcome = { persist -> persist(); true }
        manager.reconcilePendingSell("KRW-BTC", state, price)

        assertFalse(state.hasPendingSell())
        assertEquals(store.versions["KRW-BTC"], state.rowVersion)
        assertFalse(state.versionConflict)
    }

    // 기록 중복으로 체결 기록을 건너뛰어도 같은 트랜잭션의 상태 저장은 커밋된다.
    @Test
    fun `a fill that was already recorded still takes the committed version`() = runTest {
        balances("0")
        val state = loadedHolding().apply { adoptSellOrder("s-1"); pendingSellReason = SellReason.STOP_LOSS }
        commitOutcome = { persist -> persist(); false }

        manager.reconcilePendingSell("KRW-BTC", state, price)

        assertFalse(state.hasPendingSell())
        assertEquals(store.versions["KRW-BTC"], state.rowVersion)
        manager.persistState(state)
        assertFalse(state.versionConflict)
    }

    @Test
    fun `after a conflict the ticker alerts once, stops writing and still sends its stop-loss`() = runTest {
        balances("0.01")
        val state = loadedHolding()
        store.writeElsewhere("KRW-BTC")

        manager.persistState(state)
        val writesAtConflict = store.writes
        assertTrue(state.versionConflict)

        state.pendingPersistFailed = true
        state.peakPersistFailed = true
        repeat(3) {
            manager.retryPendingPersistIfNeeded(state)
            manager.persistPeak(state)
            manager.persistState(state)
        }
        manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

        assertEquals(writesAtConflict, store.writes, "충돌 뒤에는 DB 를 부르지 않는다")
        assertEquals("ask", sent.single().side, "손절은 기록이 막혀도 나간다")
        assertTrue(state.hasPendingSell(), "체결을 기록할 수 없어 pending 이 남는다")
        assertEquals(1, errors().size, errors().toString())
    }

    // 충돌한 종목은 저장이 늘 실패하므로 진입 검사에서 바로 막는다. 잔고 조회·선기록까지 가면 매수 신호마다 헛돈다.
    @Test
    fun `a ticker whose state conflicted skips a buy signal before reading the balance`() = runTest {
        val state = TradingState("KRW-BTC", rowVersion = 5L).apply { versionConflict = true }

        assertNull(manager.buy("KRW-BTC", state, price, "test"))

        coVerify(exactly = 0) { upbit.getAccounts() }
        assertTrue(sent.isEmpty())
        assertTrue(errors().isEmpty(), errors().toString())
    }
}
