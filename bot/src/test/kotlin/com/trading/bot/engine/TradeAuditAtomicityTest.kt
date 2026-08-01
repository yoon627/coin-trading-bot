package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * #52: 체결 확정 시 pending 해소(durable)와 감사 기록이 원자적인지 검증한다.
 *
 * 핵심 불변식 — **감사 커밋이 실패하면 메모리 상태 전이도 적용되지 않아야 한다.** 그래야 pending 이 살아남아
 * 다음 tick reconcile 이 재시도한다. 전이만 먼저 적용되면 DB 를 롤백해도 메모리에는 pending 이 없어
 * 아무도 재시도하지 않고 기록이 영구 유실된다(실제 현금흐름은 발생했는데 거래·손익 기록이 없는 상태).
 */
class TradeAuditAtomicityTest {

    private lateinit var upbitClient: UpbitClient
    private lateinit var stateService: TradingStateService
    private val properties = TradingProperties()

    /** commitFill 에 넘어온 "전이가 반영된 사본" 을 잡아 둔다 — 트랜잭션에 무엇이 실렸는지 확인용. */
    private val stagedStates = mutableListOf<TradingState>()
    private val committedRecords = mutableListOf<TradeRecord>()

    @BeforeEach
    fun setup() {
        upbitClient = mockk(relaxed = true)
        stateService = mockk(relaxed = true)
        stagedStates.clear()
        committedRecords.clear()
        // persistState 가 트랜잭션에 싣는 "전이 반영 사본" 을 잡는다.
        coEvery { stateService.upsert(any(), capture(stagedStates)) } returns Unit
    }

    /** 커밋이 성공하는 배선 — persistState 를 실행해 staged 사본을 캡처한다. */
    private fun managerWithCommitFill(
        commitFill: suspend (persistState: suspend () -> Unit, record: TradeRecord) -> Boolean,
        notifyTrade: suspend (record: TradeRecord) -> Unit = {},
    ) = PositionManager(
        upbitClient, properties, stateService, USER_ID,
        commitFill = commitFill,
        notifyTrade = notifyTrade,
    )

    private fun succeedingManager() = managerWithCommitFill(
        commitFill = { persistState, record ->
            committedRecords += record
            persistState()
            true
        },
    )

    /** 커밋이 실패하는 배선 — 감사 기록 저장이 transient 실패한 상황. */
    private fun failingManager() = PositionManager(
        upbitClient, properties, stateService, USER_ID,
        commitFill = { _, _ -> throw IllegalStateException("audit store unavailable") },
    )

    private fun buyPendingState() = TradingState(
        ticker = TICKER,
        pendingBuyUuid = BUY_UUID,
        pendingBuyStrategy = "combined",
    )

