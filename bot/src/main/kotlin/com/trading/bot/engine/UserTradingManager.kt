package com.trading.bot.engine

import com.trading.bot.events.UserEventHub
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.persistence.UserTradingParamsService
import com.trading.bot.domain.UserTradingParams
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
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
import com.trading.common.config.PriceEventsProperties
import com.trading.common.config.ShadowExitProperties
import com.trading.common.domain.Exchange
import com.trading.common.strategy.TradingStrategy
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
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
    runtime: ExchangeRuntime? = null,
    private val priceEventsProperties: PriceEventsProperties = PriceEventsProperties(),
    private val userTradingParams: UserTradingParamsService? = null,
    private val userEvents: UserEventHub? = null,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upbitRuntime = UpbitExchangeRuntime(upbitWebClient, tradingProperties, marketDataStore, dailyCandleCache)
    private val runtime: ExchangeRuntime = runtime ?: upbitRuntime

    init {
        // 기본값 null 은 테스트용이다. 운영에서 빠지면 사용자가 낮춘 한도가 env 로 풀리므로 알린다.
        if (userTradingParams == null) log.warn("사용자 매매 값 저장소가 없다 — 모든 엔진이 env 매매 값으로 돈다")
    }
    val exchange: Exchange get() = runtime.exchange
    private val exchangeKey: String = this.runtime.exchange.name
    private val engines = ConcurrentHashMap<Long, TradingEngine>()

    // 엔진 등록과 start() 사이에 stop 이 끼지 않도록 사용자별로 start/stop/reload 를 직렬화한다.
    private val userLocks = ConcurrentHashMap<Long, Mutex>()

    // 엔진은 멈췄지만 bot_state.running=false 를 쓰지 못한 사용자. DB 행보다 이 집합이 우선한다.
    private val unpersistedStops: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(Dispatchers.Default)
    @Volatile private var lifecycleRunning = false
    @Volatile private var shuttingDown = false
    private var restoreJob: Job? = null
    @Volatile private var restoreProgress = RestoreStatus.NOT_STARTED

    // 사용자 목록 없이 끝난 복원(bot state 조회 실패·예기치 않은 오류). 재시작 전까지 FAILED 다.
    @Volatile private var restoreAborted = false

    // 재시도를 다 쓰고 복원하지 못한 사용자. 그 사용자를 손으로 시작하거나 멈추면 빠진다.
    private val restoreFailedUsers: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // 엔진이 매수마다 읽으므로 저장이 끝난 값만 넣는다. 없는 사용자는 아직 행을 읽지 않은 것이다.
    private val entriesPauses = ConcurrentHashMap<Long, EntriesPause>()

    private data class EntriesPause(val paused: Boolean, val changedAt: Instant?)

    /** 기동 복원의 결과. 배포 판정(health)이 읽는다. */
    val restoreStatus: RestoreStatus
        get() = if (restoreProgress == RestoreStatus.DONE && (restoreAborted || restoreFailedUsers.isNotEmpty())) {
            RestoreStatus.FAILED
        } else {
            restoreProgress
        }

    // 제어 경로(시작·정지·복원·교체·매수 중지·키 저장 재기동)는 모두 이 잠금을 거친다 — 풀릴 때 화면에 상태 신호를 낸다.
    private fun lockFor(userId: Long): Mutex = userLocks.computeIfAbsent(userId) { NotifyingMutex(Mutex()) { stateChanged(userId) } }

    private fun stateChanged(userId: Long) = userEvents?.publish(userId, UserEventHub.Kind.STATE)

    private class NotifyingMutex(private val delegate: Mutex, private val onUnlock: () -> Unit) : Mutex by delegate {
        override fun unlock(owner: Any?) {
            delegate.unlock(owner)
            onUnlock()
        }
    }

    // DiscordErrorLogAppender 가 붙은 뒤에 복원해야 복원 중 에러가 Discord 로 간다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun restoreOnStartup() {
        if (!runtime.profile.autoStart) {
            restoreProgress = RestoreStatus.DISABLED
            log.info("Auto-start disabled. Skipping bot restoration.")
            return
        }
        restoreJob = scope.launch { restoreAllRunningBots() }
    }

    override fun start() {
        lifecycleRunning = true
    }

    override fun isRunning(): Boolean = lifecycleRunning

    override fun getPhase(): Int = runtime.lifecyclePhase

    /**
     * 복원을 취소하고 모든 엔진을 동시에 멈춘 뒤 저장하지 못한 pending·고점을 다시 저장한다.
     * 시간 제한을 넘기면 끊는다. 이때 저장된 pending 은 재시작 후 reconcile 되지만 저장하지 못한 매도는 잃는다.
     */
    override fun stop() {
        lifecycleRunning = false
        shuttingDown = true
        val budgetMs = runtime.stopBudget.toMillis()
        runBlocking {
            val completed = withTimeoutOrNull(budgetMs.milliseconds) {
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
                log.warn("Graceful shutdown: {}ms 예산 초과 — 일부 엔진 미완 정지(기록된 pending 은 재시작 후 reconcile, 기록하지 못한 매도는 유실될 수 있다)", budgetMs)
            }
        }
    }

    /** 정지 저장에 실패했던 사용자를 다시 저장한다. 못 쓰면 재시작 때 멈춘 봇이 다시 뜬다. */
    internal suspend fun saveUnpersistedStops() {
        for (userId in unpersistedStops.toList()) {
            val saved = try {
                withTimeoutOrNull(FINAL_STOP_SAVE_TIMEOUT_MS.milliseconds) {
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
        restoreProgress = RestoreStatus.IN_PROGRESS
        try {
            restoreWithRetries()
        } catch (e: CancellationException) {
            if (!shuttingDown) {
                log.error("봇 미복원: 복원이 취소로 중단됐다 — 복원되지 않은 봇이 있을 수 있다", e)
                abortRestore()
            }
            throw e
        } catch (e: Throwable) {
            log.error("봇 미복원: 예기치 않은 오류로 복원이 중단됐다 — 복원되지 않은 봇이 있을 수 있다", e)
            abortRestore()
            throw e
        }
        restoreProgress = RestoreStatus.DONE
    }

    private fun abortRestore() {
        restoreAborted = true
        restoreProgress = RestoreStatus.DONE
    }

    private suspend fun restoreWithRetries() {
        var pendingUserIds: List<Long> = emptyList()
        var lastQueryFailed = false
        for (attempt in 1..RESTORE_MAX_ATTEMPTS) {
            val states = try {
                botStateRepository.findByRunningTrueAndExchange(exchangeKey).collectList().awaitSingle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("restore: bot state 조회 실패 (attempt {}/{}): {}", attempt, RESTORE_MAX_ATTEMPTS, e.message)
                lastQueryFailed = true
                if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt).milliseconds)
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
            if (attempt < RESTORE_MAX_ATTEMPTS) delay(restoreBackoffMs(attempt).milliseconds)
        }
        // 조회가 끝까지 실패하면 pendingUserIds 가 비어 있어도 복원된 봇은 없다.
        if (lastQueryFailed) {
            restoreAborted = true
            log.error("봇 미복원: bot state 조회가 재시도 {}회 모두 실패 — 복원된 봇 없음", RESTORE_MAX_ATTEMPTS)
        } else if (pendingUserIds.isNotEmpty()) {
            restoreFailedUsers.addAll(pendingUserIds)
            // 마지막 시도와 이 줄 사이에 손으로 시작된 봇은 실패로 세지 않는다.
            restoreFailedUsers.removeIf { engines[it]?.isRunning() == true }
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
            val state = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
            if (state == null || !state.running) return@withLock true
            rememberEntriesPause(userId, state)
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock true
            if (!runtime.canRestore(user)) return@withLock true
            val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 복호화와 상태 로드가 실패할 수 있으므로 엔진 등록보다 먼저 한다. 복호화를 로드 뒤에 두면
            // 복호화 실패 때 정지 엔진을 바꾸지 않았는데도 그 매도를 버린다고 알린다.
            val decryptedUser = userSecretsService.decryptUserSecrets(user)
            val profile = profileFor(userId)
            val initialStates = loadInitialStates(userId)
            val engine = registerNewEngine(userId, decryptedUser, profile)
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
        if (running != null) {
            restoreFailedUsers.remove(userId)
            return@withLock respondToRunningEngine(userId, running, tickers)
        }
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")

        if (!runtime.canStart(user)) {
            return@withLock mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val profile = try {
            profileFor(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BotControlPersistFailedException(PARAMS_UNREADABLE_MESSAGE, e)
        }
        val initialStates = loadInitialStates(userId)
        // 엔진이 첫 매수를 보기 전에 저장된 중지를 읽는다. 읽지 못하면 다른 DB 실패처럼 시작하지 않는다.
        val savedRow = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
        rememberEntriesPause(userId, savedRow)
        val tickerList = tickers ?: tickersOf(savedRow) ?: runtime.profile.tickerList()
        val engine = registerNewEngine(userId, decryptedUser, profile)

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
        restoreFailedUsers.remove(userId)
        mapOf("status" to "started", "strategy" to strategy.name)
    }

    /** 화면에 보일 감시 목록 — 도는 엔진의 목록, 아니면 저장 행의 목록, 그것도 없으면 설정 기본 목록. */
    suspend fun savedTickers(userId: Long): List<String> {
        engines[userId]?.takeIf { it.isRunning() }?.let { return it.getUserTickers() }
        return storedTickers(userId) ?: runtime.profile.tickerList()
    }

    /**
     * 화면에서 고친 감시 목록을 저장한다. 도는 봇이면 엔진을 그 목록으로 바꾸고, 멈춘 봇이면 저장만 해 다음 시작이 그 목록으로 뜬다
     * (running 은 바꾸지 않는다).
     */
    suspend fun saveTickers(userId: Long, tickers: List<String>): Map<String, Any> = lockFor(userId).withLock {
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
        val running = engines[userId]?.takeIf { it.isRunning() }
        if (running != null) return@withLock respondToRunningEngine(userId, running, tickers)
        persistOrFail(userId, TICKERS_UNPERSISTED_MESSAGE) { saveStoppedTickers(userId, tickers) }
        mapOf("status" to "saved")
    }

    /** 화면에 보일 매매 값 — 사용자가 정한 항목과 env 기본. 읽기 실패는 던진다. */
    suspend fun savedParams(userId: Long): UserTradingParams = paramsService().find(userId, runtime.exchange)

    /** env 기본 값(사용자가 정하지 않은 항목이 따르는 값). */
    fun defaultProfile(): TradingProperties = runtime.profile

    /**
     * [params] 를 저장했을 때 보유 포지션이 받는 변화(전→후, 지금 가격으로 곧바로 걸리는 청산)와 그 확인 토큰. [priceOf] 는 `KRW-BTC` 의 현재가.
     */
    suspend fun previewParams(userId: Long, params: UserTradingParams, priceOf: (String) -> Double?): ParamsPreviewResult =
        lockFor(userId).withLock {
            val changes = changesFor(userId, params, priceOf)
            ParamsPreviewResult(changes, ParamsPreview.confirmToken(params, changes))
        }

    /** 저장을 막는 문제 — 편집 항목의 범위, 그리고 모든 매수가 건너뛰어지는 조합(손절가에서 최소 주문 미만). */
    fun paramsProblems(params: UserTradingParams): List<String> {
        val merged = params.applyTo(runtime.profile)
        val skip = buySkip(merged.maxInvestAmount, merged.maxLossPct, UpbitMarketRules.MIN_ORDER_KRW)
        return params.violations(runtime.profile) + listOfNotNull(
            skip?.let { "max_invest_amount × (1 − max_loss_pct/100) 가 최소 주문(${UpbitMarketRules.MIN_ORDER_KRW.toLong()}원) 미만이라 모든 매수가 건너뛰어진다" },
        )
    }

    /** 막지는 않는 조합 경고 — 엔진이 기동 때 남기는 것과 같은 규칙이다. 매매 값 API 는 업비트만 받는다. */
    fun paramsWarnings(params: UserTradingParams): List<String> =
        TradingEngine.startupConfigWarnings(params.applyTo(runtime.profile), UpbitMarketRules.MIN_ORDER_KRW).map { it.message }

    /**
     * 매매 값을 저장한다(빠진 항목은 env 기본 — 전체 교체). 보유 포지션의 청산 스냅샷이 바뀌면, 미리보기에서 받은 [confirmToken] 이
     * 지금 저장할 내용(값·포지션별 전→후)과 맞을 때만 저장하고 아니면 바뀌는 내용과 새 토큰을 돌려준다 — 손절을 좁히면 곧바로 시장가로
     * 팔리는데 되돌릴 수 없다. 도는 봇은 교체 경로로 새 값 엔진을 띄우고, 멈춘 봇은 값과 스냅샷만 저장한다.
     */
    suspend fun saveParams(userId: Long, params: UserTradingParams, confirmToken: String?, priceOf: (String) -> Double?): Map<String, Any> =
        lockFor(userId).withLock {
            if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
            val running = engines[userId]?.takeIf { it.isRunning() }
            // 멈춘 봇은 맵에 남은 정지 엔진의 미기록 상태를 먼저 남긴 뒤 그 상태로 판단한다 — 확인한 것과 덮을 것이 같은 상태다.
            val stoppedStates = if (running == null) persistOrFail(userId, PARAMS_NOT_SAVED_MESSAGE) { loadInitialStates(userId) } else null
            val changes = changesFor(params, running?.getStates()?.values ?: stoppedStates!!.values, priceOf)
            val token = ParamsPreview.confirmToken(params, changes)
            if (changes.isNotEmpty() && confirmToken != token) {
                return@withLock mapOf("status" to "confirm_required", "changes" to changes, "confirm_token" to token)
            }
            if (changes.isEmpty() && params == persistOrFail(userId, PARAMS_NOT_SAVED_MESSAGE) { paramsService().find(userId, runtime.exchange) }) {
                return@withLock mapOf("status" to "unchanged", "changes" to changes)
            }
            val rewrite = changes.mapTo(HashSet()) { it.ticker }
            if (stoppedStates != null) {
                // 덮은 뒤 정지 엔진이 옛 버전으로 저장하면 충돌하므로 맵에서 뺀다(다음 시작은 새 엔진을 만든다).
                engines[userId]?.takeIf { !it.isRunning() }?.let { engines.remove(userId, it) }
                val targets = stoppedStates.values.filter { it.ticker in rewrite }
                withContext(NonCancellable) {
                    persistOrFail(userId, PARAMS_NOT_SAVED_MESSAGE) {
                        paramsService().saveAndRewrite(userId, runtime.exchange, params, targets, params.applyTo(runtime.profile).exitParamsSnapshot())
                    }
                }
                return@withLock mapOf("status" to "saved", "changes" to changes)
            }
            try {
                reloadLocked(userId, newParams = params, rewriteTickers = rewrite)
            } catch (e: RuntimeReloadFailedException) {
                if (e.engineRestored) throw BotChangeFailedException(userId, PARAMS_NOT_APPLIED_MESSAGE, e)
                throw e
            }
            val now = engines[userId]?.takeIf { it.isRunning() }
            if (now != null && now !== running) {
                log.info("user {} 매매 값을 바꿨다: {} (보유 포지션 {}개 청산 값 갱신)", userId, params, changes.size)
                return@withLock mapOf("status" to "applied", "changes" to changes)
            }
            // 교체 엔진이 없다 — 종료가 끼어들었거나 교체가 일찍 돌아왔다. 값이 커밋됐는지로 문구를 가른다.
            val saved = runCatching { paramsService().find(userId, runtime.exchange) == params }.getOrDefault(false)
            throw BotChangeFailedException(
                userId,
                when {
                    saved -> PARAMS_SAVED_SHUTTING_DOWN_MESSAGE
                    shuttingDown -> PARAMS_SHUTTING_DOWN_NOT_SAVED_MESSAGE
                    else -> PARAMS_NOT_APPLIED_MESSAGE
                },
            )
        }

    private suspend fun changesFor(userId: Long, params: UserTradingParams, priceOf: (String) -> Double?): List<PositionChange> {
        val held = engines[userId]?.takeIf { it.isRunning() }?.getStates()?.values
            ?: tradingStateService.loadStates(userId, runtime.exchange).values
        return changesFor(params, held, priceOf)
    }

    private fun changesFor(params: UserTradingParams, states: Collection<TradingState>, priceOf: (String) -> Double?): List<PositionChange> {
        val after = params.applyTo(runtime.profile).exitParamsSnapshot()
        val today = UpbitTradingCalendar.tradingDate(Clock.systemUTC())
        return states.mapNotNull { state ->
            ParamsPreview.change(state, after, priceOf(state.ticker), state.buyDate?.let { UpbitTradingCalendar.tradingDaysBetween(it, today) })
        }
    }

    private suspend fun storedTickers(userId: Long): List<String>? =
        tickersOf(botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull())

    private fun tickersOf(row: BotStateEntity?): List<String>? =
        row?.tickers?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private suspend fun saveStoppedTickers(userId: Long, tickers: List<String>) {
        val existing = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
        val tickersStr = tickers.joinToString(",")
        if (existing != null) {
            if (existing.tickers == tickersStr) return
            botStateRepository.save(existing.copy(tickers = tickersStr, updatedAt = LocalDateTime.now())).awaitSingle()
        } else {
            botStateRepository.save(
                BotStateEntity(userId = userId, exchange = exchangeKey, running = false, strategy = strategy.name, tickers = tickersStr)
            ).awaitSingle()
        }
    }

    /**
     * 도는 엔진에 다른 목록으로 start 하면 새 목록을 먼저 저장하고 키 저장과 같은 교체 경로로 엔진을 새 목록으로 다시 띄운다.
     * 빠진 종목의 보유분은 새 엔진이 청산 대기로 관리한다. 목록이 같거나 없으면 저장 행을 엔진의 사용자 목록에 맞춘다.
     */
    private suspend fun respondToRunningEngine(
        userId: Long,
        engine: TradingEngine,
        tickers: List<String>?,
    ): Map<String, Any> {
        val current = engine.getUserTickers()
        if (tickers != null && normalizedTickers(tickers) != normalizedTickers(current)) {
            return changeTickersLocked(userId, current, tickers)
        }
        persistOrFail(userId, RUNNING_UNPERSISTED_MESSAGE) { saveRunningState(userId, current) }
        return mapOf("status" to "already_running", "strategy" to strategy.name)
    }

    /**
     * 저장을 먼저 한다 — 교체 뒤에 저장하다 실패하면 재시작 때 옛 목록으로 뜬다. 교체 뒤 새 목록으로 도는 엔진이 없으면(실패해 옛
     * 엔진으로 돌아감, 요청이 끊김, 사용자 없음) 저장도 옛 목록으로 되돌린다. 옛 엔진도 되살리지 못했거나 종료가 끼어들었으면 새 목록을
     * 둔다 — 재시작이 그 목록으로 복원한다.
     */
    private suspend fun changeTickersLocked(userId: Long, current: List<String>, tickers: List<String>): Map<String, Any> {
        // 보낸 저장 문이 취소 뒤에 커밋되면 되돌리기와 엇갈린다 — 저장은 끝까지 마친다.
        withContext(NonCancellable) { persistOrFail(userId, TICKERS_UNPERSISTED_MESSAGE) { saveRunningState(userId, tickers) } }
        try {
            reloadLocked(userId, tickers)
        } catch (e: Throwable) {
            withContext(NonCancellable) { revertIfOldEngineRuns(userId, current, tickers) }
            if (e is RuntimeReloadFailedException && e.engineRestored) throw BotChangeFailedException(userId, TICKERS_NOT_CHANGED_MESSAGE, e)
            throw e
        }
        val now = engines[userId]?.takeIf { it.isRunning() }?.getUserTickers()
        if (now != null && normalizedTickers(now) == normalizedTickers(tickers)) {
            log.info("user {} 감시 목록을 바꿨다: {} → {}", userId, current, tickers)
            return mapOf("status" to "tickers_changed", "strategy" to strategy.name)
        }
        if (shuttingDown) throw BotChangeFailedException(userId, TICKERS_SAVED_SHUTTING_DOWN_MESSAGE)
        withContext(NonCancellable) { revertIfOldEngineRuns(userId, current, tickers) }
        throw BotChangeFailedException(userId, TICKERS_NOT_CHANGED_MESSAGE)
    }

    private suspend fun revertIfOldEngineRuns(userId: Long, current: List<String>, tickers: List<String>) {
        val back = engines[userId]?.takeIf { it.isRunning() }?.getUserTickers() ?: return
        if (normalizedTickers(back) != normalizedTickers(current)) return
        runCatching { saveRunningState(userId, current) }.onFailure {
            log.error("user {} 목록 교체 실패 뒤 저장 목록을 되돌리지 못했다 — 재시작 때 {} 로 뜬다: {}", userId, tickers, it.message)
        }
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
            // 멈추기로 한 사용자는 복원 실패로 세지 않는다.
            restoreFailedUsers.remove(userId)
        }
        // 걸린 DB 호출이 lock 을 계속 쥐지 않게 시간 제한을 둔다. 실패하면 종료 때 다시 저장한다.
        persistOrFail(userId, STOP_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS.milliseconds) { markStopped(userId) } ?: throw IllegalStateException("bot_state 저장 시간 초과")
        }
        unpersistedStops.remove(userId)
        mapOf("status" to if (engine != null) "stopped" else "not_running")
    }

    /** 감시용. 맵에는 정지된 채 남은 엔진도 있어 도는 것만 낸다. */
    internal fun liveEngines(): Map<Long, TradingEngine> = engines.filterValues { it.isRunning() }

    /** 상태 조회 전에 부른다. 재시작 뒤 정지된 봇은 엔진이 없어 저장된 중지를 따로 읽어야 한다. */
    suspend fun loadEntriesPause(userId: Long) {
        if (entriesPauses.containsKey(userId)) return
        try {
            val row = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
            // lock 없이 읽으므로 그사이 저장된 새 값을 덮지 않게 빈 칸만 채운다.
            entriesPauses.putIfAbsent(userId, entriesPauseOf(row))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("user {} 의 신규 매수 중지 상태를 읽지 못했다: {}", userId, e.message)
        }
    }

    // 시작·복원은 엔진을 띄우기 전에 값을 채운다. 값이 없는 엔진은 채우는 경로를 빠뜨린 것이라 매수를 막는다.
    internal fun entriesPaused(userId: Long): Boolean = entriesPauses[userId]?.paused != false

    private fun rememberEntriesPause(userId: Long, row: BotStateEntity?) {
        entriesPauses[userId] = entriesPauseOf(row)
    }

    private fun entriesPauseOf(row: BotStateEntity?) = EntriesPause(row?.entriesPaused ?: false, row?.entriesPausedChangedAt)

    /**
     * 신규 매수만 멈추거나 다시 연다. 매도·reconcile 은 그대로 돈다. 이미 주문 단계에 들어간 매수는 끝까지 간다.
     * 저장이 끝난 뒤에만 엔진이 보는 값을 바꾼다 — 저장 없이 켜면 재시작 때 조용히 풀린다.
     */
    suspend fun setEntriesPaused(userId: Long, paused: Boolean): Map<String, Any?> = lockFor(userId).withLock {
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
        val saved = persistOrFail(userId, ENTRIES_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS.milliseconds) { saveEntriesPaused(userId, paused) } ?: throw IllegalStateException("bot_state 저장 시간 초과")
        }
        val previous = entriesPauses.put(userId, EntriesPause(saved.entriesPaused, saved.entriesPausedChangedAt))
        if (previous?.paused != paused) log.info("user {} 신규 매수 {} ({})", userId, if (paused) "중지" else "재개", exchangeKey)
        mapOf(
            "status" to if (paused) "entries_paused" else "entries_resumed",
            "entries_paused" to saved.entriesPaused,
            "entries_paused_changed_at" to saved.entriesPausedChangedAt?.toString(),
        )
    }

    /** 같은 값이면 쓰지 않아 바뀐 시각이 남는다. 행이 없으면 정지된 행을 만들어 다음 시작부터 적용한다. */
    private suspend fun saveEntriesPaused(userId: Long, paused: Boolean): BotStateEntity {
        val existing = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
        if (existing != null && existing.entriesPaused == paused) return existing
        val now = Instant.now()
        val row = existing?.copy(entriesPaused = paused, entriesPausedChangedAt = now, updatedAt = LocalDateTime.now())
            ?: BotStateEntity(
                userId = userId, exchange = exchangeKey, running = false, strategy = strategy.name,
                tickers = runtime.profile.tickers, entriesPaused = paused, entriesPausedChangedAt = now,
            )
        return botStateRepository.save(row).awaitSingle()
    }

    fun getStatus(userId: Long): Map<String, Any?> {
        val engine = engines[userId]
        val pause = entriesPauses[userId]
        // 맵에는 정지된 채 남은 엔진이 있을 수 있으므로 목록은 도는 엔진에서만 읽는다.
        val live = engine?.takeIf { it.isRunning() }
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            // running 은 시작·정지 상태다. 루프가 정지 없이 끝났거나 멈췄는지는 이것으로 본다.
            "loop" to mapOf<String, Any?>(
                "alive" to (live?.isLoopAlive() ?: false),
                "last_progress_at" to live?.lastProgressAt()?.toString(),
            ),
            "strategy" to strategy.name,
            // states 는 작업자가 돌아야 채워지므로 start 직후에도 값이 있는 활성 목록(getActiveTickers)을 쓴다.
            "tickers" to (engine?.getActiveTickers() ?: emptyList<String>()),
            "user_tickers" to (live?.getUserTickers() ?: emptyList<String>()),
            "entry_tickers" to (live?.getEntryTickers() ?: emptyList<String>()),
            "exit_only_tickers" to (live?.getExitOnlyTickers() ?: emptyList<String>()),
            "default_tickers" to runtime.profile.tickerList(),
            "positions" to (engine?.getStates()?.map { (ticker, state) ->
                mapOf(
                    "ticker" to ticker,
                    "position" to state.position,
                    "avg_buy_price" to state.avgBuyPrice,
                    "hold_volume" to state.holdVolume,
                    "bought_today" to state.boughtToday,
                    "unsynced" to state.unsynced,
                    // 버전 충돌은 정지 뒤 시작으로만 풀린다. 그때까지 신규 매수를 막는다.
                    "version_conflict" to state.versionConflict,
                    // 확인 조회가 연속으로 실패한 횟수와 다음 조회 시각. 실패할수록 간격이 늘고(상한 5분) 조회가 성공하면 풀린다.
                    "lookup_failures" to state.lookupFailures,
                    "next_lookup_at" to state.nextLookupAt?.toString(),
                    // 미해소 주문이 있으면 그 티커는 매매를 평가하지 않는다. 대사로 확정되면 풀린다.
                    "pending_buy" to state.pendingRef(TradeSide.BUY)?.let {
                        mapOf("by" to it.by, "ref" to it.ref, "since" to state.pendingBuySince?.toString())
                    },
                    "pending_sell" to state.pendingRef(TradeSide.SELL)?.let {
                        mapOf("by" to it.by, "ref" to it.ref, "since" to state.pendingSellSince?.toString())
                    },
                )
            } ?: emptyList<Map<String, Any?>>()),
            // 저장된 값을 읽지 못했으면 null 이다.
            "entries_paused" to pause?.paused,
            "entries_paused_changed_at" to pause?.changedAt?.toString(),
        )
    }

    /** [runtime] 과 무관하게 Upbit 클라이언트다. 거래소별 조회는 [queryClient] 를 쓴다. */
    fun createUpbitClient(user: UserEntity): UpbitClient = upbitRuntime.queryClient(user)

    fun queryClient(user: UserEntity): ExchangeClient = runtime.queryClient(user)

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

    /**
     * [newTickers] 가 있으면 교체 엔진을 그 목록으로 띄운다(실패해 되살린 옛 엔진은 옛 목록 그대로). [newParams] 가 있으면 상태를
     * 읽은 뒤 그 값과 보유 포지션 청산 스냅샷을 한 트랜잭션으로 저장하고 그 값으로 띄운다 — 커밋 전 실패면 옛 엔진이 옛 값으로 돌아온다.
     */
    private suspend fun reloadLocked(
        userId: Long,
        newTickers: List<String>? = null,
        newParams: UserTradingParams? = null,
        rewriteTickers: Set<String> = emptySet(),
    ) {
        if (shuttingDown) return
        val existing = engines[userId] ?: return
        // 사용자 값도 엔진을 멈추기 전에 읽는다. 읽지 못하면 env 로 내려가지 않고 교체하지 않는다 — 낮춰 둔 한도가 풀리면 안 된다.
        val (decryptedUser, profile) = try {
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return
            userSecretsService.decryptUserSecrets(user) to (newParams?.applyTo(runtime.profile) ?: profileFor(userId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 옛 엔진은 아직 이전 설정으로 돌고 있다. 원래 예외를 올리면 저장 실패로 읽힌다.
            if (existing.isRunning()) throw RuntimeReloadFailedException(userId, e, engineRestored = true)
            log.warn("reload: user {} 정지 엔진의 설정을 읽지 못해 그대로 둔다 — 다음 시작이 저장된 설정을 읽는다: {}", userId, e.message)
            return
        }
        val wasRunning = existing.isRunning()
        var committed: Map<String, TradingState>? = null
        // 활성 목록(getActiveTickers)을 넘기면 목록에서 빠진 티커가 새 엔진의 진입 대상이 된다.
        val tickers = newTickers ?: existing.getUserTickers()
        // 요청이 끊겨도 루프 join 까지 기다린다. join 전에 돌아가면 옛 루프와 새 루프가 겹친다.
        withContext(NonCancellable) { existing.stop() }
        if (leftToShutdown(userId)) return
        // stop 뒤에 읽어야 마지막 tick 의 주문까지 잡힌다. 매도 기록을 끝내 남기지 못하면 새 엔진은 그 주문을 모르므로
        // 교체하지 않고 옛 엔진으로 돌아간다.
        val initialStates = if (wasRunning) {
            try {
                val unrecorded = flushStoppedEngine(userId, existing)
                check(unrecorded.isEmpty()) { "매도 주문 기록을 DB 에 남기지 못함: ${sellRefs(unrecorded)}" }
                tradingStateService.loadStates(userId, runtime.exchange).also { states ->
                    if (newParams != null) {
                        val targets = states.values.filter { it.ticker in rewriteTickers && it.exitParams != null && !it.hasPendingSell() }
                        withContext(NonCancellable) {
                            paramsService().saveAndRewrite(userId, runtime.exchange, newParams, targets, profile.exitParamsSnapshot())
                        }
                        committed = states
                    }
                }
            } catch (e: CancellationException) {
                // 커밋된 뒤라면 옛 엔진을 되살리지 않는다 — 옛 버전·옛 값을 들고 있어 저장이 충돌한다. 새 값 엔진을 띄운다.
                val states = committed
                if (states != null && !leftToShutdown(userId)) {
                    withContext(NonCancellable) {
                        val replacement = createEngine(decryptedUser, profile)
                        engines[userId] = replacement
                        replacement.start(tickers, states)
                        stopIfShutdownStarted(userId, replacement)
                    }
                    throw e
                }
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
        val replacement = createEngine(decryptedUser, profile)
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

    internal fun createEngine(user: UserEntity, profile: TradingProperties = runtime.profile): TradingEngine {
        // Upbit 엔진 클라이언트는 createUpbitClient 로 만들어야 그 메서드를 stub 한 spy 가 엔진 클라이언트도 바꾼다.
        val parts = if (runtime === upbitRuntime) upbitRuntime.partsFor(createUpbitClient(user)) else runtime.partsFor(user)
        // 결과를 모르는 주문은 identifier 조회로만 확정한다. 그 조회가 없는 거래소는 확정할 길이 없어 엔진을 만들지 않는다.
        check(parts.unknownOrderPolicy.supportsClientOrderId) {
            "${parts.displayName} 는 identifier 로 주문을 조회하지 못해 결과를 모르는 주문을 확정할 수 없다 — 엔진을 만들지 않는다"
        }
        val client = parts.client
        // 상태 저장·거래 기록·체결 알림 이벤트는 한 트랜잭션으로 커밋하고, 알림은 발송기가 커밋 뒤에 보낸다.
        val userId = user.id!!
        // 엔진과 포지션 관리가 같은 시계로 시세 나이·조회 백오프·막힘 알림을 잰다.
        val clock = Clock.systemUTC()
        val positionManager = PositionManager(
            client, profile, tradingStateService, userId,
            commitFill = { persistState, record ->
                tradeExecutionService.commitFill(persistState, record)
            },
            rules = parts.rules,
            calendar = parts.calendar,
            orderGate = parts.orderGate,
            fillPoll = parts.fillPoll,
            fillAccounting = parts.fillAccounting,
            errorClassifier = parts.errorClassifier,
            unknownOrderPolicy = parts.unknownOrderPolicy,
            displayName = parts.displayName,
            entriesPaused = { entriesPaused(userId) },
            clock = clock,
            lifecycle = tradeExecutionService.orderLifecycle,
            stateSaved = { stateChanged(userId) },
        )
        val dailyResetManager = DailyResetManager(profile, userId = user.id!!, calendar = parts.calendar)

        return TradingEngine(
            upbitClient = client,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            strategy = strategy,
            tradingProperties = profile,
            userId = user.id!!,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
            marketDataStore = parts.marketDataStore,
            exchange = parts.exchange,
            dailyCandleCache = parts.dailyCandleCache,
            shadowExitObserver = shadowExitObserver(user.id!!),
            clock = clock,
            calendar = parts.calendar,
            rules = parts.rules,
            priceEvents = priceEventsProperties,
            statusChanged = { stateChanged(userId) },
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
        val existing = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull()
        val tickersStr = tickers.joinToString(",").ifEmpty {
            existing?.tickers ?: runtime.profile.tickers
        }
        if (existing != null) {
            if (existing.running && existing.strategy == strategy.name && existing.tickers == tickersStr) return
            botStateRepository.save(
                existing.copy(running = true, strategy = strategy.name, tickers = tickersStr, updatedAt = LocalDateTime.now())
            ).awaitSingle()
        } else {
            botStateRepository.save(
                BotStateEntity(userId = userId, exchange = exchangeKey, running = true, strategy = strategy.name, tickers = tickersStr)
            ).awaitSingle()
        }
    }

    private suspend fun markStopped(userId: Long) {
        val existing = botStateRepository.findByUserIdAndExchange(userId, exchangeKey).awaitSingleOrNull() ?: return
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
        if (withTimeoutOrNull(FLUSH_TIMEOUT_MS.milliseconds) { engine.flushUnpersisted() } == null) {
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
        val loaded = tradingStateService.loadStates(userId, runtime.exchange)
        alertDroppedSells(userId, unrecorded)
        return loaded
    }

    /**
     * 맵에 남은 정지 엔진은 이전 자격증명으로 만들어졌을 수 있어 재사용하지 않는다. [loadInitialStates] 뒤에 부른다.
     */
    private fun registerNewEngine(userId: Long, user: UserEntity, profile: TradingProperties): TradingEngine =
        createEngine(user, profile).also { engines[userId] = it }

    /** env 설정 위에 사용자 값을 덮은 엔진 설정. 읽기 실패는 던진다. */
    private suspend fun profileFor(userId: Long): TradingProperties {
        val params = userTradingParams?.find(userId, runtime.exchange) ?: UserTradingParams.NONE
        if (params != UserTradingParams.NONE) log.info("user {} 매매 값(사용자 지정): {}", userId, params)
        return params.applyTo(runtime.profile)
    }

    private fun paramsService(): UserTradingParamsService = checkNotNull(userTradingParams) { "사용자 매매 값 저장소가 없다" }

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
        private const val RESTORE_MAX_ATTEMPTS = 5

        private const val PARAMS_UNREADABLE_MESSAGE =
            "저장된 매매 값을 읽지 못해 봇을 시작하지 않았습니다. 잠시 후 다시 시도하세요."
        internal const val PARAMS_NOT_APPLIED_MESSAGE =
            "매매 값을 바꾸지 못해 이전 값으로 계속 거래 중입니다. 잠시 후 다시 시도하세요."
        internal const val PARAMS_SAVED_SHUTTING_DOWN_MESSAGE =
            "서버가 종료 중이라 매매 값은 저장만 했습니다 — 재시작 뒤 새 값으로 거래합니다."
        internal const val PARAMS_SHUTTING_DOWN_NOT_SAVED_MESSAGE =
            "서버가 종료 중이라 매매 값을 저장하지 못했습니다. 재시작 뒤 다시 저장하세요."
        internal const val PARAMS_NOT_SAVED_MESSAGE =
            "매매 값을 저장하지 못했습니다. 잠시 후 다시 시도하세요."
        private const val TICKERS_UNPERSISTED_MESSAGE =
            "감시 목록을 저장하지 못해 바꾸지 않았습니다. 잠시 후 다시 시도하세요."
        internal const val TICKERS_NOT_CHANGED_MESSAGE =
            "감시 목록을 바꾸지 못해 이전 목록으로 계속 거래 중입니다. 잠시 후 다시 시도하세요."
        private const val TICKERS_SAVED_SHUTTING_DOWN_MESSAGE =
            "서버가 종료 중이라 감시 목록은 저장만 했습니다 — 재시작 뒤 새 목록으로 거래합니다."
        private const val ENTRIES_UNPERSISTED_MESSAGE =
            "신규 매수 중지 설정을 저장하지 못해 바꾸지 않았습니다. 잠시 후 다시 시도하세요."

        // 저장하지 않은 "Service is shutting down" 과 달리 재시작 때 봇이 다시 뜬다.
        internal const val START_SAVED_SHUTTING_DOWN_MESSAGE =
            "Service is shutting down — the start is saved and the bot resumes after the restart"
        private const val FINAL_STOP_SAVE_TIMEOUT_MS = 5_000L
        private const val STOP_SAVE_TIMEOUT_MS = 10_000L
        private const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
