package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.config.UpbitProperties
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import jakarta.annotation.PreDestroy
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
@DependsOn("discordErrorLogAppender") // @PreDestroy 소멸 순서 고정: appender 가 먼저 생성→나중 detach, 이 매니저가 먼저 stop → 종료 중 에러도 Discord 도달.
class UserTradingManager(
    private val userRepository: UserRepository,
    private val botStateRepository: BotStateRepository,
    private val tradeExecutionService: TradeExecutionService,
    private val discordNotifier: DiscordNotifier,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val upbitWebClient: WebClient,
    private val userSecretsService: UserSecretsService,
    private val upbitWebSocketClient: UpbitWebSocketClient,
    private val marketDataStore: MarketDataStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, TradingEngine>()
    private val userStrategies = ConcurrentHashMap<Long, String>()
    // userId 별 Mutex 로 start/stop/reload/setStrategy 의 engines mutate 를 직렬화.
    // CAS (remove(k,v) / replace(k,old,new)) 만으로는 computeIfAbsent 직후 start() 호출 전에
    // stop 이 끼어드는 race window 를 닫지 못함.
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun lockFor(userId: Long): Mutex = userLocks.computeIfAbsent(userId) { Mutex() }

    // DiscordErrorLogAppender(@Order HIGHEST)가 먼저 attach 된 뒤 실행되도록 낮은 우선순위. 단 restore 는 비동기라
    // 리스너 순서만으로 alert 도달을 보장하지 못한다 — 실질 보장은 restoreAllRunningBots 의 유한 backoff 재시도
    // (첫 실패가 attach 이후로 밀림)다. @PostConstruct 는 attach(ApplicationReadyEvent)보다 이르러 초기 에러가 미도달이었다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun restoreOnStartup() {
        if (!tradingProperties.autoStart) {
            log.info("Auto-start disabled. Skipping bot restoration.")
            return
        }
        scope.launch { restoreAllRunningBots() }
    }

    /**
     * graceful shutdown: SIGTERM(@PreDestroy) 시 모든 엔진을 stop(cancelAndJoin)해 진행 중이던 tick 의 주문
     * 후처리(NonCancellable)가 끝난 뒤 종료한다. 병렬 stop 으로 다중 유저 합산 대기를 timeout-per-shutdown-phase(30s)
     * 예산 안에 들인다. @DependsOn 으로 DiscordErrorLogAppender detach 는 이 stop 이후로 밀려 종료 중 에러도 도달한다.
     */
    @PreDestroy
    fun shutdownAll() {
        if (engines.isEmpty()) return
        log.info("Graceful shutdown: stopping {} running engine(s)", engines.size)
        runBlocking {
            engines.values.toList().map { engine ->
                async {
                    try {
                        engine.stop()
                    } catch (e: Exception) {
                        log.error("Failed to stop engine during shutdown: {}", e.message, e)
                    }
                }
            }.awaitAll()
        }
    }

    /** running bot 을 복원하되, 일시적 실패(DB/API)는 유한 backoff 로 재시도하고 최종 실패만 ERROR alert(→Discord). */
    internal suspend fun restoreAllRunningBots() {
        var pendingUserIds: List<Long> = emptyList()
        for (attempt in 1..RESTORE_MAX_ATTEMPTS) {
            val states = try {
                botStateRepository.findByRunningTrue().collectList().awaitSingle()
            } catch (e: Exception) {
                log.warn("restore: bot state 조회 실패 (attempt {}/{}): {}", attempt, RESTORE_MAX_ATTEMPTS, e.message)
                delay(restoreBackoffMs(attempt))
                continue
            }
            if (attempt == 1) log.info("Restoring {} running bot(s) from DB", states.size)
            val failed = mutableListOf<Long>()
            for (state in states) {
                if (!restoreOne(state)) failed.add(state.userId)
            }
            pendingUserIds = failed
            if (pendingUserIds.isEmpty()) return
            if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt))
        }
        if (pendingUserIds.isNotEmpty()) {
            log.error("봇 미복원: {}개 유저 복원 실패 (재시도 {}회 소진) — userIds={}", pendingUserIds.size, RESTORE_MAX_ATTEMPTS, pendingUserIds)
        }
    }

    /**
     * 한 유저 복원. per-user lock 으로 start/stop 과 직렬화하고, lock 획득 후 engines 를 재확인해 사용자가 이미
     * 개입(start/stop)했으면 skip — restoreOnStartup 만 lockFor 를 우회하던 유령 엔진 경합을 차단한다.
     * 반환: true=복원 완료 또는 재시도 무의미(유저/키 없음·이미 개입), false=일시적 실패(재시도 대상).
     */
    private suspend fun restoreOne(state: BotStateEntity): Boolean = lockFor(state.userId).withLock {
        if (engines.containsKey(state.userId)) return@withLock true
        try {
            val user = userRepository.findById(state.userId).awaitSingleOrNull() ?: return@withLock true
            if (user.upbitAccessKey.isNullOrBlank()) return@withLock true
            val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            userStrategies[state.userId] = state.strategy
            val engine = engines.computeIfAbsent(state.userId) {
                createEngine(userSecretsService.decryptUserSecrets(user))
            }
            engine.setStrategy(state.strategy)
            engine.start(tickers)
            log.info("Restored bot for user {}: strategy={}, tickers={}", state.userId, state.strategy, tickers)
            true
        } catch (e: Exception) {
            log.warn("restore: user {} 복원 실패 — 재시도 대상: {}", state.userId, e.message)
            false
        }
    }

    private fun restoreBackoffMs(attempt: Int): Long = minOf(1000L shl (attempt - 1), 30_000L)

    fun getEngine(userId: Long): TradingEngine? = engines[userId]

    suspend fun startBot(userId: Long, tickers: List<String>?, strategyName: String?): Map<String, Any> = lockFor(userId).withLock {
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")

        if (user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            return@withLock mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val engine = engines.computeIfAbsent(userId) { createEngine(decryptedUser) }

        val strategy = strategyName ?: userStrategies[userId]
        if (strategy != null) engine.setStrategy(strategy)

        val tickerList = tickers ?: tradingProperties.tickerList()
        engine.start(tickerList)

        saveState(userId, true, engine.getActiveStrategyName(), tickerList)
        mapOf("status" to "started", "strategy" to engine.getActiveStrategyName())
    }

    suspend fun stopBot(userId: Long): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId] ?: return@withLock mapOf("status" to "not_running")
        engine.stop()
        saveState(userId, false, engine.getActiveStrategyName(), emptyList())
        engines.remove(userId)
        userStrategies.remove(userId)
        mapOf("status" to "stopped")
    }

    fun getStatus(userId: Long): Map<String, Any> {
        val engine = engines[userId]
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            "strategy" to (engine?.getActiveStrategyName() ?: userStrategies[userId] ?: tradingProperties.strategy),
            // engine.getActiveTickers() is set synchronously by start();
            // states keys only populate once the background loop initializes
            // them, so reading from states here would briefly return [] right
            // after /api/bot/start.
            "tickers" to (engine?.getActiveTickers() ?: emptyList<String>()),
            "positions" to (engine?.getStates()?.map { (ticker, state) ->
                mapOf(
                    "ticker" to ticker,
                    "position" to state.position,
                    "avg_buy_price" to state.avgBuyPrice,
                    "hold_volume" to state.holdVolume,
                    "bought_today" to state.boughtToday,
                )
            } ?: emptyList<Map<String, Any>>()),
        )
    }

    suspend fun setStrategy(userId: Long, strategyName: String): Boolean = lockFor(userId).withLock {
        val valid = strategies.any { it.name == strategyName }
        if (!valid) return@withLock false
        userStrategies[userId] = strategyName
        engines[userId]?.setStrategy(strategyName)

        val existing = botStateRepository.findByUserId(userId).awaitSingleOrNull()
        if (existing != null) {
            botStateRepository.save(existing.copy(strategy = strategyName, updatedAt = LocalDateTime.now())).awaitSingle()
        }
        true
    }

    fun createUpbitClient(user: UserEntity): UpbitClient {
        val props = UpbitProperties(
            accessKey = user.upbitAccessKey ?: "",
            secretKey = user.upbitSecretKey ?: "",
        )
        val authProvider = UpbitAuthProvider(props)
        return UpbitClientImpl(upbitWebClient, authProvider)
    }

    suspend fun reloadUserRuntime(userId: Long) = lockFor(userId).withLock {
        val existing = engines[userId] ?: return@withLock
        val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock
        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val wasRunning = existing.isRunning()
        val tickers = existing.getActiveTickers()
        val strategy = existing.getActiveStrategyName()
        existing.stop()
        val replacement = createEngine(decryptedUser)
        replacement.setStrategy(strategy)
        engines[userId] = replacement
        userStrategies[userId] = strategy
        if (wasRunning) {
            replacement.start(tickers.ifEmpty { tradingProperties.tickerList() })
        }
    }

    internal fun createEngine(user: UserEntity): TradingEngine {
        val client = createUpbitClient(user)
        val positionManager = PositionManager(client, tradingProperties)
        val dailyResetManager = DailyResetManager(tradingProperties)

        return TradingEngine(
            upbitClient = client,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            tradeExecutionService = tradeExecutionService,
            strategies = strategies,
            tradingProperties = tradingProperties,
            userId = user.id!!,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
            webSocketClient = upbitWebSocketClient,
            marketDataStore = marketDataStore,
        )
    }

    private suspend fun saveState(userId: Long, running: Boolean, strategy: String, tickers: List<String>) {
        try {
            val existing = botStateRepository.findByUserId(userId).awaitSingleOrNull()
            val tickersStr = tickers.joinToString(",").ifEmpty {
                existing?.tickers ?: tradingProperties.tickers
            }
            if (existing != null) {
                botStateRepository.save(
                    existing.copy(running = running, strategy = strategy, tickers = tickersStr, updatedAt = LocalDateTime.now())
                ).awaitSingle()
            } else {
                botStateRepository.save(
                    BotStateEntity(userId = userId, running = running, strategy = strategy, tickers = tickersStr)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.error("Failed to save bot state for user {}: {}", userId, e.message)
        }
    }

    companion object {
        private const val RESTORE_MAX_ATTEMPTS = 5
    }
}