    private fun stubFilledBuy() {
        coEvery { upbitClient.getOrder(BUY_UUID) } returns
            Order(uuid = BUY_UUID, state = "done", executedVolume = "0.01")
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.01", avgBuyPrice = "50000000"),
        )
    }

    @Test
    fun `감사 커밋 후 메모리 전이를 적용하고 알림 실패를 격리한다`() = runTest {
        stubFilledBuy()
        val state = buyPendingState()
        val memoryAppliedWhenNotified = AtomicBoolean(false)
        val manager = managerWithCommitFill(
            commitFill = { persistState, _ ->
                persistState()
                true
            },
            notifyTrade = {
                memoryAppliedWhenNotified.set(state.position)
                throw IllegalStateException("discord down")
            },
        )

        val record = manager.reconcilePendingBuy(TICKER, state, PRICE)

        assertNotNull(record)
        assertTrue(memoryAppliedWhenNotified.get(), "알림 실패 전에도 커밋된 메모리 전이가 먼저 적용돼야 한다")
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid)
    }

    // --- 매수 체결 ---

    @Test
    fun `매수 감사 커밋이 실패하면 pending 이 살아남아 재시도 근거가 유지된다`() = runTest {
        stubFilledBuy()
        val state = buyPendingState()

        assertThrows<IllegalStateException> {
            failingManager().reconcilePendingBuy(TICKER, state, PRICE)
        }

        assertEquals(BUY_UUID, state.pendingBuyUuid, "pending 이 지워지면 다음 tick 이 재시도하지 않아 기록이 유실된다")
        assertEquals("combined", state.pendingBuyStrategy)
        assertFalse(state.position, "커밋 실패 시 포지션 전이가 적용되면 안 된다")
        assertEquals(0.0, state.avgBuyPrice)
    }

    @Test
    fun `매수 감사 커밋이 성공하면 전이가 적용되고 pending 이 해소된 사본이 커밋된다`() = runTest {
        stubFilledBuy()
        val state = buyPendingState()

        val record = succeedingManager().reconcilePendingBuy(TICKER, state, PRICE)

        assertNotNull(record)
        assertEquals(USER_ID, record!!.userId, "감사 기록에 userId 가 없으면 리더보드·PnL 집계에서 누락된다")
        assertEquals(BUY_UUID, record.exchangeOrderId, "멱등 dedup 키가 실려야 재시도 시 중복 insert 를 막는다")
        // 메모리 전이
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid)
        // 트랜잭션에 실린 사본도 pending 이 해소된 상태여야 원자성이 성립한다
        assertEquals(1, stagedStates.size)
        assertNull(stagedStates.single().pendingBuyUuid)
        assertTrue(stagedStates.single().position)
    }

    @Test
    fun `매수 후처리는 취소되어도 감사 커밋을 완주한다`() = runTest {
        stubFilledBuy()
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "KRW", balance = "1000000"),
            Account(currency = "BTC", balance = "0.01", avgBuyPrice = "50000000"),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = BUY_UUID)
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val committed = AtomicBoolean(false)
        val manager = managerWithCommitFill(
            commitFill = { persistState, _ ->
                commitEntered.complete(Unit)
                releaseCommit.await()
                persistState()
                committed.set(true)
                true
            },
        )
        val state = TradingState(ticker = TICKER)

        val job = async { manager.buy(TICKER, state, PRICE, "combined") }
        commitEntered.await()
        job.cancel()
        releaseCommit.complete(Unit)
        assertThrows<kotlinx.coroutines.CancellationException> { job.await() }

        assertTrue(committed.get(), "주문 후처리가 취소로 중단되면 감사 기록이 유실된다")
        assertTrue(state.position)
        assertNull(state.pendingBuyUuid)
    }

    // --- 매도 체결 ---

    @Test
    fun `매도 감사 커밋이 실패하면 pendingSell 이 살아남고 포지션이 유지된다`() = runTest {
        val state = TradingState(
            ticker = TICKER,
            position = true,
            avgBuyPrice = 50_000_000.0,
            holdVolume = 0.01,
        )
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.01", avgBuyPrice = "50000000"),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = SELL_UUID)
        coEvery { upbitClient.getOrder(SELL_UUID) } returns
            Order(uuid = SELL_UUID, state = "done", executedVolume = "0.01")

        val record = failingManager().sell(TICKER, state, PRICE, SellReason.TAKE_PROFIT)

        assertNull(record, "커밋이 실패했으면 거래가 확정된 것처럼 record 를 돌려주면 안 된다")
        assertEquals(SELL_UUID, state.pendingSellUuid, "pendingSell 이 남아야 다음 tick reconcile 이 청산을 확정한다")
        assertTrue(state.position, "커밋 실패 시 청산 전이가 적용되면 안 된다")
        assertEquals(0.01, state.holdVolume)
    }

    @Test
    fun `매도 감사 커밋이 성공하면 청산 전이가 적용된다`() = runTest {
        val state = TradingState(
            ticker = TICKER,
            position = true,
            avgBuyPrice = 50_000_000.0,
            holdVolume = 0.01,
        )
        coEvery { upbitClient.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.01", avgBuyPrice = "50000000"),
        )
        coEvery { upbitClient.placeOrder(any()) } returns Order(uuid = SELL_UUID)
        coEvery { upbitClient.getOrder(SELL_UUID) } returns
            Order(uuid = SELL_UUID, state = "done", executedVolume = "0.01")

        val record = succeedingManager().sell(TICKER, state, PRICE, SellReason.TAKE_PROFIT)

        assertNotNull(record)
        assertEquals(USER_ID, record!!.userId)
        assertFalse(state.position)
        assertNull(state.pendingSellUuid)
        assertEquals(2, stagedStates.size, "주문 접수 직후 pending 저장과 원자 커밋의 상태 저장을 구분한다")
        assertFalse(stagedStates.last().position, "트랜잭션에 실린 사본도 청산이 반영돼야 한다")
    }

    private companion object {
        const val TICKER = "KRW-BTC"
        const val USER_ID = 7L
        const val BUY_UUID = "buy-uuid-1"
        const val SELL_UUID = "sell-uuid-1"
        const val PRICE = 51_000_000.0
    }
}
