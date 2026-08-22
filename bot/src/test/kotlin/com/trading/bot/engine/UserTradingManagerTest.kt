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
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
                emptyList(), TradingProperties(autoStart = true), upbitWebClient,
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
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
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
        coVerify(exactly = 1) { mockEngine.start(listOf("KRW-BTC"), emptyMap()) } // 옛 엔진 재기동
    }

    @Test
    fun `reload rethrows only after the old engine is back up`() = runTest {
        // 순서가 뒤바뀌면 stop 된 엔진만 남아 PR #50 이 막으려던 결함이 되살아난다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down")

        runCatching { manager.reloadUserRuntime(1L) }

        coVerify(ordering = Ordering.ORDERED) {
            mockEngine.stop()
            mockEngine.start(listOf("KRW-BTC"), emptyMap())
        }
    }

    @Test
    fun `reload failure carries the original cause`() = runTest {
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
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
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        val loadFailure = RuntimeException("db down")
        coEvery { tradingStateService.loadStates(1L) } throws loadFailure
        val restoreFailure = RuntimeException("engine start failed")
        coEvery { mockEngine.start(any(), any()) } throws restoreFailure

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
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.reloadUserRuntime(1L) }
        }

        coVerify(exactly = 1) { mockEngine.start(listOf("KRW-BTC"), emptyMap()) } // 복구는 수행
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
        every { userRepository.findById(1L) } returns Mono.just(user(1L))

        manager.restoreAllRunningBots()

        // 첫 조회 실패 후 backoff 재시도로 복원 성공(가상시간이 delay 를 즉시 진행).
        verify(exactly = 1) { manager.createEngine(any()) }
    }

    @Test
    fun `restore logs error after exhausting retries`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(1L))
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

}
