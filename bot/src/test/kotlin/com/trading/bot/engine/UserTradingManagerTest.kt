package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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
        every { botStateRepository.findByRunningTrue() } returns Flux.just(runningState(1L))
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        every { mockEngine.isRunning() } returns false
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down") andThen emptyMap()

        manager.restoreAllRunningBots()

        coVerify(exactly = 1) { mockEngine.start(any(), any()) } // 재시도에서 실제로 기동돼야 한다
    }

    @Test
    fun `reload does not register a replacement engine when durable state load fails`() = runTest {
        // 교체 엔진을 등록해 두고 기동에 실패하면 봇은 멈춘 채 "복원됨" 으로 보인다.
        engines()[1L] = mockEngine
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getActiveTickers() } returns listOf("KRW-BTC")
        every { mockEngine.getActiveStrategyName() } returns "combined"
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        coEvery { tradingStateService.loadStates(1L) } throws RuntimeException("db down")

        assertThrows(RuntimeException::class.java) { runBlocking { manager.reloadUserRuntime(1L) } }

        assertSame(mockEngine, engines()[1L], "로드 실패 시 교체 엔진이 등록되면 안 된다")
        verify(exactly = 0) { manager.createEngine(any()) }
    }

    @Test
    fun `restore skips a user whose engine is already running`() = runTest {
        engines()[1L] = mockEngine // 사용자가 이미 start 로 개입한 상태 시뮬
        every { mockEngine.isRunning() } returns true
        every { botStateRepository.findByRunningTrue() } returns Flux.just(runningState(1L))

        manager.restoreAllRunningBots()

        // lock 획득 후 engines 재확인으로 skip — 새 엔진 생성도, 기존 엔진 재기동(setStrategy/start)도 없다.
        // (구 computeIfAbsent 는 createEngine 만 skip 하고 실행 중 엔진에 start 를 다시 걸어 유령 엔진을 만들었다.)
        verify(exactly = 0) { manager.createEngine(any()) }
        verify(exactly = 0) { mockEngine.setStrategy(any()) }
        verify(exactly = 0) { mockEngine.start(any()) }
    }

    @Test
    fun `restore retries transient DB failure then succeeds`() = runTest {
        every { botStateRepository.findByRunningTrue() } returnsMany listOf(
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
        every { botStateRepository.findByRunningTrue() } returns Flux.just(runningState(1L))
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
        every { botStateRepository.findByRunningTrue() } returns Flux.just(runningState(1L))
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
        every { botStateRepository.findByRunningTrue() } returns Flux.error(RuntimeException("db down"))

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
}
