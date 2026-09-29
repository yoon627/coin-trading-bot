package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.bot.persistence.entity.TradeRecordEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.CapturingSlot
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import org.springframework.transaction.reactive.TransactionalOperator

class UserTradingManagerTest {

    private lateinit var userRepository: UserRepository
    private lateinit var botStateRepository: BotStateRepository
    private lateinit var tradeExecutionService: TradeExecutionService
    private lateinit var discordNotifier: DiscordNotifier
    private lateinit var userSecretsService: UserSecretsService
    private lateinit var marketDataStore: MarketDataStore
    private lateinit var tradingStateService: TradingStateService
    private lateinit var upbitWebClient: WebClient
    private lateinit var manager: UserTradingManager
    private val mockEngine: TradingEngine = mockk(relaxed = true)
    // 실제 앱처럼 등록 전략이 있어야 요청 전략 검증이 "combined" 를 알 수 없는 이름으로 거절하지 않는다.
    private val registeredStrategies: List<TradingStrategy> = listOf("combined", "golden_cross").map { strategyName ->
        mockk<TradingStrategy> { every { name } returns strategyName }
    }

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        botStateRepository = mockk()
        tradeExecutionService = mockk(relaxed = true)
        discordNotifier = mockk(relaxed = true)
        userSecretsService = mockk(relaxed = true)
        marketDataStore = mockk(relaxed = true)
        tradingStateService = mockk(relaxed = true)
        upbitWebClient = mockk(relaxed = true)
        manager = spyk(
            UserTradingManager(
                userRepository, botStateRepository, tradeExecutionService, discordNotifier,
                registeredStrategies, TradingProperties(autoStart = true), upbitWebClient,
                userSecretsService, marketDataStore, tradingStateService,
            ),
        )
        // engine.start 의 실코루틴 기동을 피하고 restore 오케스트레이션만 검증하기 위한 seam.
        every { manager.createEngine(any()) } returns mockEngine
    }

    private fun engines(): ConcurrentHashMap<Long, TradingEngine> {
        val f = UserTradingManager::class.java.getDeclaredField("engines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as ConcurrentHashMap<Long, TradingEngine>
    }

    private fun runningState(userId: Long) =
        BotStateEntity(userId = userId, running = true, strategy = "combined", tickers = "KRW-BTC")

    private fun user(userId: Long) =
        UserEntity(id = userId, username = "u$userId", password = "p", upbitAccessKey = "ak", upbitSecretKey = "sk")

    @Test
    fun `restore retries when durable state load fails instead of stranding an unstarted engine`() = runTest {
        // loadStates 가 터지면 engines 에는 생성만 되고 기동 안 된 엔진이 남는다. 그 엔진의 존재만으로
        // 다음 시도가 "이미 복원됨" 으로 판단하면 그 유저는 프로세스 수명 내내 영구 미복원(무증상)이 된다.
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { mockEngine.isRunning() } returns false
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down") andThen emptyMap()

        manager.restoreAllRunningBots()

        coVerify(exactly = 1) { mockEngine.start(any(), any()) } // 재시도에서 실제로 기동돼야 한다
    }

    @Test
    fun `reload restores the running engine when durable state load fails`() = runTest {
        // 교체 실패는 정지 의도가 아니다. 여기서 포기하면 stop 된 엔진만 남아 보유 포지션의 손절이
        // 무기한 중단되는데, running=true 라 겉으로는 정상으로 보인다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down")

        // 되살리기는 하되 호출자에게 알린다 — 조용히 성공을 반환하면 옛 자격증명으로 계속
        // 거래하는 것을 사용자가 알 수 없다(#51).
        assertThrows(RuntimeReloadFailedException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        assertSame(mockEngine, engines()[1L], "로드 실패 시 교체 엔진이 등록되면 안 된다")
        verify(exactly = 0) { manager.createEngine(any()) }
        // 옛 엔진 재기동 — 직전 사용자 목록·메모리 상태 그대로여야 목록 밖 잔류 포지션이 복귀 뒤에도 관리된다(#226).
        // 빈 상태로 start 하면 그 포지션이 빠진다.
        verify(exactly = 1) { mockEngine.resume() }
        coVerify(exactly = 0) { mockEngine.start(any(), any()) }
        verify(exactly = 0) { mockEngine.getActiveTickers() }
    }

    @Test
    fun `reload rethrows only after the old engine is back up`() = runTest {
        // 순서가 뒤바뀌면 stop 된 엔진만 남아 PR #50 이 막으려던 결함이 되살아난다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down")

        runCatching { manager.reloadUserRuntime(1L) }

        coVerify(ordering = Ordering.ORDERED) {
            mockEngine.stop()
            mockEngine.resume()
        }
    }

    @Test
    fun `reload failure carries the original cause`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        val cause = RuntimeException("db down")
        coEvery { tradingStateService.loadStates(1L) } throws cause

        val thrown = assertThrows(RuntimeReloadFailedException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        assertSame(cause, thrown.cause)
        assertEquals(1L, thrown.userId)
        assertTrue(thrown.engineRestored, "옛 엔진 재기동에 성공했으면 restored=true 여야 한다")
    }

    @Test
    fun `restore failure is reported as engine stopped`() = runTest {
        // 되살리기마저 실패하면 엔진이 정지된 채 남는다 — "이전 설정으로 거래 중" 과 정반대라
        // 호출자가 다른 문구를 쓸 수 있게 구분돼야 한다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        val loadFailure = RuntimeException("db down")
        coEvery { tradingStateService.loadStates(1L) } throws loadFailure
        val restoreFailure = RuntimeException("engine start failed")
        every { mockEngine.resume() } throws restoreFailure

        val thrown = assertThrows(RuntimeReloadFailedException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        assertFalse(thrown.engineRestored, "복귀 실패면 restored=false 여야 한다")
        assertSame(restoreFailure, thrown.cause)
        // 원래 실패 원인도 잃지 않는다 — 진단에 둘 다 필요하다.
        assertSame(loadFailure, thrown.cause!!.suppressed.single())
        // 정지 엔진이 남으면 안내대로 누른 /api/bot/start 가 그것을 재사용해 옛 키로 거래를 재개한다.
        assertNull(engines()[1L], "복귀 실패 시 정지된 옛 엔진은 맵에서 제거돼야 한다")
    }

    @Test
    fun `cancellation still restores the old engine before propagating`() = runTest {
        // 취소를 그대로 전파하면 stop() 된 엔진만 남아 손절이 무기한 멈추고, 이후 reload 는
        // wasRunning=false 로 보아 되살리지도 않는다. 복구는 하되 취소는 삼키지 않는다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        verify(exactly = 1) { mockEngine.resume() } // 복구는 수행
        assertSame(mockEngine, engines()[1L], "취소 시 엔진이 교체되면 안 된다")
    }

    @Test
    fun `restore skips a user whose engine is already running`() = runTest {
        engines()[1L] = mockEngine // 사용자가 이미 start 로 개입한 상태 시뮬
        every { mockEngine.isRunning() } returns true
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))

        manager.restoreAllRunningBots()

        // lock 획득 후 engines 재확인으로 skip — 새 엔진 생성도, 기존 엔진 재기동(setStrategy/start)도 없다.
        // (구 computeIfAbsent 는 createEngine 만 skip 하고 실행 중 엔진에 start 를 다시 걸어 유령 엔진을 만들었다.)
        verify(exactly = 0) { manager.createEngine(any()) }
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
        verify(exactly = 0) { mockEngine.start(any()) }
    }

    @Test
    fun `restore retries transient DB failure then succeeds`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returnsMany listOf(
            Flux.error(RuntimeException("db temporarily down")),
            Flux.just(runningState(1L)),
        )
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        manager.restoreAllRunningBots()

        // 첫 조회 실패 후 backoff 재시도로 복원 성공(가상시간이 delay 를 즉시 진행).
        verify(exactly = 1) { manager.createEngine(any()) }
    }

    @Test
    fun `restore logs error after exhausting retries`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.error(RuntimeException("user db down"))

        val logger = LoggerFactory.getLogger(UserTradingManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            manager.restoreAllRunningBots()

            val errors = appender.list.filter { it.level == Level.ERROR }
            assertTrue(
                errors.any { it.formattedMessage.contains("봇 미복원") },
                "재시도 소진 후 '봇 미복원' ERROR alert 가 없음: ${errors.map { it.formattedMessage }}",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `shutdown stops all running engines`() {
        val engine1 = mockk<TradingEngine>(relaxed = true)
        val engine2 = mockk<TradingEngine>(relaxed = true)
        engines()[1L] = engine1
        engines()[2L] = engine2

        manager.stop() // SmartLifecycle.stop

        // 모든 엔진을 stop(cancelAndJoin) — 진행 중 tick 완주 후 종료.
        coVerify { engine1.stop() }
        coVerify { engine2.stop() }
    }

    @Test
    fun `restore does not start engines once shutting down`() = runTest {
        // SmartLifecycle.stop 이 shuttingDown 을 세우면 이후 restore 는 신규 엔진을 기동하지 않는다
        // (backoff 중 SIGTERM → shutdown 후 엔진 기동으로 아무도 stop 안 하는 유령 엔진 방지, M5).
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        manager.stop() // shuttingDown = true
        manager.restoreAllRunningBots()

        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `startBot is rejected while shutting down`() = runTest {
        // restore 뿐 아니라 API 경로(startBot)도 종료 중이면 신규 엔진을 기동하지 않는다(M5 일관, 재검토 발견).
        manager.stop() // shuttingDown = true

        val result = manager.startBot(1L, listOf("KRW-BTC"), "combined")

        assertTrue(result["error"] == "Service is shutting down", "종료 중 startBot 이 거부되지 않음: $result")
        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `restore logs error when all DB queries fail`() = runTest {
        // 모든 attempt 에서 bot state 조회가 실패하면 복원 0건 — pendingUserIds 는 비어 있어도 alert 해야 한다(M2).
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.error(RuntimeException("db down"))

        val logger = LoggerFactory.getLogger(UserTradingManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            manager.restoreAllRunningBots()

            val errors = appender.list.filter { it.level == Level.ERROR }
            assertTrue(
                errors.any { it.formattedMessage.contains("봇 미복원") && it.formattedMessage.contains("조회") },
                "DB 조회 전실패 시 '봇 미복원' 조회실패 ERROR 가 없음: ${errors.map { it.formattedMessage }}",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `createEngine wires actual atomic commit before post-commit notification`() = runTest {
        val client = mockk<UpbitClient>()
        val tradeRecordRepository = mockk<TradeRecordRepository>()
        val tradeExecutionRepository = mockk<TradeExecutionRepository>()
        val transactionalOperator = mockk<TransactionalOperator>()
        val auditNotifier = mockk<DiscordNotifier>()
        val state = TradingState(
            ticker = "KRW-BTC",
            pendingBuyUuid = "fill-1",
            pendingBuyStrategy = "combined",
        )
        val notifiedAfterMemory = AtomicBoolean(false)

        every { transactionalOperator.transactional(any<Mono<Any>>()) } answers { firstArg() }
        every { tradeExecutionRepository.existsByUserIdAndExchangeOrderId(1L, "fill-1") } returns Mono.just(false)
        every { tradeExecutionRepository.save(any()) } returns Mono.just(
            TradeExecutionEntity(
                userId = 1L,
                exchange = "UPBIT",
                market = "KRW-BTC",
                side = "BUY",
                price = 51_000_000.0,
                volume = 0.01,
                totalAmount = 510_000.0,
                exchangeOrderId = "fill-1",
            )
        )
        coEvery { tradeRecordRepository.save(any()) } returns TradeRecordEntity(
            ticker = "KRW-BTC",
            side = "BUY",
            price = 51_000_000.0,
            volume = 0.01,
            totalAmount = 510_000.0,
            userId = 1L,
        )
        coEvery { tradingStateService.upsert(1L, any()) } returns Unit
        coEvery { client.getOrder("fill-1") } returns Order(
            uuid = "fill-1",
            state = "done",
            executedVolume = "0.01",
        )
        coEvery { client.getAccounts() } returns listOf(
            Account(currency = "BTC", balance = "0.01", avgBuyPrice = "50000000"),
        )
        every { auditNotifier.sendTradeEmbed(any(), any(), any(), any()) } answers {
            notifiedAfterMemory.set(state.position)
            throw IllegalStateException("discord down")
        }

        val actualTradeExecutionService = TradeExecutionService(
            tradeRecordRepository,
            tradeExecutionRepository,
            auditNotifier,
            transactionalOperator,
            TradingProperties(),
        )
        manager = spyk(
            UserTradingManager(
                userRepository, botStateRepository, actualTradeExecutionService, auditNotifier,
                emptyList(), TradingProperties(autoStart = true), upbitWebClient,
                userSecretsService, marketDataStore, tradingStateService,
            ),
        )
        every { manager.createEngine(any()) } answers { callOriginal() }
        every { manager.createUpbitClient(any()) } returns client

        val engine = manager.createEngine(user(1L))
        val positionManagerField = TradingEngine::class.java.getDeclaredField("positionManager").apply {
            isAccessible = true
        }
        val positionManager = positionManagerField.get(engine) as PositionManager

        val record = positionManager.reconcilePendingBuy("KRW-BTC", state, 51_000_000.0)

        assertTrue(record != null)
        assertTrue(state.position)
        assertTrue(notifiedAfterMemory.get(), "실제 UserTradingManager 배선에서도 알림보다 메모리 전이가 먼저여야 한다")
        coVerify(exactly = 1) { tradeRecordRepository.save(any()) }
        verify(exactly = 1) { tradeExecutionRepository.save(any()) }
    }

    @Test
    fun `restore reports the strategy the engine actually uses, not the one in the database`() = runTest {
        // DB 에 있는 전략명이 현재 bean 목록에 없으면(전략 제거·rename·revert 후) setStrategy 는 false 를
        // 반환하고 엔진은 폴백 전략으로 돈다. 그 사실을 알리지 않으면 로그·상태 응답은 DB 값을 그대로
        // 보고해, 운영자가 실제와 다른 전략이 매매 중인 것을 모른다.
        val stale = BotStateEntity(userId = 7L, running = true, strategy = "removed_strategy", tickers = "KRW-BTC")
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(stale)
        every { botStateRepository.findByUserIdAndExchange(7L, "UPBIT") } returns Mono.just(stale)
        coEvery { userRepository.findById(7L) } returns Mono.just(user(7L))
        every { mockEngine.setStrategy("removed_strategy") } returns false
        every { mockEngine.getActiveStrategyName() } returns "volatility_breakout"

        manager.restoreAllRunningBots()

        // getStatus 는 engine 을 우선 읽어 이미 정확하다. 문제는 내부 캐시다 — 엔진이 사라진 뒤
        // (재시작·reload) startBot 이 이 값을 다시 setStrategy 에 넘기므로, DB 의 죽은 이름이 남으면
        // 매번 폴백을 반복하면서 로그에는 그 이름이 계속 찍힌다.
        val f = UserTradingManager::class.java.getDeclaredField("userStrategies").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = f.get(manager) as ConcurrentHashMap<Long, String>
        assertEquals("volatility_breakout", cache[7L], "캐시에 DB 의 죽은 전략명이 그대로 남았다")
    }

    @Test
    fun `startBot persists the requested tickers, not the engine's derived active set`() = runTest {
        // 자동 유니버스·적립 티커가 합쳐진 활성 집합을 bot_state.tickers 에 되쓰면, 기능을 꺼도 그날의 목록이
        // 사용자 의도로 굳어 되돌릴 수 없다 — 저장은 사용자가 준 목록만.
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC", "KRW-ETH", "KRW-SOL")
        every { mockEngine.getActiveStrategyName() } returns "combined"

        manager.startBot(1L, listOf("KRW-ETH"), null)

        assertEquals("KRW-ETH", saved.captured.tickers)
    }

    // --- 사용자 목록과 파생 활성 집합의 분리 (#226) ---
    // 재기동·실행 중 start 는 엔진의 사용자 목록을 기준으로 한다. 파생 집합(적립·잔류·auto 선정)을 사용자 의도로 넘기면
    // 목록에서 뺀 티커가 신규 진입 대상으로 승격된다.

    private fun userStrategies(): ConcurrentHashMap<Long, String> {
        val f = UserTradingManager::class.java.getDeclaredField("userStrategies").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as ConcurrentHashMap<Long, String>
    }

    @Test
    fun `reload starts the replacement with the user list as is, never the config list or the derived active set`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        // 적립만 운용하는 엔진 — 빈 사용자 목록을 설정 목록으로 바꾸면 신규 진입 대상이 조용히 생긴다.
        every { mockEngine.getUserTickers() } returns emptyList()
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        val loaded = mapOf("KRW-XRP" to TradingState("KRW-XRP"))
        coEvery { tradingStateService.loadStates(1L) } returns loaded

        manager.reloadUserRuntime(1L)

        coVerify(exactly = 1) { mockEngine.start(emptyList(), loaded) }
        verify(exactly = 0) { mockEngine.getActiveTickers() }
    }

    @Test
    fun `startBot on a running engine with a different ticker list is refused before any side effect`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC", "KRW-ETH")

        val result = manager.startBot(1L, listOf("KRW-BTC"), "combined")

        assertEquals("conflict", result["code"])
        assertTrue(result.containsKey("error"))
        coVerify(exactly = 0) { mockEngine.start(any(), any()) }
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
        coVerify(exactly = 0) { tradingStateService.loadStates(any()) }
        verify(exactly = 0) { botStateRepository.save(any()) }
        assertNull(userStrategies()[1L])
    }

    @Test
    fun `startBot on a running engine with the same list in another order or case reports already_running`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        // bot_state 에서 온 목록은 trim 만 돼 있다 — 요청(대문자·distinct)과 순서·대소문자만 달라도 같은 목록이다.
        every { mockEngine.getUserTickers() } returns listOf("krw-btc", "KRW-ETH")
        every { mockEngine.setStrategy("combined") } returns true
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.startBot(1L, listOf("KRW-ETH", "KRW-BTC"), "combined")

        assertEquals("already_running", result["status"])
        assertEquals("combined", result["strategy"])
        assertEquals("krw-btc,KRW-ETH", saved.captured.tickers)
        assertEquals("combined", saved.captured.strategy)
        verify(exactly = 1) { mockEngine.setStrategy("combined") }
        coVerify(exactly = 0) { mockEngine.start(any(), any()) }
    }

    @Test
    fun `getStatus exposes the config default list and the running engine's ticker groups`() {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-SOL")
        every { mockEngine.getAccumulateTickers() } returns listOf("KRW-ADA")
        every { mockEngine.getEntryTickers() } returns listOf("KRW-A")
        every { mockEngine.getExitOnlyTickers() } returns listOf("KRW-XRP")

        val status = manager.getStatus(1L)

        // 미리 채울 기본값은 목록 없이 시작할 때 쓰는 설정 목록과 같은 소스다.
        assertEquals(listOf("KRW-BTC"), status["default_tickers"])
        assertEquals(listOf("KRW-SOL"), status["user_tickers"])
        assertEquals(listOf("KRW-A"), status["entry_tickers"])
        assertEquals(listOf("KRW-ADA"), status["accumulate_tickers"])
        assertEquals(listOf("KRW-XRP"), status["exit_only_tickers"])
    }

    @Test
    fun `getStatus shows no ticker groups for an engine that is registered but not running`() {
        // 기동 전에 실패한 restore·wasRunning=false reload 는 맵에 엔진을 남긴다 — 그 옛 목록을 실행 중처럼 보이면 안 된다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.getUserTickers() } returns listOf("KRW-SOL")

        val status = manager.getStatus(1L)

        assertEquals(emptyList<String>(), status["user_tickers"])
        assertEquals(listOf("KRW-BTC"), status["default_tickers"])
    }

    @Test
    fun `startBot on a running engine without a list keeps the engine's list instead of the config list`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-SOL")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.startBot(1L, null, null)

        assertEquals("already_running", result["status"])
        assertEquals("KRW-SOL", saved.captured.tickers)
    }

    // --- 봇 제어의 상태 저장 실패 (#228) ---
    // 저장이 실패했는데 성공으로 답하면 운영자가 멈춘 봇이 다음 재시작 때 실자금으로 다시 돈다(또는 돌던 봇이 복원되지 않는다).

    private fun unpersistedStops(): MutableSet<Long> {
        val f = UserTradingManager::class.java.getDeclaredField("unpersistedStops").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as MutableSet<Long>
    }

    @Test
    fun `stop that cannot persist still stops the engine and reports the failure`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.stopBot(1L) }
        }

        assertEquals(STOP_UNPERSISTED_MESSAGE, thrown.message)
        coVerify(exactly = 1) { mockEngine.stop() }
        assertNull(engines()[1L], "저장이 실패해도 정지한 엔진은 맵에서 빠져야 한다")
        assertTrue(1L in unpersistedStops())
    }

    @Test
    fun `stop without an engine clears a running flag left in the database`() = runTest {
        // 복원이 실패해 엔진 없이 DB 만 running=true 인 경우 — not_running 만 답하면 다음 재시작 때 복원된다.
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.stopBot(1L)

        assertEquals("not_running", result["status"])
        assertFalse(saved.captured.running)
    }

    @Test
    fun `stop writes nothing when the database already says stopped`() = runTest {
        // 저장이 커밋된 뒤 응답만 유실돼 다시 부른 경우도 여기로 온다 — 멱등이어야 한다.
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns
            Mono.just(runningState(1L).copy(running = false))

        val result = manager.stopBot(1L)

        assertEquals("not_running", result["status"])
        verify(exactly = 0) { botStateRepository.save(any()) }
    }

    @Test
    fun `a stop called again after a failed save persists and leaves the unsaved set`() = runTest {
        unpersistedStops().add(1L)
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } answers { Mono.just(firstArg()) }

        manager.stopBot(1L)

        assertFalse(1L in unpersistedStops())
    }

    @Test
    fun `restore skips a user whose stop could not be saved even though the row still says running`() = runTest {
        unpersistedStops().add(1L)
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        manager.restoreAllRunningBots()

        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `restore rereads the row under the lock and skips a user stopped since the query`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns
            Mono.just(runningState(1L).copy(running = false))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        manager.restoreAllRunningBots()

        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `shutdown saves once more a stop that could not be saved`() {
        unpersistedStops().add(1L)
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        manager.stop() // SmartLifecycle.stop

        assertFalse(saved.captured.running)
        assertFalse(1L in unpersistedStops())
    }

    @Test
    fun `start that cannot persist does not start the engine`() = runTest {
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))
        every { mockEngine.getActiveStrategyName() } returns "combined"

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.startBot(1L, listOf("KRW-BTC"), "combined") }
        }

        assertEquals(START_UNPERSISTED_MESSAGE, thrown.message)
        coVerify(exactly = 0) { mockEngine.start(any(), any()) }
        assertNull(engines()[1L], "기동하지 않은 엔진이 맵에 남으면 안 된다")
    }

    @Test
    fun `a start whose row already matches leaves the unsaved-stop set so shutdown does not undo it`() = runTest {
        // 정지 저장이 실패한 뒤 같은 전략·목록으로 다시 시작하면 행이 이미 같아 쓰기가 생략된다 — 그래도 집합에서 빠져야
        // 종료 저장이 running=false 로 덮어 다음 재시작 때 봇이 복원되지 않는 일이 없다.
        unpersistedStops().add(1L)
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { mockEngine.getActiveStrategyName() } returns "combined"

        manager.startBot(1L, listOf("KRW-BTC"), "combined")

        verify(exactly = 0) { botStateRepository.save(any()) }
        assertFalse(1L in unpersistedStops())
        coVerify(exactly = 1) { mockEngine.start(listOf("KRW-BTC"), any()) }
    }

    @Test
    fun `start with an unknown strategy is refused before creating an engine`() = runTest {
        val result = manager.startBot(1L, listOf("KRW-BTC"), "x not found")

        assertEquals(UserTradingManager.UNKNOWN_STRATEGY_CODE, result["code"])
        assertEquals("Unknown strategy: x not found", result["error"])
        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `start on a running engine with an unknown strategy is refused without touching the engine`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")

        val result = manager.startBot(1L, null, "nope")

        assertEquals(UserTradingManager.UNKNOWN_STRATEGY_CODE, result["code"])
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
    }

    @Test
    fun `strategy change on a running engine that cannot persist keeps the old strategy`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.startBot(1L, null, "golden_cross") }
        }

        assertEquals(STRATEGY_UNPERSISTED_MESSAGE, thrown.message)
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
    }

    @Test
    fun `start on a running engine writes nothing when the row already matches`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))

        val result = manager.startBot(1L, null, null)

        assertEquals("already_running", result["status"])
        verify(exactly = 0) { botStateRepository.save(any()) }
    }

    @Test
    fun `strategy change that cannot persist changes neither the cache nor the engine`() = runTest {
        engines()[1L] = mockEngine
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.setStrategy(1L, "golden_cross") }
        }

        assertEquals(STRATEGY_UNPERSISTED_MESSAGE, thrown.message)
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
        assertNull(userStrategies()[1L])
    }

    @Test
    fun `halt clear that cannot persist is reported as a failure`() = runTest {
        engines()[1L] = mockEngine
        coEvery { mockEngine.clearHalt("KRW-BTC") } throws RuntimeException("db down")

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.clearHalt(1L, "KRW-BTC") }
        }

        assertEquals(HALT_CLEAR_UNPERSISTED_MESSAGE, thrown.message)
    }

    @Test
    fun `stop with a running engine lowers the running flag and forgets the engine`() = runTest {
        engines()[1L] = mockEngine
        userStrategies()[1L] = "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val result = manager.stopBot(1L)

        assertEquals("stopped", result["status"])
        assertFalse(saved.captured.running)
        assertNull(engines()[1L])
        assertNull(userStrategies()[1L])
        assertFalse(1L in unpersistedStops())
    }

    @Test
    fun `a stop request cancelled while the engine is stopping still leaves the stop known`() = runTest {
        // 클라이언트가 끊겨 요청이 취소돼도 엔진만 멈추고 표시가 빠지면, 저장도 종료 저장도 없이 재시작 때 이 봇이 다시 뜬다.
        engines()[1L] = mockEngine
        coEvery { mockEngine.stop() } coAnswers { delay(1_000) }
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }

        val request = launch { manager.stopBot(1L) }
        advanceTimeBy(500)
        request.cancelAndJoin()

        assertNull(engines()[1L], "취소돼도 정지한 엔진은 맵에서 빠져야 한다")
        assertTrue(
            1L in unpersistedStops() || (saved.isCaptured && !saved.captured.running),
            "정지가 저장되지도, 미저장으로 표시되지도 않았다",
        )
    }

    @Test
    fun `a final stop save that fails keeps the user marked`() = runTest {
        unpersistedStops().add(1L)
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))

        manager.saveUnpersistedStops()

        assertTrue(1L in unpersistedStops())
    }

    @Test
    fun `the final stop save does not overwrite a start that finished while it waited for the lock`() = runTest {
        // 종료 저장은 집합을 스냅샷한 뒤 사용자 lock 을 기다린다 — 그 사이 시작이 저장을 마치면 도는 봇의 행을 내리면 안 된다.
        unpersistedStops().add(1L)
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { mockEngine.getActiveStrategyName() } returns "combined"
        coEvery { tradingStateService.loadStates(1L) } coAnswers {
            delay(1_000)
            emptyMap()
        }

        val start = launch { manager.startBot(1L, listOf("KRW-BTC"), "combined") }
        advanceTimeBy(500) // 시작이 lock 을 쥔 채 상태 로드를 기다리는 중
        val finalSave = launch { manager.saveUnpersistedStops() }
        start.join()
        finalSave.join()

        verify(exactly = 0) { botStateRepository.save(any()) }
        assertFalse(1L in unpersistedStops())
    }

    @Test
    fun `start on a running engine whose stored state differs reports a running-but-unsaved failure`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns
            Mono.just(runningState(1L).copy(running = false))
        every { botStateRepository.save(any()) } returns Mono.error(RuntimeException("db down"))

        val thrown = assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.startBot(1L, null, null) }
        }

        assertEquals(RUNNING_UNPERSISTED_MESSAGE, thrown.message)
    }

    @Test
    fun `strategy change to the stored strategy writes nothing`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))

        assertTrue(manager.setStrategy(1L, "combined"))

        verify(exactly = 0) { botStateRepository.save(any()) }
    }

    @Test
    fun `strategy change whose state lookup fails is a persistence failure`() = runTest {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.error(RuntimeException("db down"))

        assertThrows(BotControlPersistFailedException::class.java) {
            runBlocking { manager.setStrategy(1L, "golden_cross") }
        }
    }

    @Test
    fun `a failed restore does not cache a strategy name that is no longer registered`() = runTest {
        // 캐시된 죽은 이름은 상태 API 로 나가 화면이 그 이름으로 시작을 요청한다 — 이제는 400 이다.
        val stale = BotStateEntity(userId = 7L, running = true, strategy = "removed_strategy", tickers = "KRW-BTC")
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(stale)
        every { botStateRepository.findByUserIdAndExchange(7L, "UPBIT") } returns Mono.just(stale)
        every { userRepository.findById(7L) } returns Mono.just(user(7L))
        coEvery { tradingStateService.loadStates(7L) } throws RuntimeException("db down")

        manager.restoreAllRunningBots()

        assertNull(userStrategies()[7L])
    }

    // --- 멈춘 엔진의 미기록 매도 pending (#244) ---
    // 매도는 선기록이 실패해도 보내고, 재기록은 다음 tick 몫이다. 엔진을 버리거나 그 states 를 DB 값으로 덮기 전에 한 번 더
    // 남기지 않으면 그 주문은 메모리와 함께 사라져, 체결돼도 거래 기록이 남지 않는다.

    private fun unrecordedSell() =
        TradingState("KRW-BTC", position = true, pendingSellIdentifier = "ctb-sell-1", pendingPersistFailed = true)

    private inline fun errorsDuring(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(UserTradingManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
    }

    private fun runningEngineToReload() {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
    }

    private fun stoppableRow(): CapturingSlot<BotStateEntity> {
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        val saved = slot<BotStateEntity>()
        every { botStateRepository.save(capture(saved)) } answers { Mono.just(firstArg()) }
        return saved
    }

    @Test
    fun `reload records the old engine's pending before reading the database`() = runTest {
        runningEngineToReload()

        manager.reloadUserRuntime(1L)

        coVerify(ordering = Ordering.ORDERED) {
            mockEngine.stop()
            mockEngine.flushUnpersistedPending()
            tradingStateService.loadStates(1L)
        }
    }

    @Test
    fun `reload keeps the old engine running when a sell stays unrecorded`() = runTest {
        runningEngineToReload()
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())

        val thrown = assertThrows(RuntimeReloadFailedException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        // 옛 엔진은 그 매도를 메모리에 들고 다음 tick 에 다시 기록한다 — 새 엔진은 DB 에 없는 그 주문을 모른다.
        assertTrue(thrown.engineRestored)
        assertTrue(thrown.cause?.message.orEmpty().contains("KRW-BTC"), "원인에 남기지 못한 티커가 없다: ${thrown.cause?.message}")
        verify(exactly = 1) { mockEngine.resume() }
        coVerify(exactly = 0) { tradingStateService.loadStates(any()) }
        verify(exactly = 0) { manager.createEngine(any()) }
        assertSame(mockEngine, engines()[1L])
    }

    @Test
    fun `a stuck recording during reload falls back to the old engine within the bound`() = runTest {
        runningEngineToReload()
        coEvery { mockEngine.flushUnpersistedPending() } coAnswers { delay(60_000) }
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())

        val thrown = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()

        // 취소로 번지면 컨트롤러가 RuntimeReloadFailedException 을 받지 못해 503 안내 문구가 나가지 않는다.
        assertTrue(thrown is RuntimeReloadFailedException, "옛 엔진 복귀로 알려야 한다: $thrown")
        assertTrue(testScheduler.currentTime < 60_000, "걸린 기록이 사용자 lock 을 끝까지 쥐었다")
        verify(exactly = 1) { mockEngine.resume() }
    }

    @Test
    fun `a reload cancelled while recording pending still brings the old engine back`() = runTest {
        runningEngineToReload()
        coEvery { mockEngine.flushUnpersistedPending() } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        verify(exactly = 1) { mockEngine.resume() }
        coVerify(exactly = 0) { tradingStateService.loadStates(any()) }
    }

    @Test
    fun `stop records pending after the stop is settled and reports a sell it could not record`() = runTest {
        engines()[1L] = mockEngine
        val saved = stoppableRow()
        val settledAtRecording = mutableListOf<Boolean>()
        coEvery { mockEngine.flushUnpersistedPending() } coAnswers {
            settledAtRecording += engines()[1L] == null && 1L in unpersistedStops()
        }
        every { mockEngine.unpersistedSells() } returns
            listOf(TradingState("KRW-BTC", position = true, pendingSellUuid = "u-77", pendingPersistFailed = true))

        lateinit var result: Map<String, Any>
        val errors = errorsDuring { result = manager.stopBot(1L) }

        // 정지·표시가 기록 결과에 달리면 안 된다(#228) — 먼저 확정하고 기록한다.
        assertEquals(listOf(true), settledAtRecording, "기록 전에 엔진 제거·정지 표시가 끝나 있어야 한다")
        assertEquals("stopped", result["status"])
        assertFalse(saved.captured.running)
        assertTrue(errors.any { it.contains("KRW-BTC") && it.contains("u-77") }, "남기지 못한 매도를 알려야 한다: $errors")
    }

    @Test
    fun `a stop request cancelled while recording pending still finishes the recording`() = runTest {
        engines()[1L] = mockEngine
        stoppableRow()
        val recorded = AtomicBoolean(false)
        coEvery { mockEngine.flushUnpersistedPending() } coAnswers { delay(1_000); recorded.set(true) }

        val request = launch { manager.stopBot(1L) }
        advanceTimeBy(500)
        request.cancelAndJoin()

        // 엔진은 이미 맵에서 빠졌다 — 여기서 끊기면 그 매도는 메모리와 함께 사라진다.
        assertTrue(recorded.get())
    }

    @Test
    fun `a stop whose pending recording fails unexpectedly still stops and saves`() = runTest {
        engines()[1L] = mockEngine
        val saved = stoppableRow()
        coEvery { mockEngine.flushUnpersistedPending() } throws IllegalStateException("boom")

        lateinit var result: Map<String, Any>
        val errors = errorsDuring { result = manager.stopBot(1L) }

        assertEquals("stopped", result["status"])
        assertFalse(saved.captured.running)
        assertNull(engines()[1L])
        assertTrue(errors.any { it.contains("다시 남기지 못함") }, "기록 실패를 알려야 한다: $errors")
    }

    @Test
    fun `a stuck recording does not keep a stop holding the user lock`() = runTest {
        // 정지의 기록은 요청 취소를 받지 않는다 — 상한이 없으면 걸린 DB 호출이 이 사용자의 lock 을 무기한 쥔다(#228).
        engines()[1L] = mockEngine
        val saved = stoppableRow()
        coEvery { mockEngine.flushUnpersistedPending() } coAnswers { delay(60_000) }

        val result = manager.stopBot(1L)

        assertEquals("stopped", result["status"])
        assertFalse(saved.captured.running)
        assertTrue(testScheduler.currentTime < 60_000, "걸린 기록이 정지를 끝까지 붙잡았다")
    }

    @Test
    fun `start records a stopped engine's pending before the database state replaces it`() = runTest {
        // 맵에 남은 정지 엔진(reload 취소 뒤 복귀 실패 등)을 재사용하면 start 가 그 states 를 DB 값으로 덮는다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.empty()
        every { botStateRepository.save(any()) } answers { Mono.just(firstArg()) }

        val errors = errorsDuring { manager.startBot(1L, listOf("KRW-BTC"), null) }

        coVerify(ordering = Ordering.ORDERED) {
            mockEngine.flushUnpersistedPending()
            tradingStateService.loadStates(1L)
        }
        assertTrue(errors.any { it.contains("KRW-BTC") && it.contains("ctb-sell-1") }, "남기지 못한 매도를 알려야 한다: $errors")
    }

    @Test
    fun `restore records a stopped engine's pending before the database state replaces it`() = runTest {
        // 복원도 맵에 남은 정지 엔진을 재사용한다 — 회차와 무관하다(복원이 이 사용자에 닿기 전에 사용자가 시작한 엔진이, 취소된
        // reload 뒤 옛 엔진으로 돌아오지 못해 정지된 채 남은 경우 등).
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        val errors = errorsDuring { manager.restoreAllRunningBots() }

        coVerify(ordering = Ordering.ORDERED) {
            mockEngine.flushUnpersistedPending()
            tradingStateService.loadStates(1L)
        }
        assertTrue(errors.any { it.contains("KRW-BTC") && it.contains("ctb-sell-1") }, "남기지 못한 매도를 알려야 한다: $errors")
    }

    @Test
    fun `restore reports a stopped engine's unrecorded sell only on the attempt that read the database state`() = runTest {
        // 로드가 실패한 시도는 아무것도 덮지 않았다 — 엔진은 맵에 남아 다음 시도가 다시 기록한다. 그때 알리면 재시도마다 오경보다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down") andThen emptyMap()

        val errors = errorsDuring { manager.restoreAllRunningBots() }

        assertEquals(1, errors.count { it.contains("ctb-sell-1") }, "로드가 성공한 시도에서만 알려야 한다: $errors")
        coVerify(exactly = 2) { mockEngine.flushUnpersistedPending() }
        coVerify(exactly = 1) { mockEngine.start(any(), any()) }
        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `start that cannot read the database state keeps the stopped engine without reporting a dropped sell`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down")

        val errors = errorsDuring {
            val thrown = runCatching { manager.startBot(1L, listOf("KRW-BTC"), null) }.exceptionOrNull()
            assertEquals("db down", thrown?.message)
        }

        assertTrue(errors.none { it.contains("ctb-sell-1") }, "덮은 것이 없는데 버린다고 알렸다: $errors")
        coVerify(exactly = 1) { mockEngine.flushUnpersistedPending() }
        assertSame(mockEngine, engines()[1L], "기록한 엔진을 남겨야 다음 시작이 다시 기록한다")
    }

    @Test
    fun `reload that can neither hand over nor bring back the old engine reports the sell it drops`() = runTest {
        // 복귀까지 실패하면 옛 엔진을 맵에서 뺀다 — 그 매도를 들고 있던 유일한 사본이 사라지므로 사람이 맞추도록 알린다.
        runningEngineToReload()
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())
        every { mockEngine.resume() } throws RuntimeException("engine start failed")

        val errors = errorsDuring {
            val thrown = runCatching { manager.reloadUserRuntime(1L) }.exceptionOrNull()
            assertTrue(thrown is RuntimeReloadFailedException && !thrown.engineRestored, "복귀 실패로 알려야 한다: $thrown")
        }

        assertNull(engines()[1L])
        // 선기록이 성공한 매도는 다음 기동이 스스로 확정한다 — 확인 없이 수동으로 맞추면 이중 계상된다.
        assertTrue(
            errors.any { it.contains("ctb-sell-1") && it.contains("먼저 확인") && it.contains("Upbit 주문 내역") },
            "버리는 매도를 조치 문구와 함께 알려야 한다: $errors",
        )
    }

    @Test
    fun `reload replacing a stopped engine records its pending and reports a sell it could not record`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns false
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { mockEngine.unpersistedSells() } returns listOf(unrecordedSell())

        val errors = errorsDuring { manager.reloadUserRuntime(1L) }

        coVerify(exactly = 1) { mockEngine.flushUnpersistedPending() }
        assertTrue(errors.any { it.contains("KRW-BTC") && it.contains("ctb-sell-1") }, "남기지 못한 매도를 알려야 한다: $errors")
        // 알림은 교체를 막지 않는다 — 이 엔진은 돌고 있지 않아 새 설정의 엔진으로 바꾸는 것뿐이다.
        verify(exactly = 1) { manager.createEngine(any()) }
    }

    @Test
    fun `shutdown records each stopped engine's pending and reports a sell it could not record`() {
        val engine = mockk<TradingEngine>(relaxed = true)
        engines()[1L] = engine
        every { engine.unpersistedSells() } returns listOf(unrecordedSell())

        val errors = errorsDuring { manager.stop() } // SmartLifecycle.stop

        coVerify(ordering = Ordering.ORDERED) {
            engine.stop()
            engine.flushUnpersistedPending()
        }
        assertTrue(errors.any { it.contains("KRW-BTC") && it.contains("ctb-sell-1") }, "남기지 못한 매도를 알려야 한다: $errors")
    }
}
