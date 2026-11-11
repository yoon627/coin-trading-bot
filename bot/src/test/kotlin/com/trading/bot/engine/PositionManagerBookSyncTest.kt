package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.StoredBook
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** 재시작·재동기화는 저장된 봇 장부를 잔고만큼 줄이기만 한다. 소유자 코인은 장부에 들지 않고, 봇 코인은 손절 밖으로 빠지지 않는다. */
class PositionManagerBookSyncTest {

    private val upbit = mockk<UpbitClient>()
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(), stateService, 1L)
    private val saved = mutableListOf<TradingState>()

    private val logger = LoggerFactory.getLogger(PositionManager::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun balance(free: String, locked: String = "0", avg: String = "30000000") {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = free, locked = locked, avgBuyPrice = avg))
    }

    private fun loaded(book: Double?, avg: Double = 50_000_000.0) =
        TradingState("KRW-BTC", storedBook = book?.let { StoredBook(it, avg) })

    private fun warns() = logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private suspend fun sync(state: TradingState) = manager.syncPosition("KRW-BTC", state)

    @Test
    fun `a stored book is restored, not the whole balance, with the book's average`() = runTest {
        balance("0.06")
        val state = loaded(0.01)

        sync(state)

        assertTrue(state.position)
        assertEquals(0.01, state.holdVolume, 1e-12)
        assertEquals(50_000_000.0, state.avgBuyPrice)
        assertTrue(state.bookKnown)
        assertEquals(0.01, saved.single().holdVolume, 1e-12)
    }

    @Test
    fun `a book larger than the balance shrinks to it and the shrink is saved`() = runTest {
        balance("0.004")
        val state = loaded(0.01)

        sync(state)

        assertEquals(0.004, state.holdVolume, 1e-12)
        assertEquals(0.004, saved.single().holdVolume, 1e-12)
    }

    // 소유자 지정가가 봇 코인을 잠근 채 재시작하면 free 만 보고 줄일 때 봇 코인이 손절 밖으로 빠진다.
    @Test
    fun `an owner's lock does not shrink the book and keeps the position for the stop-loss`() = runTest {
        balance("0", locked = "0.06")
        val state = loaded(0.01)

        sync(state)

        assertTrue(state.position)
        assertEquals(0.01, state.holdVolume, 1e-12)
        assertTrue(state.unsynced) // 보유를 모르는 채 사지 않는다
        assertEquals(0.01, saved.single().holdVolume, 1e-12)
    }

    // 잠긴 봇 코인을 free 만 보고 편입하면 0 이나 일부로 굳어 손절 밖으로 빠진다.
    @Test
    fun `a row from before stored books waits for an owner's lock to clear before adopting`() = runTest {
        balance("0.02", locked = "0.04")
        val state = TradingState("KRW-BTC", entryStrategy = "combined")

        sync(state)

        assertFalse(state.bookKnown)
        assertTrue(state.unsynced)
        assertEquals(0.02, state.holdVolume, 1e-12) // 그 사이 손절은 팔 수 있는 잔고로
        assertTrue(warns().none { "Adopting" in it }, warns().toString())
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }

        balance("0.06")
        sync(state)

        assertEquals(0.06, state.holdVolume, 1e-12)
        assertFalse(state.unsynced)
    }

    @Test
    fun `a row from before stored books adopts the whole balance once when the bot left a trace`() = runTest {
        balance("0.06", avg = "45000000")
        val state = TradingState("KRW-BTC", entryStrategy = "combined", buyDate = LocalDate.of(2026, 10, 4))

        sync(state)
        sync(state)

        assertEquals(0.06, state.holdVolume, 1e-12)
        assertEquals(45_000_000.0, state.avgBuyPrice)
        assertEquals(1, warns().count { "Adopting" in it }, warns().toString())
        assertEquals(0.06, saved.first().holdVolume, 1e-12) // 편입은 그 동기화에서 바로 남긴다
    }

    @Test
    fun `a row from before stored books with no trace is not adopted and says so`() = runTest {
        balance("0.5")
        val state = TradingState("KRW-BTC")

        sync(state)

        assertFalse(state.position)
        assertEquals(0.0, state.holdVolume)
        assertTrue(state.bookKnown)
        assertEquals(1, warns().count { "Not adopting" in it && "0.5" in it }, warns().toString())
    }

    @Test
    fun `a row from before stored books with an open sell takes the order volume as the book`() = runTest {
        balance("0.05", locked = "0.01")
        val state = TradingState("KRW-BTC", pendingSellUuid = "s-1", pendingSellVolume = 0.01)

        sync(state)

        assertEquals(0.01, state.holdVolume, 1e-12)
    }

    @Test
    fun `a coin row missing from the account zeroes the book`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "KRW", balance = "1000"))
        val state = loaded(0.01)

        sync(state)

        assertFalse(state.position)
        assertEquals(0.0, saved.single().holdVolume)
    }

    // 동기화가 실패한 상태는 장부를 모르므로, 이어지는 저장(일일 리셋·clearHalt 등)이 저장된 장부를 덮지 않아야 한다.
    @Test
    fun `a failed sync leaves the book unknown so later saves keep the stored book`() = runTest {
        coEvery { upbit.getAccounts() } throws IllegalStateException("upbit down")
        val state = loaded(0.01)

        sync(state)

        assertFalse(state.bookKnown)
        assertTrue(state.unsynced)
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
    }

    @Test
    fun `a manually cleared buy adopts only what rose above the balance before the order, once`() = runTest {
        balance("0.06", avg = "51000000")
        val state = loaded(0.0).apply {
            pendingBuyUuid = "b-1"
            pendingBuyPriorVolume = 0.05
            releasePending(TradeSide.BUY, LocalDate.of(2026, 11, 10))
        }

        sync(state)
        sync(state)

        assertEquals(0.01, state.holdVolume, 1e-9)
        assertEquals(51_000_000.0, state.avgBuyPrice)
        assertNull(state.adoptBaselineVolume)
        assertNull(saved.first().adoptBaselineVolume) // 편입과 같은 저장에서 비운다
    }

    @Test
    fun `a manually cleared sell reduces the book by what it sold, partial fills included`() = runTest {
        fun cleared() = loaded(0.01).apply {
            pendingSellUuid = "s-1"
            pendingSellPriorVolume = 0.06
            pendingSellVolume = 0.01
            releasePending(TradeSide.SELL, LocalDate.of(2026, 11, 10))
        }
        balance("0.05")
        val filled = cleared()
        sync(filled)
        balance("0.056")
        val partial = cleared()
        sync(partial)

        assertFalse(filled.position)
        assertEquals(0.006, partial.holdVolume, 1e-9)
        assertNull(partial.sellClearVolume)
    }

    @Test
    fun `a manual clear is judged only after an owner's lock clears`() = runTest {
        balance("0.05", locked = "0.01")
        val state = loaded(0.01).apply {
            pendingSellUuid = "s-1"
            pendingSellPriorVolume = 0.06
            pendingSellVolume = 0.01
            releasePending(TradeSide.SELL, LocalDate.of(2026, 11, 10))
        }

        sync(state)

        assertTrue(state.unsynced)
        assertEquals(0.01, state.sellClearVolume)
        assertFalse(state.position) // 정리한 매도가 이미 팔았을 수 있어 저장된 장부로 손절하지 않는다
        coVerify(exactly = 0) { stateService.upsert(any(), any()) }
    }

    // 주문 뒤 소유자가 봇 코인 일부를 팔고 재시작하면, 체결을 옛 주문 때 장부에 더해 줄인 장부를 되돌린다.
    @Test
    fun `a sync caps the order-time book of a pending buy at the shrunk book`() = runTest {
        balance("0.004")
        val state = loaded(0.01).apply {
            pendingBuyUuid = "b-1"
            pendingBuyBookVolume = 0.01
        }

        sync(state)

        assertEquals(0.004, state.pendingBuyBookVolume!!, 1e-12)
    }
}
