package com.trading.bot.characterization

import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.PositionManager
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 응답을 못 받은 Upbit 주문의 확정 규칙을 고정한다. identifier 조회가 연속 2회 null 이고 첫 null 뒤 60초가 지났으며
 * 잔고 흔적이 없을 때만 미접수로 풀고, 흔적이 보이면 이 엔진 안에서는 풀지 않는다. 조회 실패는 연속 횟수와 60초 타이머를 끊는다.
 * 거절 오류 이름 목록만 pending 을 즉시 지우고 다음 매수는 기다림 없이 다시 보낸다. 조회 실패는 halt 카운터에 세고,
 * 흔적 없는 404 는 카운터를 올리지도 리셋하지도 않는다.
 */
class UnknownOrderCharacterizationTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-10-18T00:00:00Z"), TradingDay.KST)
    private val sent = mutableListOf<OrderRequest>()

    private fun manager(props: TradingProperties = TradingProperties()) = PositionManager(upbit, props, stateService, 1L, clock = clock)

    /** 응답이 끊긴 매수를 만들고 그 identifier 를 돌려준다. */
    private suspend fun lostBuy(manager: PositionManager, state: TradingState, failure: Exception = RuntimeException("ReadTimeout")): String? {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.placeOrder(any()) } coAnswers { sent += firstArg<OrderRequest>(); throw failure }
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        return sent.last().identifier
    }

    @Test
    fun `a lost buy response is resolved by identifier, never by uuid and never by resending`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(1, sent.size)
        coVerify(exactly = 1) { upbit.getOrderByIdentifier(identifier) }
        coVerify(exactly = 0) { upbit.getOrder(any()) }
        assertEquals(identifier, state.pendingBuyIdentifier)
    }

    @Test
    fun `two not-found lookups 60 seconds apart with no balance change release the pending`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)
        clock.advance(60)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertFalse(state.hasPendingBuy())
        assertEquals(0, state.reconcileFailureCount)
    }

    @Test
    fun `two not-found lookups only 59 seconds apart keep the pending`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        clock.advance(59)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertEquals(0, state.reconcileFailureCount)
    }

    @Test
    fun `a not-found lookup without a balance change neither raises nor resets the halt counter`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        state.reconcileFailureCount = 3

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertEquals(3, state.reconcileFailureCount)
    }

    @Test
    fun `a failed lookup between two not-found lookups restarts the count and the 60 second timer`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!

        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        clock.advance(30)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("503")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        clock.advance(30)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        // 끊기지 않았다면 두 번째 null 이 첫 null 뒤 60초라 여기서 풀렸다.
        assertEquals(identifier, state.pendingBuyIdentifier)
    }

    @Test
    fun `a balance trace keeps the pending and it is not released later even after the trace goes away`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        coEvery { upbit.getAccounts() } returns listOf(krw())
        repeat(3) {
            clock.advance(120)
            manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        }

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertFalse(state.position)
        assertEquals(0, state.reconcileFailureCount) // 흔적 판정은 halt 카운터에 세지 않는다
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "create_ask_error", "create_bid_error", "insufficient_funds_ask", "insufficient_funds_bid",
            "under_min_total_ask", "under_min_total_bid", "over_krw_funds_bid", "validation_error",
            "invalid_parameter", "invaild_parameter", "notfoundmarket", "market_offline",
        ],
    )
    fun `a 4xx rejection with a listed error name clears the pending at once`(errorName: String) = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        lostBuy(manager, state, UpbitApiException(400, errorName, "rejected", "{}"))

        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `after a listed rejection the next buy is sent again right away`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        lostBuy(manager, state, UpbitApiException(400, "insufficient_funds_bid", "rejected", "{}"))

        val start = currentTime
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")

        assertEquals(2, sent.size)
        assertEquals(0L, currentTime - start)
    }

    @ParameterizedTest
    @CsvSource(
        "400, duplicated_identifier",
        "429, too_many_requests",
        "401, jwt_verification",
        "500, create_bid_error",
        "400, ''",
    )
    fun `any other API error keeps the pending for identifier lookup`(status: Int, errorName: String) = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state, UpbitApiException(status, errorName.ifEmpty { null }, null, "{}"))

        assertEquals(identifier, state.pendingBuyIdentifier)
    }

    @Test
    fun `failed identifier lookups and unreadable balances count toward the halt threshold`() = runTest {
        val manager = manager(TradingProperties(reconcileHaltThreshold = 2))
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!

        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("503")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(1, state.reconcileFailureCount)
        assertFalse(state.halted)

        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals(2, state.reconcileFailureCount)
        assertTrue(state.halted)
        assertEquals(identifier, state.pendingBuyIdentifier)
    }

    @Test
    fun `an order found by identifier is adopted by its uuid`() = runTest {
        val manager = manager()
        val state = TradingState("KRW-BTC")
        val identifier = lostBuy(manager, state)!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(uuid = "u-9", state = "wait")

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals("u-9", state.pendingBuyUuid)
        assertNull(state.pendingBuyIdentifier)
    }
}
