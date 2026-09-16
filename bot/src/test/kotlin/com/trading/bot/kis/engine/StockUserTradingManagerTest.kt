package com.trading.bot.kis.engine

import com.trading.bot.engine.RuntimeReloadFailedException
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.order.StockOrderReconciler
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.StockPositionStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * 키 교체가 실행 중 엔진에 반영되는지(#91). Upbit `UserTradingManagerTest` 의 reload 계약과 같은 축:
 * 새 키의 엔진으로 교체 + durable 복원 + 실패 시 옛 엔진 복귀·`RuntimeReloadFailedException(engineRestored)`.
 */
class StockUserTradingManagerTest {

    private lateinit var userRepository: UserRepository
    private lateinit var botStateRepository: BotStateRepository
    private lateinit var kisClientFactory: KisClientFactory
    private lateinit var manager: StockUserTradingManager
    private val oldEngine: KisStockTradingEngine = mockk(relaxed = true)
    private val newEngine: KisStockTradingEngine = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        botStateRepository = mockk(relaxed = true)
        kisClientFactory = mockk(relaxed = true)
        manager = spyk(
            StockUserTradingManager(
                userRepository, botStateRepository, kisClientFactory,
                mockk<StockOrderService>(relaxed = true), mockk<StockOrderReconciler>(relaxed = true),
                emptyList(), TradingProperties(), mockk<MarketDataStore>(relaxed = true),
                mockk<KisMarketCalendar>(relaxed = true), KisProperties(), mockk<StockPositionStateService>(relaxed = true),
            ),
        )
        every { manager.createEngine(any()) } returns newEngine
        every { userRepository.findById(1L) } returns Mono.just(user())
        every { oldEngine.getActiveSymbols() } returns listOf("005930")
        every { oldEngine.getActiveStrategyName() } returns "rsi_bounce"
        every { oldEngine.isRunning() } returns true
    }

    private fun engines(): ConcurrentHashMap<Long, KisStockTradingEngine> {
        val f = StockUserTradingManager::class.java.getDeclaredField("engines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as ConcurrentHashMap<Long, KisStockTradingEngine>
    }

    private fun user() = UserEntity(
        id = 1L, username = "u", password = "p",
        kisAppKey = "enc:ak", kisAppSecret = "enc:sk", kisCano = "12345678", kisAcntPrdtCd = "01",
    )

    @Test
    fun `reload replaces a running engine with one built from the new keys and restores its positions`() = runTest {
        engines()[1L] = oldEngine

        manager.reloadUserRuntime(1L)

        // 옛 엔진을 완전히 멈춘 뒤(join) 새 엔진을 복원·기동한다 — 순서가 바뀌면 두 계좌가 동시에 주문한다.
        coVerifyOrder {
            oldEngine.stop()
            newEngine.restorePositionState(listOf("005930"))
            newEngine.start(listOf("005930"))
        }
        verify { newEngine.setStrategy("rsi_bounce") }
        verify { kisClientFactory.invalidate(1L) } // 캐시된 옛 키 클라이언트가 새 엔진에 재사용되지 않게
        assertSame(newEngine, engines()[1L])
    }

    @Test
    fun `reload swaps a stopped engine without restoring or starting it`() = runTest {
        // restoreOnStartup 이 start 못 한 엔진도 여기 해당 — 다음 startBot 이 복원·기동한다.
        every { oldEngine.isRunning() } returns false
        engines()[1L] = oldEngine

        manager.reloadUserRuntime(1L)

        coVerify(exactly = 0) { newEngine.restorePositionState(any()) }
        verify(exactly = 0) { newEngine.start(any()) }
        assertSame(newEngine, engines()[1L])
    }

    @Test
    fun `reload without an engine only invalidates the client cache`() = runTest {
        manager.reloadUserRuntime(1L)

        verify { kisClientFactory.invalidate(1L) }
        verify(exactly = 0) { manager.createEngine(any()) }
        assertNull(engines()[1L])
    }

    @Test
    fun `reload restarts the old engine and reports engineRestored when the replacement fails to restore`() = runTest {
        engines()[1L] = oldEngine
        coEvery { newEngine.restorePositionState(any()) } throws IllegalStateException("db down")

        val ex = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()

        assertTrue(ex is RuntimeReloadFailedException && ex.engineRestored, "옛 자격증명 엔진으로 복귀했음을 구분해 알려야 한다: $ex")
        verify { oldEngine.start(listOf("005930")) }
        verify(exactly = 0) { newEngine.start(any()) }
        assertSame(oldEngine, engines()[1L]) // 새 엔진은 등록된 적이 없어야 한다
    }

    @Test
    fun `reload restarts the old engine when building the replacement itself fails`() = runTest {
        engines()[1L] = oldEngine
        every { manager.createEngine(any()) } throws IllegalStateException("kisAppKey missing")

        val ex = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()

        assertTrue(ex is RuntimeReloadFailedException && ex.engineRestored)
        verify { oldEngine.start(listOf("005930")) }
        assertSame(oldEngine, engines()[1L])
    }

    @Test
    fun `reload removes the engine and reports engineRestored=false when the old engine cannot be restarted`() = runTest {
        // KIS 엔진의 start() 는 실제로 거의 실패하지 않는다 — mock 으로만 만드는 경로지만 계약(정지 엔진을 맵에 남기지 않음)은 고정한다.
        engines()[1L] = oldEngine
        coEvery { newEngine.restorePositionState(any()) } throws IllegalStateException("db down")
        every { oldEngine.start(any()) } throws IllegalStateException("cannot restart")

        val ex = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()

        assertTrue(ex is RuntimeReloadFailedException && !ex.engineRestored, "$ex")
        assertNull(engines()[1L], "정지된 옛 엔진을 남기면 다음 start 가 옛 키로 거래를 재개한다")
    }

    @Test
    fun `reload restarts the old engine and rethrows when cancelled mid-restore`() = runTest {
        engines()[1L] = oldEngine
        coEvery { newEngine.restorePositionState(any()) } throws CancellationException("request cancelled")

        val ex = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()

        assertTrue(ex is CancellationException, "$ex")
        verify { oldEngine.start(listOf("005930")) }
        assertSame(oldEngine, engines()[1L])
    }

    @Test
    fun `reload falls back to the saved symbols when the running engine reports none`() = runTest {
        engines()[1L] = oldEngine
        every { oldEngine.getActiveSymbols() } returns emptyList()
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS") } returns
            Mono.just(BotStateEntity(userId = 1L, running = true, strategy = "rsi_bounce", tickers = "005930,000660", exchange = "KIS"))

        manager.reloadUserRuntime(1L)

        coVerify { newEngine.restorePositionState(listOf("005930", "000660")) }
        verify { newEngine.start(listOf("005930", "000660")) }
    }

    @Test
    fun `reload does not start a ghost engine when no symbols are known`() = runTest {
        engines()[1L] = oldEngine
        every { oldEngine.getActiveSymbols() } returns emptyList()
        every { botStateRepository.findByUserIdAndExchange(1L, "KIS") } returns Mono.empty()

        manager.reloadUserRuntime(1L)

        verify(exactly = 0) { newEngine.start(any()) }
        assertSame(newEngine, engines()[1L])
    }

    @Test
    fun `caller cancellation while the old engine drains does not cut the stop short`() = runBlocking {
        // HTTP 요청이 join 중 끊기면 정지된 옛 키 엔진이 맵에 남아 다음 startBot 이 옛 계좌로 재기동한다.
        engines()[1L] = oldEngine
        val stopEntered = CompletableDeferred<Unit>()
        val stopCompleted = AtomicBoolean(false)
        coEvery { oldEngine.stop() } coAnswers {
            stopEntered.complete(Unit)
            delay(150)
            stopCompleted.set(true)
        }

        val job = launch { manager.reloadUserRuntime(1L) }
        stopEntered.await()
        job.cancelAndJoin()

        assertTrue(stopCompleted.get(), "호출자 취소가 옛 엔진의 drain 을 중단시키면 안 된다")
    }
}
