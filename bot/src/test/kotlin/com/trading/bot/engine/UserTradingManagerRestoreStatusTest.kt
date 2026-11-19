package com.trading.bot.engine

import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** 기동 복원의 결과를 배포 판정(health)이 읽는다. 복원 로직은 그대로 두고 결과만 기록한다. */
class UserTradingManagerRestoreStatusTest {

    private val userRepository: UserRepository = mockk()
    private val botStateRepository: BotStateRepository = mockk()
    private val mockEngine: TradingEngine = mockk(relaxed = true)
    private val strategy: TradingStrategy = mockk { every { name } returns "combined" }

    private fun manager(autoStart: Boolean = true): UserTradingManager {
        val manager = spyk(
            UserTradingManager(
                userRepository, botStateRepository, mockk(relaxed = true), mockk<DiscordNotifier>(relaxed = true),
                strategy, TradingProperties(autoStart = autoStart), mockk<WebClient>(relaxed = true),
                mockk<UserSecretsService>(relaxed = true), mockk<MarketDataStore>(relaxed = true),
                mockk<TradingStateService>(relaxed = true),
            ),
        )
        every { manager.createEngine(any()) } returns mockEngine
        return manager
    }

    private fun engines(manager: UserTradingManager): ConcurrentHashMap<Long, TradingEngine> {
        val f = UserTradingManager::class.java.getDeclaredField("engines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(manager) as ConcurrentHashMap<Long, TradingEngine>
    }

    private fun runningState(userId: Long) =
        BotStateEntity(userId = userId, running = true, strategy = "combined", tickers = "KRW-BTC")

    private fun user(userId: Long) =
        UserEntity(id = userId, username = "u$userId", password = "p", upbitAccessKey = "ak", upbitSecretKey = "sk")

    private fun runningBot(userId: Long) {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.just(runningState(userId))
        every { botStateRepository.findByUserIdAndExchange(userId, "UPBIT") } returns Mono.just(runningState(userId))
        every { botStateRepository.save(any()) } answers { Mono.just(firstArg()) }
    }

    @Test
    fun `복원 전에는 NOT_STARTED 이고 실행 중이던 봇을 모두 복원하면 DONE 이다`() = runTest {
        runningBot(1L)
        every { userRepository.findById(1L) } returns Mono.just(user(1L))
        val manager = manager()
        val before = manager.restoreStatus

        manager.restoreAllRunningBots()

        assertEquals(RestoreStatus.NOT_STARTED, before)
        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `실행 중이던 봇이 없으면 DONE 이다`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.empty()
        val manager = manager()

        manager.restoreAllRunningBots()

        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `복원하는 동안은 IN_PROGRESS 이고 종료가 아닌 취소로 끊기면 FAILED 다`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.never()
        val manager = manager()

        val restore = launch { manager.restoreAllRunningBots() }
        runCurrent()
        val during = manager.restoreStatus
        restore.cancelAndJoin()

        assertEquals(RestoreStatus.IN_PROGRESS, during)
        assertEquals(RestoreStatus.FAILED, manager.restoreStatus)
    }

    @Test
    fun `예기치 않은 오류로 복원이 끊기면 FAILED 다`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } throws NotImplementedError("boom")
        val manager = manager()

        val thrown = runCatching { manager.restoreAllRunningBots() }.exceptionOrNull()

        assertTrue(thrown is NotImplementedError, "오류는 그대로 다시 던진다: $thrown")
        assertEquals(RestoreStatus.FAILED, manager.restoreStatus)
    }

    @Test
    fun `마지막 시도 뒤 손으로 시작된 봇은 복원 실패로 세지 않는다`() = runTest {
        runningBot(1L)
        val manager = manager()
        val running = AtomicBoolean(false)
        every { mockEngine.isRunning() } answers { running.get() }
        var calls = 0
        every { userRepository.findById(1L) } answers {
            // 마지막 시도가 실패한 직후 사용자가 시작해 엔진이 돈다.
            if (++calls == RESTORE_ATTEMPTS) {
                engines(manager)[1L] = mockEngine
                running.set(true)
            }
            Mono.error(RuntimeException("user db down"))
        }

        manager.restoreAllRunningBots()

        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `복원하지 못한 사용자가 이미 도는 봇을 다시 시작하면 DONE 이다`() = runTest {
        runningBot(1L)
        every { userRepository.findById(1L) } returns Mono.error(RuntimeException("user db down"))
        val manager = manager()
        manager.restoreAllRunningBots()
        val afterRestore = manager.restoreStatus
        every { mockEngine.isRunning() } returns true
        every { mockEngine.getUserTickers() } returns listOf("KRW-BTC")
        engines(manager)[1L] = mockEngine

        manager.startBot(1L, null)

        assertEquals(RestoreStatus.FAILED, afterRestore)
        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `자동 시작이 꺼져 있으면 DISABLED 다`() {
        val manager = manager(autoStart = false)

        manager.restoreOnStartup()

        assertEquals(RestoreStatus.DISABLED, manager.restoreStatus)
    }

    @Test
    fun `재시도를 다 쓰고 복원하지 못한 사용자가 있으면 FAILED 이고 그 사용자를 멈추면 DONE 이다`() = runTest {
        runningBot(1L)
        every { userRepository.findById(1L) } returns Mono.error(RuntimeException("user db down"))
        val manager = manager()

        manager.restoreAllRunningBots()
        val afterRestore = manager.restoreStatus
        manager.stopBot(1L)

        assertEquals(RestoreStatus.FAILED, afterRestore)
        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `재시도를 다 쓰고 복원하지 못한 사용자를 손으로 시작하면 DONE 이다`() = runTest {
        runningBot(1L)
        val failures = List(RESTORE_ATTEMPTS) { Mono.error<UserEntity>(RuntimeException("user db down")) }
        every { userRepository.findById(1L) } returnsMany failures + Mono.just(user(1L))
        val manager = manager()

        manager.restoreAllRunningBots()
        val afterRestore = manager.restoreStatus
        manager.startBot(1L, listOf("KRW-BTC"))

        assertEquals(RestoreStatus.FAILED, afterRestore)
        assertEquals(RestoreStatus.DONE, manager.restoreStatus)
    }

    @Test
    fun `bot state 조회가 끝까지 실패하면 재시작 전까지 FAILED 다`() = runTest {
        every { botStateRepository.findByRunningTrueAndExchange("UPBIT") } returns Flux.error(RuntimeException("db down"))
        every { botStateRepository.findByUserIdAndExchange(1L, "UPBIT") } returns Mono.just(runningState(1L))
        every { botStateRepository.save(any()) } answers { Mono.just(firstArg()) }
        val manager = manager()

        manager.restoreAllRunningBots()
        manager.stopBot(1L)

        assertEquals(RestoreStatus.FAILED, manager.restoreStatus)
    }

    private companion object {
        /** `UserTradingManager.RESTORE_MAX_ATTEMPTS` 와 같다. */
        const val RESTORE_ATTEMPTS = 5
    }
}
