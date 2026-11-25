package com.trading.bot.engine

import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.StoredBook
import com.trading.bot.domain.TradingState
import com.trading.bot.domain.UserTradingParams
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.UserTradingParamsService
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import com.trading.bot.marketdata.MarketDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** 사용자 매매 값: 모든 엔진 생성이 그 값을 쓰고, 저장은 확인을 받아 보유 포지션 스냅샷과 함께 반영한다. */
class UserTradingManagerParamsTest {

    private val env = TradingProperties(autoStart = true)
    private val userParams = UserTradingParams(kValue = 0.8, maxLossPct = 2.0, maxInvestAmount = 50_000.0)
    private val mine = userParams.applyTo(env)
    private val userRepository = mockk<UserRepository>()
    private val botStateRepository = mockk<BotStateRepository>()
    private val tradingStateService = mockk<TradingStateService>(relaxed = true)
    private val params = mockk<UserTradingParamsService>()
    private val oldEngine = mockk<TradingEngine>(relaxed = true)
    private val newEngine = mockk<TradingEngine>(relaxed = true)
    private val strategy: TradingStrategy = mockk { every { name } returns "combined" }
    private lateinit var manager: UserTradingManager

    @BeforeEach
    fun setup() {
        manager = spyk(
            UserTradingManager(
                userRepository, botStateRepository, mockk(relaxed = true), mockk<DiscordNotifier>(relaxed = true),
                strategy, env, mockk<WebClient>(relaxed = true),
                mockk<UserSecretsService>(relaxed = true), mockk<MarketDataStore>(relaxed = true), tradingStateService,
                userTradingParams = params,
            ),
        )
        every { manager.createEngine(any(), any()) } returns newEngine
        every { userRepository.findById(1L) } returns Mono.just(UserEntity(id = 1L, username = "u", password = "p", upbitAccessKey = "ak", upbitSecretKey = "sk"))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(BotStateEntity(id = 3L, userId = 1L, running = true, tickers = "KRW-BTC"))
        every { botStateRepository.save(any()) } answers { Mono.just(firstArg()) }
        coEvery { params.find(1L, any()) } returns userParams
        every { newEngine.isRunning() } returns true
    }

    private fun engines(): ConcurrentHashMap<Long, TradingEngine> {
        val f = UserTradingManager::class.java.getDeclaredField("engines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as ConcurrentHashMap<Long, TradingEngine>
    }

    private fun running() {
        engines()[1L] = oldEngine
        every { oldEngine.isRunning() } returns true
        every { oldEngine.getUserTickers() } returns listOf("KRW-BTC")
    }

    /** 도는 엔진의 보유 상태(position). */
    private fun held(): TradingState = TradingState("KRW-BTC", avgBuyPrice = 100.0, position = true, exitParams = env.exitParamsSnapshot())

    /** DB 에서 읽은 보유 상태 — position 은 거래소 동기화 전이라 false, 장부 수량으로 본다. */
    private fun stored(): TradingState = TradingState("KRW-BTC", storedBook = StoredBook(0.01, 100.0), exitParams = env.exitParamsSnapshot())

    private fun tokenFor(params: UserTradingParams) = runBlocking {
        manager.saveParams(1L, params, null) { 99.0 }["confirm_token"] as String
    }

    // 사용자가 낮춘 한도는 시작·재기동 복원·키 저장 교체 어디서든 같아야 한다.
    @Test
    fun `start, restore and a key-save reload all build the engine with the user's params`() = runTest {
        manager.startBot(1L, listOf("KRW-BTC"))
        verify { manager.createEngine(any(), mine) }

        engines().clear()
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(BotStateEntity(id = 3L, userId = 1L, running = true, tickers = "KRW-BTC"))
        manager.restoreAllRunningBots()
        verify(exactly = 2) { manager.createEngine(any(), mine) }

        running()
        manager.reloadUserRuntime(1L)
        verify(exactly = 3) { manager.createEngine(any(), mine) }
    }

    // env 로 내려가면 사용자가 낮춘 매수 상한·손절이 조용히 풀린다.
    @Test
    fun `an unreadable params row fails the start and leaves a running engine alone on reload`() = runTest {
        coEvery { params.find(1L, any()) } throws RuntimeException("db down")

        assertThrows(BotControlPersistFailedException::class.java) { runBlocking { manager.startBot(1L, listOf("KRW-BTC")) } }
        running()
        assertThrows(RuntimeReloadFailedException::class.java) { runBlocking { manager.reloadUserRuntime(1L) } }

        verify(exactly = 0) { manager.createEngine(any(), any()) }
        coVerify(exactly = 0) { oldEngine.stop() }
    }

    @Test
    fun `saving params that move a held position needs a confirmation first`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())

