package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.bot.config.ExitParamsDeclarationCheck
import com.trading.common.config.ExitParamRanges
import com.trading.common.config.TradingProperties
import com.trading.bot.persistence.ShadowExitObservationRepository
import com.trading.common.config.ShadowExitProperties
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private val strategy: TradingStrategy,
    private val tradingProperties: TradingProperties,
    private val upbitWebClient: WebClient,
    private val userSecretsService: UserSecretsService,
    private val marketDataStore: MarketDataStore,
    private val tradingStateService: TradingStateService,
    private val dailyCandleCache: DailyCandleCache? = null,
    private val shadowExitProperties: ShadowExitProperties = ShadowExitProperties(),
    private val shadowExitObservationRepository: ShadowExitObservationRepository? = null,
    private val exitParamsDeclarationCheck: ExitParamsDeclarationCheck? = null,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, TradingEngine>()
    // 엔진 등록과 start() 사이에 stop 이 끼지 않도록 사용자별로 start/stop/reload 를 직렬화한다.
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    // 엔진은 멈췄지만 bot_state.running=false 를 쓰지 못한 사용자. DB 행보다 이 집합이 우선한다.
    private val unpersistedStops: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(Dispatchers.Default)
    @Volatile private var lifecycleRunning = false
    @Volatile private var shuttingDown = false
    private var restoreJob: Job? = null

    private fun lockFor(userId: Long): Mutex = userLocks.computeIfAbsent(userId) { Mutex() }

    // DiscordErrorLogAppender 가 붙은 뒤에 복원해야 복원 중 에러가 Discord 로 간다.
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

    // 웹 서버의 graceful shutdown(phase 가 0 보다 크다)이 진행 중 요청을 끝낸 뒤에 엔진을 멈춘다.
    override fun getPhase(): Int = 0

    /**
     * 복원을 취소하고 모든 엔진을 동시에 멈춘 뒤 저장하지 못한 pending·고점을 다시 저장한다.
     * 시간 제한을 넘기면 끊는다. 이때 저장된 pending 은 재시작 후 reconcile 되지만 저장하지 못한 매도는 잃는다.
     */
    override fun stop() {
        lifecycleRunning = false
        shuttingDown = true
        runBlocking {
            val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                restoreJob?.cancelAndJoin()
                // 정지 저장이 걸려도 엔진 정지의 시간 제한을 먹지 않게 동시에 돌린다.
                val finalStopSaves = async { saveUnpersistedStops() }
                if (engines.isNotEmpty()) {
                    log.info("Graceful shutdown: stopping {} running engine(s)", engines.size)
                    engines.entries.toList().map { (userId, engine) ->
                        async {
                            try {
                                engine.stop()
                                flushOrAlert(userId, engine)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.error("Failed to stop engine during shutdown: {}", e.message, e)
                            }
                        }
                    }.awaitAll()
                }
                finalStopSaves.await()
                true
            }
            if (completed == null) {
                log.warn("Graceful shutdown: {}ms 예산 초과 — 일부 엔진 미완 정지(기록된 pending 은 재시작 후 reconcile, 기록하지 못한 매도는 유실될 수 있다)", SHUTDOWN_TIMEOUT_MS)
            }
        }
    }

    /** 정지 저장에 실패했던 사용자를 다시 저장한다. 못 쓰면 재시작 때 멈춘 봇이 다시 뜬다. */
    internal suspend fun saveUnpersistedStops() {
        for (userId in unpersistedStops.toList()) {
            val saved = try {
                withTimeoutOrNull(FINAL_STOP_SAVE_TIMEOUT_MS) {
                    lockFor(userId).withLock {
                        // 그 사이 시작이 저장을 마쳤다면 도는 봇의 행을 덮으면 안 된다.
                        if (userId !in unpersistedStops) return@withLock false
                        markStopped(userId)
                        unpersistedStops.remove(userId)
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("종료 전 정지 상태 저장 실패 user={} — 재시작 때 봇이 다시 시작될 수 있다", userId, e)
                continue
            }
            when (saved) {
                null -> log.error("종료 전 정지 상태 저장 시간 초과 user={} — 재시작 때 봇이 다시 시작될 수 있다", userId)
                true -> log.info("종료 전 정지 상태 저장 완료 user={}", userId)
                false -> Unit
            }
        }
    }

    /** 일시적인 DB·API 실패는 backoff 로 재시도한다. ERROR 는 최종 실패와 종료가 아닌 취소에만 낸다. */
    internal suspend fun restoreAllRunningBots() {
        try {
            restoreWithRetries()
        } catch (e: CancellationException) {
            if (!shuttingDown) log.error("봇 미복원: 복원이 취소로 중단됐다 — 복원되지 않은 봇이 있을 수 있다", e)
            throw e
        }
    }

    private suspend fun restoreWithRetries() {
        var pendingUserIds: List<Long> = emptyList()
        var lastQueryFailed = false
        for (attempt in 1..RESTORE_MAX_ATTEMPTS) {
            val states = try {
                botStateRepository.findByRunningTrueAndExchange(EXCHANGE).collectList().awaitSingle()
            } catch (e: CancellationException) {
                throw e
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
                if (!restoreOne(state.userId)) failed.add(state.userId)
            }
            pendingUserIds = failed
            if (pendingUserIds.isEmpty()) return
            if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt))
        }
        // 조회가 끝까지 실패하면 pendingUserIds 가 비어 있어도 복원된 봇은 없다.
        if (lastQueryFailed) {
            log.error("봇 미복원: bot state 조회가 재시도 {}회 모두 실패 — 복원된 봇 없음", RESTORE_MAX_ATTEMPTS)
        } else if (pendingUserIds.isNotEmpty()) {
            log.error("봇 미복원: {}개 유저 복원 실패 (재시도 {}회 소진) — userIds={}", pendingUserIds.size, RESTORE_MAX_ATTEMPTS, pendingUserIds)
        }
    }

    /**
     * false 를 돌려주면 재시도한다. 행은 lock 안에서 다시 읽는다. 목록 조회와 lock 사이에 정지가 끼면
     * 옛 행으로 엔진이 뜨기 때문이다.
     */
    private suspend fun restoreOne(userId: Long): Boolean = lockFor(userId).withLock {
        if (shuttingDown) return@withLock true
        // 기동 전에 실패해 맵에 남은 엔진은 재시도해야 하므로 containsKey 가 아니라 isRunning 으로 본다.
        if (engines[userId]?.isRunning() == true) return@withLock true
        if (userId in unpersistedStops) return@withLock true
        try {
            val state = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
            if (state == null || !state.running) return@withLock true
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock true
            if (user.upbitAccessKey.isNullOrBlank()) return@withLock true
            val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 복호화와 상태 로드가 실패할 수 있으므로 엔진 등록보다 먼저 한다. 복호화를 로드 뒤에 두면
            // 복호화 실패 때 정지 엔진을 바꾸지 않았는데도 그 매도를 버린다고 알린다.
            val decryptedUser = userSecretsService.decryptUserSecrets(user)
            val initialStates = loadInitialStates(userId)
            val engine = registerNewEngine(userId, decryptedUser)
            engine.start(tickers, initialStates)
            log.info("Restored bot for user {}: strategy={}, tickers={}", userId, strategy.name, tickers)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("restore: user {} 복원 실패 — 재시도 대상: {}", userId, e.message)
            false
        }
    }

    private fun restoreBackoffMs(attempt: Int): Long = minOf(1000L shl (attempt - 1), 30_000L)

    fun getEngine(userId: Long): TradingEngine? = engines[userId]

    suspend fun startBot(userId: Long, tickers: List<String>?): Map<String, Any> = lockFor(userId).withLock {
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
        // 도는 엔진의 start 는 no-op 이므로 저장하기 전에 가른다.
        val running = engines[userId]?.takeIf { it.isRunning() }
        if (running != null) return@withLock respondToRunningEngine(userId, running, tickers)
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")

        if (user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            return@withLock mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val initialStates = loadInitialStates(userId)
        val engine = registerNewEngine(userId, decryptedUser)

        val tickerList = tickers ?: tradingProperties.tickerList()
        exitParamsDeclarationCheck?.report("수동 기동 user=$userId")
        // 기동 뒤에 저장하면 저장이 실패해도 봇이 돌고 재시작 때 복원되지 않으므로 먼저 저장한다.
        try {
            persistOrFail(userId, START_UNPERSISTED_MESSAGE) { saveRunningState(userId, tickerList) }
        } catch (e: BotControlPersistFailedException) {
            engines.remove(userId, engine)
            throw e
        }
        unpersistedStops.remove(userId)
        // 저장 중에 종료가 시작됐을 수 있어 기동 전후로 다시 본다. 저장한 running=true 는 남겨 재시작 때 복원되게 한다.
        if (shuttingDown) return@withLock mapOf("error" to START_SAVED_SHUTTING_DOWN_MESSAGE)
        engine.start(tickerList, initialStates)
        if (stopIfShutdownStarted(userId, engine)) return@withLock mapOf("error" to START_SAVED_SHUTTING_DOWN_MESSAGE)
        mapOf("status" to "started", "strategy" to strategy.name)
    }

    /**
     * 도는 엔진에 다른 목록으로 start 하면 거절한다. 저장만 하면 엔진은 옛 목록으로 돌다가 재시작 때 새 목록이 적용된다.
     * 목록이 같거나 없으면 저장 행을 엔진의 사용자 목록에 맞춘다.
     */
    private suspend fun respondToRunningEngine(
        userId: Long,
        engine: TradingEngine,
        tickers: List<String>?,
    ): Map<String, Any> {
        val current = engine.getUserTickers()
        if (tickers != null && normalizedTickers(tickers) != normalizedTickers(current)) {
            return mapOf(
                "error" to "Bot is already running with $current — stop it before changing tickers",
                "code" to CONFLICT_CODE,
            )
        }
        persistOrFail(userId, RUNNING_UNPERSISTED_MESSAGE) { saveRunningState(userId, current) }
        return mapOf("status" to "already_running", "strategy" to strategy.name)
    }

    // 요청은 검증기가 대문자·distinct 로 만들지만 엔진 목록은 bot_state·설정에서 trim 만 된 값이다.
    private fun normalizedTickers(tickers: List<String>): Set<String> =
        tickers.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * 저장이 실패해도 엔진은 멈춘다. DB 장애가 정지를 막으면 안 된다. 엔진이 없어도 행이 running 이면 내린다.
     * 저장이 확인될 때까지 사용자는 [unpersistedStops] 에 남는다.
     */
    suspend fun stopBot(userId: Long): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId]
        // 엔진만 멈추고 표시가 빠지면 재시작 때 봇이 다시 뜨므로 요청이 취소돼도 여기까지는 마친다.
        withContext(NonCancellable) {
            if (engine != null) {
                engine.stop()
                engines.remove(userId)
            }
            unpersistedStops.add(userId)
            // 엔진이 맵에서 빠졌으므로 여기서 끊기면 저장하지 못한 매도가 사라진다.
            if (engine != null) flushOrAlert(userId, engine)
        }
        // 걸린 DB 호출이 lock 을 계속 쥐지 않게 시간 제한을 둔다. 실패하면 종료 때 다시 저장한다.
        persistOrFail(userId, STOP_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS) { markStopped(userId) } ?: throw IllegalStateException("bot_state 저장 시간 초과")
        }
        unpersistedStops.remove(userId)
        mapOf("status" to if (engine != null) "stopped" else "not_running")
    }

    fun getStatus(userId: Long): Map<String, Any> {
        val engine = engines[userId]
        // 맵에는 정지된 채 남은 엔진이 있을 수 있으므로 목록은 도는 엔진에서만 읽는다.
        val live = engine?.takeIf { it.isRunning() }
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            "strategy" to strategy.name,
            // states 는 루프가 돌아야 채워지므로 start 직후에도 값이 있는 activeTickers 를 쓴다.
            "tickers" to (engine?.getActiveTickers() ?: emptyList<String>()),
            "user_tickers" to (live?.getUserTickers() ?: emptyList<String>()),
            "entry_tickers" to (live?.getEntryTickers() ?: emptyList<String>()),
            "exit_only_tickers" to (live?.getExitOnlyTickers() ?: emptyList<String>()),
            "default_tickers" to tradingProperties.tickerList(),
            "positions" to (engine?.getStates()?.map { (ticker, state) ->
                mapOf(
                    "ticker" to ticker,
                    "position" to state.position,
                    "avg_buy_price" to state.avgBuyPrice,
                    "hold_volume" to state.holdVolume,
                    "bought_today" to state.boughtToday,
                    "halted" to state.halted,
                    "unsynced" to state.unsynced,
                    // 미해소 주문이 있으면 그 티커는 매매를 평가하지 않는다. 정지 뒤 /api/bot/pending/clear 로 푼다.
                    "pending_buy" to state.pendingRef(TradeSide.BUY)?.let { mapOf("by" to it.by, "ref" to it.ref) },
                    "pending_sell" to state.pendingRef(TradeSide.SELL)?.let {
                        mapOf("by" to it.by, "ref" to it.ref, "since" to state.pendingSellSince?.toString())
                    },
                )
            } ?: emptyList<Map<String, Any?>>()),
            "halted_tickers" to (engine?.getHaltedTickers() ?: emptyList<String>()),
        )
    }

    /**
     * 자동으로 풀리지 않는 pending 을 거래소를 확인한 사람이 지운다. 도는 엔진은 매 tick 같은 state 를 reconcile 하므로
     * 정지된 봇에서만 받는다. 엔진이 없어도 저장 행이 running 이면 곧 기동되므로 거절한다.
     */
    suspend fun clearPending(userId: Long, ticker: String, side: TradeSide): Map<String, Any> = lockFor(userId).withLock {
        // 종료 중 저장은 lock 밖에서 돌아 해제 전 메모리 값으로 되쓸 수 있다.
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
        val engine = engines[userId]
        val running = engine?.isRunning() == true || (
            userId !in unpersistedStops &&
                botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()?.running == true
            )
        if (running) {
            return@withLock mapOf("error" to "Stop the bot before clearing a pending order", "code" to CONFLICT_CODE)
        }
        // 정지 엔진의 미저장 상태를 먼저 저장한다. 그래도 DB 에 없을 수 있는 매도가 남으면 해제하지 않는다.
        // 엔진을 버리면 그 주문의 유일한 기록이 사라진다.
        if (engine != null) {
            val unrecorded = withContext(NonCancellable) { flushOrLog(userId, engine) }
            if (unrecorded.isNotEmpty()) {
                throw BotControlPersistFailedException(
                    PENDING_CLEAR_UNPERSISTED_MESSAGE,
                    IllegalStateException("정지 엔진의 매도 주문 기록을 DB 에 남기지 못함: ${sellRefs(unrecorded)}"),
                )
            }
        }
        val state = tradingStateService.loadState(userId, ticker)
        val ref = state?.pendingRef(side) ?: return@withLock mapOf("status" to "not_pending")
        if (engine != null) engines.remove(userId, engine)
        val cleared = state.pendingFields(side)
        // 지운 값의 유일한 기록이므로 저장 전에 남긴다. 체결된 주문이었다면 이 값으로 거래 기록을 맞춘다.
        log.warn("Clearing pending {} {} ({} {}) by hand for user {} — fields: {}", side, ticker, ref.by, ref.ref, userId, cleared)
        state.releasePending(side, TradingDay.of(LocalDateTime.now(TradingDay.KST)))
        persistOrFail(userId, PENDING_CLEAR_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS) { tradingStateService.upsert(userId, state) }
                ?: throw IllegalStateException("trading_states 저장 시간 초과")
        }
        log.info("Pending {} {} cleared for user {}", side, ticker, userId)
        mapOf("status" to "cleared", "ticker" to ticker, "side" to side.name.lowercase(), "cleared" to cleared)
    }

    suspend fun clearHalt(userId: Long, ticker: String): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId] ?: return@withLock mapOf("status" to "not_running")
        val cleared = persistOrFail(userId, HALT_CLEAR_UNPERSISTED_MESSAGE) { engine.clearHalt(ticker) }
        mapOf("status" to if (cleared) "cleared" else "not_halted")
    }

    fun createUpbitClient(user: UserEntity): UpbitClient {
        val authProvider = UpbitAuthProvider(
            accessKey = user.upbitAccessKey ?: "",
            secretKey = user.upbitSecretKey ?: "",
        )
        return UpbitClientImpl(upbitWebClient, authProvider)
    }

    /** 키·웹훅 저장 뒤 엔진을 저장된 설정으로 바꾼다. 요청이 끊기면 응답이 가지 않으므로 반영되지 않았을 수 있음을 로그로 알린다. */
    suspend fun reloadUserRuntime(userId: Long) {
        var locked = false
        try {
            lockFor(userId).withLock {
                locked = true
                reloadLocked(userId)
            }
        } catch (e: CancellationException) {
            // lock 대기 중 취소되면 lock 을 쥔 다른 요청이 이전 설정으로 엔진을 띄울 수 있어 엔진이 없어도 알린다.
            // lock 을 쥔 뒤의 취소는 교체 전이므로 도는 엔진이 남았을 때만 알린다.
            if (!locked || engines[userId]?.isRunning() == true) {
                log.error(
                    "reload: user {} 요청이 끊겨 저장된 새 설정(자격증명·웹훅)이 반영되지 않았을 수 있다 — 봇이 이전 설정으로 돌 수 있으니 다시 저장해야 한다",
                    userId,
                )
            }
            throw e
        }
    }

    private suspend fun reloadLocked(userId: Long) {
        if (shuttingDown) return
        val existing = engines[userId] ?: return
        val decryptedUser = try {
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return
            userSecretsService.decryptUserSecrets(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 옛 엔진은 아직 이전 설정으로 돌고 있다. 원래 예외를 올리면 저장 실패로 읽힌다.
            if (existing.isRunning()) throw RuntimeReloadFailedException(userId, e, engineRestored = true)
            log.warn("reload: user {} 정지 엔진의 설정을 읽지 못해 그대로 둔다 — 다음 시작이 저장된 설정을 읽는다: {}", userId, e.message)
            return
        }
        val wasRunning = existing.isRunning()
        // activeTickers 를 넘기면 목록에서 빠진 티커가 새 엔진의 진입 대상이 된다.
        val tickers = existing.getUserTickers()
        // 요청이 끊겨도 루프 join 까지 기다린다. join 전에 돌아가면 옛 루프와 새 루프가 겹친다.
        withContext(NonCancellable) { existing.stop() }
        if (leftToShutdown(userId)) return
        // stop 뒤에 읽어야 마지막 tick 의 주문까지 잡힌다. 매도 기록을 끝내 남기지 못하면 새 엔진은 그 주문을 모르므로
        // 교체하지 않고 옛 엔진으로 돌아간다.
        val initialStates = if (wasRunning) {
            try {
                val unrecorded = flushStoppedEngine(userId, existing)
                check(unrecorded.isEmpty()) { "매도 주문 기록을 DB 에 남기지 못함: ${sellRefs(unrecorded)}" }
                tradingStateService.loadStates(userId)
            } catch (e: CancellationException) {
                // stop() 은 이미 끝났으므로 되살리지 않으면 손절이 멈춘 엔진만 남는다.
                if (!leftToShutdown(userId)) {
                    withContext(NonCancellable) {
                        runCatching { existing.resume() }
                            .onFailure { log.error("reload: user {} 취소 중 기존 엔진 복귀 실패 — 엔진 정지 상태", userId, it) }
                        stopIfShutdownStarted(userId, existing)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (leftToShutdown(userId, e)) return
                // 여기서 포기하면 멈춘 엔진만 남아 손절이 돌지 않으므로 옛 엔진을 되살린다.
                log.error("reload: user {} 새 엔진에 넘길 상태를 준비하지 못함 — 기존 엔진으로 복귀: {}", userId, e.message, e)
                try {
                    existing.resume()
                } catch (restoreFailure: CancellationException) {
                    throw restoreFailure
                } catch (restoreFailure: Exception) {
                    log.error("reload: user {} 기존 엔진 복귀 실패 — 엔진이 정지 상태로 남는다", userId, restoreFailure)
                    // 알림을 받은 사람이 DB 를 맞추므로 엔진은 버린다. 맵에 남기면 다음 시작·정지가 메모리 값으로 DB 를 되쓴다.
                    alertDroppedSells(userId, existing.unpersistedSells())
                    engines.remove(userId, existing)
                    restoreFailure.addSuppressed(e)
                    throw RuntimeReloadFailedException(userId, restoreFailure, engineRestored = false)
                }
                if (stopIfShutdownStarted(userId, existing)) return
                // 되살린 엔진은 이전 자격증명으로 주문하므로 성공으로 응답하면 안 된다.
                throw RuntimeReloadFailedException(userId, e, engineRestored = true)
            }
        } else {
            // 교체하면 정지 엔진의 states 가 버려지므로 먼저 저장한다.
            flushOrAlert(userId, existing)
            emptyMap()
        }
        if (leftToShutdown(userId)) return
        val replacement = createEngine(decryptedUser)
        engines[userId] = replacement
        if (wasRunning) {
            replacement.start(tickers, initialStates)
            stopIfShutdownStarted(userId, replacement)
        }
    }

    /**
     * 종료가 시작됐으면 옛 엔진을 정지 상태로 맵에 두고 종료에 맡긴다. 종료가 맵을 훑은 뒤에 띄운 엔진은 아무도 멈추지 않는다.
     */
    private fun leftToShutdown(userId: Long, cause: Exception? = null): Boolean {
        if (!shuttingDown) return false
        if (cause == null) {
            log.info("reload: user {} 종료가 시작돼 엔진을 되살리거나 교체하지 않는다 — 종료가 정지 상태를 기록한다", userId)
        } else {
            log.warn("reload: user {} 종료 중 상태 준비 실패 — 엔진을 되살리지 않고 종료가 기록한다: {}", userId, cause.message, cause)
        }
        return true
    }

    /**
     * 기동 직후 종료 플래그를 다시 본다. 종료는 플래그를 쓴 뒤 맵을 보고 여기는 기동한 뒤 플래그를 읽으므로
     * 둘 중 하나는 반드시 상대를 본다.
     */
    private suspend fun stopIfShutdownStarted(userId: Long, engine: TradingEngine): Boolean {
        if (!shuttingDown) return false
        log.info("user {} 엔진 기동 중 종료가 시작됐다 — 방금 기동한 엔진을 멈추고 기록한다", userId)
        withContext(NonCancellable) {
            engine.stop()
            flushOrAlert(userId, engine)
        }
        return true
    }

    internal fun createEngine(user: UserEntity): TradingEngine {
        val client = createUpbitClient(user)
        // 상태 저장과 거래 기록은 한 트랜잭션으로 커밋하고 알림은 커밋 뒤에만 보낸다.
        val positionManager = PositionManager(
            client, tradingProperties, tradingStateService, user.id!!,
            commitFill = { persistState, record ->
                tradeExecutionService.commitFill(persistState, record)
            },
            notifyTrade = { record ->
                tradeExecutionService.notifyTrade(record, client, user.username, user.discordWebhookUrl)
            },
        )
        val dailyResetManager = DailyResetManager(tradingProperties, userId = user.id!!)

        return TradingEngine(
            upbitClient = client,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            strategy = strategy,
            tradingProperties = tradingProperties,
            userId = user.id!!,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
            marketDataStore = marketDataStore,
            dailyCandleCache = dailyCandleCache,
            shadowExitObserver = shadowExitObserver(user.id!!),
        )
    }

    /** 설정이 꺼져 있거나 저장소가 없으면 null 이다. */
    internal fun shadowExitObserver(userId: Long): ShadowExitObserver? {
        // 설정은 여러 배포 계층을 거치며 빠지기 쉬워서 꺼진 경우도 로그로 남긴다.
        if (!shadowExitProperties.enabled) {
            log.info("[shadow-exit] 관측 off (trading.shadow-exit.enabled=false) — user {}", userId)
            return null
        }
        // ERROR 는 ExitParamsDeclarationCheck 가 이미 낸다.
        val outOfRange = ExitParamRanges.violations(shadowExitProperties)
        if (outOfRange.isNotEmpty()) {
            log.warn("[shadow-exit] 관측 off — 설정이 구간 밖이다 {} — user {}", outOfRange, userId)
            return null
        }
        val repository = shadowExitObservationRepository ?: run {
            log.warn("trading.shadow-exit.enabled=true 인데 저장소가 없어 관측을 켜지 않는다")
            return null
        }
        log.info(
            "[shadow-exit] 관측 on — user {} 후보 트레일링 {}% / arm {}% (매매 무영향, 기록 전용)",
            userId, shadowExitProperties.trailingStopPct, shadowExitProperties.trailingArmPct,
        )
        return ShadowExitObserver(
            repository = repository,
            userId = userId,
            trailingStopPct = shadowExitProperties.trailingStopPct,
            trailingArmPct = shadowExitProperties.trailingArmPct,
        )
    }

    /** 기존 행과 같으면 쓰지 않는다. strategy 칸은 읽지 않지만 이전 버전으로 롤백할 때를 위해 계속 쓴다. */
    private suspend fun saveRunningState(userId: Long, tickers: List<String>) {
        val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
        val tickersStr = tickers.joinToString(",").ifEmpty {
            existing?.tickers ?: tradingProperties.tickers
        }
        if (existing != null) {
            if (existing.running && existing.strategy == strategy.name && existing.tickers == tickersStr) return
            botStateRepository.save(
                existing.copy(running = true, strategy = strategy.name, tickers = tickersStr, updatedAt = LocalDateTime.now())
            ).awaitSingle()
        } else {
            botStateRepository.save(
                BotStateEntity(userId = userId, exchange = EXCHANGE, running = true, strategy = strategy.name, tickers = tickersStr)
            ).awaitSingle()
        }
    }

    private suspend fun markStopped(userId: Long) {
        val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull() ?: return
        if (!existing.running) return
        botStateRepository.save(existing.copy(running = false, updatedAt = LocalDateTime.now())).awaitSingle()
    }

    private inline fun <T> persistOrFail(userId: Long, userMessage: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("봇 제어 상태 저장 실패 user={} — {}", userId, userMessage, e)
            throw BotControlPersistFailedException(userMessage, e)
        }

    /** 멈춘 엔진의 미저장 상태를 다시 저장하고, 그래도 DB 에 없을 수 있는 매도를 돌려준다. */
    private suspend fun flushStoppedEngine(userId: Long, engine: TradingEngine): List<TradingState> {
        // withTimeout 을 쓰면 시간 초과가 취소로 번져 reload 가 실패 응답을 내지 못한다.
        if (withTimeoutOrNull(FLUSH_TIMEOUT_MS) { engine.flushUnpersisted() } == null) {
            log.warn("user {} 의 미기록 주문·고점 재기록이 {}ms 안에 끝나지 않았다", userId, FLUSH_TIMEOUT_MS)
        }
        return engine.unpersistedSells()
    }

    /** 뒤따르는 정지 저장·로드가 막히지 않도록 취소 외의 예외는 로그만 남긴다. */
    private suspend fun flushOrLog(userId: Long, engine: TradingEngine): List<TradingState> =
        try {
            flushStoppedEngine(userId, engine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("user {} 의 미기록 주문·고점을 다시 남기지 못함", userId, e)
            engine.unpersistedSells()
        }

    /** 엔진의 states 를 버리기 전에 부른다. */
    private suspend fun flushOrAlert(userId: Long, engine: TradingEngine) = alertDroppedSells(userId, flushOrLog(userId, engine))

    /**
     * 맵에 남은 정지 엔진은 곧 교체되므로 읽기 전에 그 상태를 저장한다. 읽기가 실패하면 엔진을 바꾸지 않으므로
     * 알림은 읽기가 성공한 뒤에만 낸다.
     */
    private suspend fun loadInitialStates(userId: Long): Map<String, TradingState> {
        val unrecorded = engines[userId]?.let { flushOrLog(userId, it) }.orEmpty()
        val loaded = tradingStateService.loadStates(userId)
        alertDroppedSells(userId, unrecorded)
        return loaded
    }

    /**
     * 맵에 남은 정지 엔진은 이전 자격증명으로 만들어졌을 수 있어 재사용하지 않는다. [loadInitialStates] 뒤에 부른다.
     */
    private fun registerNewEngine(userId: Long, user: UserEntity): TradingEngine =
        createEngine(user).also { engines[userId] = it }

    /**
     * 선기록까지 실패한 매도는 아무도 확정하지 않는다. uuid 기록만 실패한 매도도 섞여 있는데, 이쪽은 다음 기동이
     * identifier 로 확정하므로 바로 수동 기록하면 이중으로 잡힌다.
     */
    private fun alertDroppedSells(userId: Long, unrecorded: List<TradingState>) {
        if (unrecorded.isEmpty()) return
        log.error(
            "user {}: DB 에 없을 수 있는 매도 주문을 남긴 채 엔진 상태를 버린다 — 다음 기동 뒤 봇이 이 주문을 스스로 확정했는지(거래 기록) " +
                "먼저 확인하고, 없으면 Upbit 주문 내역으로 거래 기록을 맞춰야 한다: {}",
            userId, sellRefs(unrecorded),
        )
    }

    private fun sellRefs(states: List<TradingState>): Map<String, String?> = states.associate { it.ticker to it.pendingSellRef() }

    companion object {
        // 컨트롤러가 409 로 응답한다.
        const val CONFLICT_CODE = "conflict"

        private const val RESTORE_MAX_ATTEMPTS = 5
        private const val SHUTDOWN_TIMEOUT_MS = 25_000L // Spring timeout-per-shutdown-phase(30s) 보다 짧게
        // 저장하지 않은 "Service is shutting down" 과 달리 재시작 때 봇이 다시 뜬다.
        internal const val START_SAVED_SHUTTING_DOWN_MESSAGE =
            "Service is shutting down — the start is saved and the bot resumes after the restart"
        private const val FINAL_STOP_SAVE_TIMEOUT_MS = 5_000L
        private const val STOP_SAVE_TIMEOUT_MS = 10_000L
        private const val FLUSH_TIMEOUT_MS = 5_000L

        private const val EXCHANGE = "UPBIT"
    }
}
