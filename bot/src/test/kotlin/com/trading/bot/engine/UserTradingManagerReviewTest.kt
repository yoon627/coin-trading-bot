package com.trading.bot.engine

import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/** 확인이 필요한 주문은 halt 해제로 풀리지 않고 pending 해제가 사유를 지운다. pending 해제는 자기 거래소 행만 고친다. */
class UserTradingManagerReviewTest {

    private val botStateRepository = mockk<BotStateRepository>()
    private val tradingStateService = mockk<TradingStateService>(relaxed = true)
    private val strategy = mockk<TradingStrategy> { every { name } returns "combined" }
    private val engine = mockk<TradingEngine>(relaxed = true)

    private val manager = UserTradingManager(
        mockk<UserRepository>(), botStateRepository, mockk(relaxed = true), mockk(relaxed = true),
        strategy, TradingProperties(), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), tradingStateService,
    )

    private val review = "주문 확인 필요(SELL s-1): 체결 후보 2건"

    private fun registerEngine() {
        val field = UserTradingManager::class.java.getDeclaredField("engines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(manager) as ConcurrentHashMap<Long, TradingEngine>)[1L] = engine
    }

    private fun stoppedRow() {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns
            Mono.just(BotStateEntity(userId = 1L, running = false, strategy = "combined", tickers = "KRW-BTC"))
    }

    @Test
    fun `halt clear is refused with a conflict while the ticker needs an order review`() = runTest {
        registerEngine()
        every { engine.getStates() } returns mapOf("KRW-BTC" to TradingState("KRW-BTC", halted = true, haltReason = review, reviewReason = review))

        val result = manager.clearHalt(1L, "KRW-BTC")

        assertEquals(UserTradingManager.CONFLICT_CODE, result["code"], "$result")
        coVerify(exactly = 0) { engine.clearHalt(any()) }
    }

    @Test
    fun `pending clear also clears the review reason and keeps the halt`() = runTest {
        stoppedRow()
        val state = TradingState("KRW-BTC", halted = true, haltReason = review, reviewReason = review, pendingSellUuid = "s-1")
        coEvery { tradingStateService.loadState(1L, "KRW-BTC", Exchange.UPBIT) } returns state
        val saved = slot<TradingState>()
        coEvery { tradingStateService.upsert(1L, capture(saved)) } just Runs

        val result = manager.clearPending(1L, "KRW-BTC", TradeSide.SELL)

        assertEquals("cleared", result["status"], "$result")
        assertNull(saved.captured.reviewReason)
        assertTrue(saved.captured.halted)
    }

    @Test
    fun `pending clear refuses a row of another exchange and writes nothing`() = runTest {
        stoppedRow()
        coEvery { tradingStateService.loadState(1L, "KRX-005930", Exchange.UPBIT) } returns
            TradingState("KRX-005930", exchange = Exchange.KIS_PAPER, pendingSellUuid = "KIS_PAPER:20261020:0001")

        val result = manager.clearPending(1L, "KRX-005930", TradeSide.SELL)

        assertEquals(UserTradingManager.CONFLICT_CODE, result["code"], "$result")
        coVerify(exactly = 0) { tradingStateService.upsert(any(), any()) }
    }
}
