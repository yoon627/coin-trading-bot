package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.config.UpbitProperties
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
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class UserTradingManager(
    private val userRepository: UserRepository,
    private val botStateRepository: BotStateRepository,
    private val tradeExecutionService: TradeExecutionService,
    private val discordNotifier: DiscordNotifier,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val upbitWebClient: WebClient,
    private val userSecretsService: UserSecretsService,
    private val marketDataStore: MarketDataStore,
    private val tradingStateService: TradingStateService,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, TradingEngine>()
    private val userStrategies = ConcurrentHashMap<Long, String>()
    // userId 별 Mutex 로 start/stop/reload/setStrategy 의 engines mutate 를 직렬화.
    // CAS (remove(k,v) / replace(k,old,new)) 만으로는 computeIfAbsent 직후 start() 호출 전에
    // stop 이 끼어드는 race window 를 닫지 못함.
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    private val scope = CoroutineScope(Dispatchers.Default)
    // SmartLifecycle 상태 + shutdown 진행 플래그(신규 엔진 기동 차단). restoreJob 은 shutdown 시 취소 대상(M5).
    @Volatile private var lifecycleRunning = false
    @Volatile private var shuttingDown = false
    private var restoreJob: Job? = null

    private fun lockFor(userId: Long): Mutex = userLocks.computeIfAbsent(userId) { Mutex() }

    // DiscordErrorLogAppender(@Order HIGHEST)가 먼저 attach 된 뒤 restore 가 실행되도록 낮은 우선순위.
    // ApplicationReadyEvent 리스너는 순차 동기 호출이라 appender.attach 완료 후 이 리스너의 scope.launch 가 돈다
    // → restore 중 에러도 Discord 도달. backoff 재시도는 순서 보장이 아니라 일시적 DB/API 실패 회복용이다.
    // (@PostConstruct 는 attach(ApplicationReadyEvent)보다 일러 초기 에러가 미도달이었다 — 그래서 이 이벤트로 이동.)
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun restoreOnStartup() {
        if (!tradingProperties.autoStart) {
            log.info("Auto-start disabled. Skipping bot restoration.")
            return
        }
        restoreJob = scope.launch { restoreAllRunningBots() }
    }

    override fun start() {
        lifecycleRunning = true
    }

    override fun isRunning(): Boolean = lifecycleRunning

    // web 요청 드레이닝(WebServer phase = Integer.MAX_VALUE) 이후에 엔진을 멈추도록 낮은 phase. appender detach(@PreDestroy)는
    // 모든 SmartLifecycle.stop 뒤라 자동 후행(@DependsOn 불필요).
    override fun getPhase(): Int = 0

    /**
     * graceful shutdown: SmartLifecycle.stop 은 @PreDestroy 와 달리 `timeout-per-shutdown-phase`(30s) 예산을 실제로
     * 받아 무한 hang 을 막는다(@PreDestroy 엔 미적용 — 리뷰 arch Major). 진행 중 restore 를 먼저 취소해(M5) shutdown
     * 이후 엔진 기동을 막고, 모든 엔진을 동시 stop(cancelAndJoin — runBlocking 이벤트루프의 협조적 동시)해 tick 후처리
     * 완주를 기다린다. NonCancellable 후처리가 예산을 넘기면 self-bound(withTimeoutOrNull)로 끊고 — 잔여 daemon 코루틴은
     * JVM 종료로 정리되고 — 미완 pending 은 재시작 후 durable reconcile(#20) 소관으로 남긴다.
     */
    override fun stop() {
        lifecycleRunning = false
        shuttingDown = true
        runBlocking {
            val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                restoreJob?.cancelAndJoin()
                if (engines.isNotEmpty()) {
                    log.info("Graceful shutdown: stopping {} running engine(s)", engines.size)
                    engines.values.toList().map { engine ->
                        async {
                            try {
                                engine.stop()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.error("Failed to stop engine during shutdown: {}", e.message, e)
                            }
                        }
                    }.awaitAll()
                }
                true
            }
            if (completed == null) {
                log.warn("Graceful shutdown: {}ms 예산 초과 — 일부 엔진 미완 정지(잔여는 재시작 후 durable reconcile 소관)", SHUTDOWN_TIMEOUT_MS)
            }
        }
    }

    /** running bot 을 복원하되, 일시적 실패(DB/API)는 유한 backoff 로 재시도하고 최종 실패만 ERROR alert(→Discord). */
    internal suspend fun restoreAllRunningBots() {
        var pendingUserIds: List<Long> = emptyList()
        var lastQueryFailed = false
        for (attempt in 1..RESTORE_MAX_ATTEMPTS) {
            val states = try {
                botStateRepository.findByRunningTrueAndExchange(EXCHANGE).collectList().awaitSingle()
            } catch (e: Exception) {
                log.warn("restore: bot state 조회 실패 (attempt {}/{}): {}", attempt, RESTORE_MAX_ATTEMPTS, e.message)
                lastQueryFailed = true
                if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt))
                continue
            }
            lastQueryFailed = false
            if (attempt == 1) log.info("Restoring {} running bot(s) from DB", states.size)
            val failed = mutableListOf<Long>()
            for (state in states) {
                if (!restoreOne(state)) failed.add(state.userId)
            }
            pendingUserIds = failed
            if (pendingUserIds.isEmpty()) return
            if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt))
        }
        // 조회가 끝까지 실패하면 pendingUserIds 는 비어 있어도 복원은 0건 — 이 케이스도 alert 해야 한다(M2).
        if (lastQueryFailed) {
            log.error("봇 미복원: bot state 조회가 재시도 {}회 모두 실패 — 복원된 봇 없음", RESTORE_MAX_ATTEMPTS)
        } else if (pendingUserIds.isNotEmpty()) {
            log.error("봇 미복원: {}개 유저 복원 실패 (재시도 {}회 소진) — userIds={}", pendingUserIds.size, RESTORE_MAX_ATTEMPTS, pendingUserIds)
        }
    }

    /**
     * 한 유저 복원. per-user lock 으로 start/stop 과 직렬화하고, lock 획득 후 engines 를 재확인해 사용자가 이미
     * 개입(start/stop)했으면 skip — restoreOnStartup 만 lockFor 를 우회하던 유령 엔진 경합을 차단한다.
     * 반환: true=복원 완료 또는 재시도 무의미(유저/키 없음·이미 개입), false=일시적 실패(재시도 대상).
     */
    private suspend fun restoreOne(state: BotStateEntity): Boolean = lockFor(state.userId).withLock {
        if (shuttingDown) return@withLock true // 종료 중 — 신규 엔진 기동 안 함(M5)
        // containsKey 가 아니라 isRunning — 기동 전에 실패해 map 에 남은 엔진은 재시도 대상이어야 한다.
        if (engines[state.userId]?.isRunning() == true) return@withLock true
        try {
            val user = userRepository.findById(state.userId).awaitSingleOrNull() ?: return@withLock true
            if (user.upbitAccessKey.isNullOrBlank()) return@withLock true
            val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            userStrategies[state.userId] = state.strategy
            // durable 상태 로드를 엔진 등록보다 먼저 — 여기서 터지면 engines 에 아무것도 남기지 않는다.
            val initialStates = tradingStateService.loadStates(state.userId)
            val engine = engines.computeIfAbsent(state.userId) {
                createEngine(userSecretsService.decryptUserSecrets(user))
            }
            engine.setStrategy(state.strategy)
            engine.start(tickers, initialStates)
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
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down") // 종료 중 신규 엔진 기동 차단(M5 일관)
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")

        if (user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            return@withLock mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        // restoreOne 과 같은 이유로 durable 로드를 엔진 등록 앞에 둔다(실패 시 미기동 엔진 잔류 방지).
        val initialStates = tradingStateService.loadStates(userId)
        val engine = engines.computeIfAbsent(userId) { createEngine(decryptedUser) }

        val strategy = strategyName ?: userStrategies[userId]
        if (strategy != null) engine.setStrategy(strategy)

        val tickerList = tickers ?: tradingProperties.tickerList()
        engine.start(tickerList, initialStates)

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
                    "halted" to state.halted,
                )
            } ?: emptyList<Map<String, Any>>()),
            "halted_tickers" to (engine?.getHaltedTickers() ?: emptyList<String>()),
        )
    }

    /** #19: halt 된 ticker 수동 해제 — 다음 tick 부터 reconcile/매매 재개. */
    suspend fun clearHalt(userId: Long, ticker: String): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId] ?: return@withLock mapOf("status" to "not_running")
        val cleared = try {
            engine.clearHalt(ticker)
        } catch (e: Exception) {
            // durable 반영 실패 — 해제되지 않았음을 그대로 알린다(재시도는 사용자 몫).
            log.error("halt 해제 실패 user={} ticker={}: {}", userId, ticker, e.message, e)
            return@withLock mapOf("error" to "Failed to clear halt — state not persisted")
        }
        mapOf("status" to if (cleared) "cleared" else "not_halted")
    }

    suspend fun setStrategy(userId: Long, strategyName: String): Boolean = lockFor(userId).withLock {
        val valid = strategies.any { it.name == strategyName }
        if (!valid) return@withLock false
        userStrategies[userId] = strategyName
        engines[userId]?.setStrategy(strategyName)

        val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
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
        if (shuttingDown) return@withLock // 종료 중 — 엔진 교체·재기동 안 함(M5 일관)
        val existing = engines[userId] ?: return@withLock
        val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock
        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val wasRunning = existing.isRunning()
        val tickers = existing.getActiveTickers()
        val strategy = existing.getActiveStrategyName()
        existing.stop()
        // stop 이후에 읽어야 마지막 tick 의 durable flush(pending 주문 포함)까지 잡힌다 — 먼저 읽으면
        // 그 사이 발생한 주문이 스냅샷에서 빠져 orphan pending 이 된다(#20).
        val initialStates = if (wasRunning) {
            try {
                tradingStateService.loadStates(userId)
            } catch (e: Exception) {
                // 교체 실패는 정지 의도가 아니다 — 여기서 포기하면 stop 된 엔진만 남아 보유 포지션의 손절이
                // 무기한 중단된다(무증상). 옛 엔진을 원래 상태로 되살리고 알린다.
                log.error("reload: user {} durable 상태 로드 실패 — 기존 엔진으로 복귀: {}", userId, e.message, e)
                existing.start(tickers.ifEmpty { tradingProperties.tickerList() }, emptyMap())
                return@withLock
            }
        } else {
            emptyMap()
        }
        val replacement = createEngine(decryptedUser)
        replacement.setStrategy(strategy)
        engines[userId] = replacement
        userStrategies[userId] = strategy
        if (wasRunning) {
            replacement.start(tickers.ifEmpty { tradingProperties.tickerList() }, initialStates)
        }
    }

    internal fun createEngine(user: UserEntity): TradingEngine {
        val client = createUpbitClient(user)
        // #52: 체결 확정 시 상태 전이 저장과 감사 기록을 한 트랜잭션으로 커밋하고, 커밋 후에만 알림한다.
        val positionManager = PositionManager(
            client, tradingProperties, tradingStateService, user.id!!,
            commitFill = { persistState, record ->
                tradeExecutionService.commitFill(persistState, record)
            },
            notifyTrade = { record ->
                tradeExecutionService.notifyTrade(record, client, user.username, user.discordWebhookUrl)
            },
        )
        val dailyResetManager = DailyResetManager(tradingProperties)

        return TradingEngine(
            upbitClient = client,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            strategies = strategies,
            tradingProperties = tradingProperties,
            userId = user.id!!,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
            marketDataStore = marketDataStore,
        )
    }

    private suspend fun saveState(userId: Long, running: Boolean, strategy: String, tickers: List<String>) {
        try {
            val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
            val tickersStr = tickers.joinToString(",").ifEmpty {
                existing?.tickers ?: tradingProperties.tickers
            }
            if (existing != null) {
                botStateRepository.save(
                    existing.copy(running = running, strategy = strategy, tickers = tickersStr, updatedAt = LocalDateTime.now())
                ).awaitSingle()
            } else {
                botStateRepository.save(
                    BotStateEntity(userId = userId, exchange = EXCHANGE, running = running, strategy = strategy, tickers = tickersStr)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.error("Failed to save bot state for user {}: {}", userId, e.message)
        }
    }

    companion object {
        private const val RESTORE_MAX_ATTEMPTS = 5
        private const val SHUTDOWN_TIMEOUT_MS = 25_000L // Spring timeout-per-shutdown-phase(30s) 안쪽 self-bound

        // bot_state 는 (user_id, exchange) 별 1행 — 이 매니저는 Upbit 행만 다룬다(KIS 는 StockUserTradingManager).
        private const val EXCHANGE = "UPBIT"
    }
}