        val result = manager.saveParams(1L, userParams, null) { 99.0 }

        assertEquals("confirm_required", result["status"])
        val change = (result["changes"] as List<*>).single() as PositionChange
        assertEquals(2.0, change.after.maxLossPct)
        assertTrue((result["confirm_token"] as String).isNotEmpty())
        coVerify(exactly = 0) { oldEngine.stop() }
        coVerify(exactly = 0) { params.saveAndRewrite(any(), any(), any(), any(), any()) }
    }

    // 상태를 읽은 뒤 값과 스냅샷을 한 트랜잭션으로 저장하고, 그 상태(새 버전)로 새 값 엔진을 띄운다.
    @Test
    fun `a confirmed save on a running bot rewrites held snapshots and restarts with the new params`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())
        val loaded = mapOf("KRW-BTC" to stored(), "KRW-ETH" to TradingState("KRW-ETH"))
        coEvery { tradingStateService.loadStates(1L, any()) } returns loaded
        coEvery { params.saveAndRewrite(1L, any(), userParams, any(), any()) } returns listOf("KRW-BTC")

        val result = manager.saveParams(1L, userParams, tokenFor(userParams)) { 99.0 }

        assertEquals("applied", result["status"])
        // 덮는 것은 확인한 포지션뿐이다.
        coVerify { params.saveAndRewrite(1L, any(), userParams, listOf(loaded.getValue("KRW-BTC")), mine.exitParamsSnapshot()) }
        verify { manager.createEngine(any(), mine) }
        coVerify { newEngine.start(listOf("KRW-BTC"), loaded) }
        assertSame(newEngine, engines()[1L])
    }

    // 트랜잭션이 커밋되지 않았으면 저장된 것이 없으므로 옛 엔진을 옛 값으로 되살린다.
    @Test
    fun `a rewrite that does not commit brings the old engine back`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())
        coEvery { tradingStateService.loadStates(1L, any()) } returns mapOf("KRW-BTC" to stored())
        coEvery { params.saveAndRewrite(any(), any(), any(), any(), any()) } throws RuntimeException("conflict")
        val token = tokenFor(userParams)

        val thrown = assertThrows(BotChangeFailedException::class.java) { runBlocking { manager.saveParams(1L, userParams, token) { 99.0 } } }

        assertEquals(UserTradingManager.PARAMS_NOT_APPLIED_MESSAGE, thrown.message)
        verify(exactly = 1) { oldEngine.resume() }
        assertSame(oldEngine, engines()[1L])
        verify(exactly = 0) { manager.createEngine(any(), any()) }
    }

    // 커밋 뒤에는 옛 엔진의 메모리가 옛 버전이라 그대로 되살리면 저장이 충돌한다 — 커밋한 상태(새 버전)로 옛 엔진을 띄운다(#409).
    @Test
    fun `a replacement that fails after the values committed restarts the old engine from the committed states`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())
        val loaded = mapOf("KRW-BTC" to stored())
        coEvery { tradingStateService.loadStates(1L, any()) } returns loaded
        coEvery { params.saveAndRewrite(1L, any(), userParams, any(), any()) } returns listOf("KRW-BTC")
        every { newEngine.start(any(), any()) } throws IllegalStateException("start failed")
        val token = tokenFor(userParams)

        val thrown = assertThrows(BotChangeFailedException::class.java) { runBlocking { manager.saveParams(1L, userParams, token) { 99.0 } } }

        assertEquals(UserTradingManager.PARAMS_SAVED_NOT_APPLIED_MESSAGE, thrown.message)
        verify(exactly = 0) { oldEngine.resume() }
        verify { oldEngine.start(listOf("KRW-BTC"), loaded) }
        assertSame(oldEngine, engines()[1L])
    }

    @Test
    fun `a committed save whose restore also fails says the bot is stopped`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())
        coEvery { tradingStateService.loadStates(1L, any()) } returns mapOf("KRW-BTC" to stored())
        coEvery { params.saveAndRewrite(1L, any(), userParams, any(), any()) } returns listOf("KRW-BTC")
        every { manager.createEngine(any(), any()) } throws IllegalStateException("build failed")
        every { oldEngine.start(any(), any()) } throws IllegalStateException("start failed")
        val token = tokenFor(userParams)

        val thrown = assertThrows(RuntimeReloadFailedException::class.java) { runBlocking { manager.saveParams(1L, userParams, token) { 99.0 } } }

        assertFalse(thrown.engineRestored)
        assertNull(engines()[1L])
    }

    // 멈춘 봇: 맵에 남은 정지 엔진의 미기록 상태를 먼저 남기고 빼고, 값과 스냅샷만 저장한다.
    @Test
    fun `a save on a stopped bot flushes the stopped engine first and does not start`() = runTest {
        engines()[1L] = oldEngine
        every { oldEngine.isRunning() } returns false
        coEvery { tradingStateService.loadStates(1L, any()) } returns mapOf("KRW-BTC" to stored())
        coEvery { params.saveAndRewrite(any(), any(), any(), any(), any()) } returns listOf("KRW-BTC")
        val token = tokenFor(userParams)

        val result = manager.saveParams(1L, userParams, token) { 99.0 }

        assertEquals("saved", result["status"])
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            oldEngine.flushUnpersisted()
            params.saveAndRewrite(1L, any(), userParams, any(), mine.exitParamsSnapshot())
        }
        assertTrue(engines()[1L] == null)
        verify(exactly = 0) { manager.createEngine(any(), any()) }
    }

    @Test
    fun `saving the same params with nothing to move does not restart the engine`() = runTest {
        running()
        every { oldEngine.getStates() } returns emptyMap()

        assertEquals("unchanged", manager.saveParams(1L, userParams, null) { 99.0 }["status"])

        coVerify(exactly = 0) { oldEngine.stop() }
    }

    // 확인은 미리본 내용에 묶인다 — 확인한 뒤 값을 더 좁혀 보내면 다시 확인을 받는다.
    @Test
    fun `a confirmation for other values asks again instead of saving`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held())
        val token = tokenFor(userParams)

        val result = manager.saveParams(1L, userParams.copy(maxLossPct = 1.0), token) { 99.0 }

        assertEquals("confirm_required", result["status"])
        assertTrue(result["confirm_token"] != token)
        coVerify(exactly = 0) { oldEngine.stop() }
    }

    // 잔량을 놓아 준 행은 엔진이 청산하지 않는다 — 바뀌는 포지션으로 세지 않는다.
    @Test
    fun `a released row is not a held position`() = runTest {
        running()
        every { oldEngine.getStates() } returns mapOf("KRW-BTC" to held().apply { position = false })
        coEvery { params.find(1L, any()) } returns UserTradingParams.NONE
        coEvery { tradingStateService.loadStates(1L, any()) } returns emptyMap()
        coEvery { params.saveAndRewrite(any(), any(), any(), any(), any()) } returns emptyList()

        assertEquals("applied", manager.saveParams(1L, userParams, null) { 99.0 }["status"])
    }

    // 멈춘 봇에서 DB 가 실패하면 500 이 아니라 안내 문구가 있는 503 이다.
    @Test
    fun `a stopped-bot save that cannot reach the db reports it`() = runTest {
        coEvery { tradingStateService.loadStates(1L, any()) } throws RuntimeException("db down")

        val thrown = assertThrows(BotControlPersistFailedException::class.java) { runBlocking { manager.saveParams(1L, userParams, null) { 99.0 } } }

        assertEquals(UserTradingManager.PARAMS_NOT_SAVED_MESSAGE, thrown.message)
    }

    // 재기동 복원도 env 로 내려가지 않는다 — 읽지 못하면 그 봇은 복원 실패로 남고 배포 health 가 안다.
    @Test
    fun `boot restore with an unreadable params row fails instead of using env`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(BotStateEntity(id = 3L, userId = 1L, running = true, tickers = "KRW-BTC"))
        coEvery { params.find(1L, any()) } throws RuntimeException("db down")

        manager.restoreAllRunningBots()

        assertEquals(RestoreStatus.FAILED, manager.restoreStatus)
        verify(exactly = 0) { manager.createEngine(any(), any()) }
    }

    @Test
    fun `combinations that skip every buy are refused, others only warned`() {
        assertTrue(manager.paramsProblems(UserTradingParams(maxInvestAmount = 5_000.0, maxLossPct = 5.0)).single().contains("최소 주문"))
        assertTrue(manager.paramsProblems(UserTradingParams(takeProfitPct = 1.0)).isEmpty())
        assertTrue(manager.paramsWarnings(UserTradingParams(takeProfitPct = 1.0)).single().contains("takeProfitPct(1.0)"))
    }
}
