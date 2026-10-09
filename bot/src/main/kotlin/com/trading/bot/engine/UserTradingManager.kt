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
    // null 이면 그림자 관측을 만들지 않는다 — 저장소 없이 켜면 매 tick 관측이 조용히 버려진다.
    private val shadowExitObservationRepository: ShadowExitObservationRepository? = null,
    // null 이면 보고를 건너뛴다 — 이 클래스를 직접 만드는 테스트가 많아 기본값을 둔다.
    private val exitParamsDeclarationCheck: ExitParamsDeclarationCheck? = null,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, TradingEngine>()
    // userId 별 Mutex 로 start/stop/reload 의 engines mutate 를 직렬화.
    // CAS (remove(k,v) / replace(k,old,new)) 만으로는 엔진 등록 직후 start() 호출 전에
    // stop 이 끼어드는 race window 를 닫지 못함.
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    // 정지는 됐는데 bot_state.running=false 를 쓰지 못한 사용자 — 이 집합이 DB 행보다 우선하고, 여기 있는 사용자에게는 엔진이 없다.
    // 복원은 건너뛰고, 시작이 저장을 마치면 빠지며, 정상 종료 때 저장을 한 번 더 시도한다.
    private val unpersistedStops: MutableSet<Long> = ConcurrentHashMap.newKeySet()
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
     * 완주를 기다린 뒤 루프가 기록하지 못한 pending·고점을 한 번 더 남긴다(매도가 남으면 ERROR). NonCancellable 후처리가 예산을
     * 넘기면 self-bound(withTimeoutOrNull)로 끊고 — 잔여 daemon 코루틴은 JVM 종료로 정리되고 — 기록된 pending 은 재시작 후
     * durable reconcile(#20) 이 잇지만 기록하지 못한 매도는 유실된다. 정지 저장이 확인되지 않은 사용자는 엔진 정지와 동시에
     * 한 번 더 저장한다([saveUnpersistedStops]).
     */
    override fun stop() {
        lifecycleRunning = false
        shuttingDown = true
        runBlocking {
            val completed = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                restoreJob?.cancelAndJoin()
                // 정지 저장이 실패한 사용자에게는 엔진이 없어 엔진 정지와 서로 기다릴 것이 없다 — 동시에 돌려 걸린 저장이
                // 엔진 정지(tick 주문 후처리 대기)의 예산을 먹지 않게 한다.
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

    /** 정상 종료 때 정지 저장이 확인되지 않은 사용자를 한 번 더 저장한다 — 못 쓰면 재시작 때 멈춘 봇이 다시 뜬다. */
    internal suspend fun saveUnpersistedStops() {
        for (userId in unpersistedStops.toList()) {
            val saved = try {
                withTimeoutOrNull(FINAL_STOP_SAVE_TIMEOUT_MS) {
                    lockFor(userId).withLock {
                        // 스냅샷 뒤에 시작이 저장을 마쳐 집합에서 빠졌으면 도는 봇의 행을 running=false 로 덮으면 안 된다.
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

    /** running bot 을 복원하되, 일시적 실패(DB/API)는 유한 backoff 로 재시도한다. ERROR alert(→Discord)는 최종 실패와 종료 밖의 취소에만 낸다. */
    internal suspend fun restoreAllRunningBots() {
        try {
            restoreWithRetries()
        } catch (e: CancellationException) {
            // 종료가 취소한 복원은 실패가 아니다. 그 밖의 취소는 남은 재시도 없이 복원을 끝내므로 알린다.
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
     * 판정은 lock 안에서 다시 읽은 행으로 한다 — 조회(후보 목록)와 lock 획득 사이에 정지가 끼면 옛 행으로 실자금 엔진이 뜬다.
     * 반환: true=복원 완료 또는 재시도 무의미(유저/키 없음·이미 개입·정지됨), false=일시적 실패(재시도 대상).
     */
    private suspend fun restoreOne(userId: Long): Boolean = lockFor(userId).withLock {
        if (shuttingDown) return@withLock true // 종료 중 — 신규 엔진 기동 안 함(M5)
        // containsKey 가 아니라 isRunning — 기동 전에 실패해 map 에 남은 엔진은 재시도 대상이어야 한다.
        if (engines[userId]?.isRunning() == true) return@withLock true
        // 정지는 됐지만 저장하지 못한 사용자 — 행은 아직 running=true 여도 멈춘 상태가 우선이다.
        if (userId in unpersistedStops) return@withLock true
        try {
            val state = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
            if (state == null || !state.running) return@withLock true
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock true
            if (user.upbitAccessKey.isNullOrBlank()) return@withLock true
            val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 복호화도 로드 앞(startBot 과 같다) — 로드 뒤에 실패하면 맵의 정지 엔진을 바꾸지 않았는데 그 매도를 버린다고 알린다.
            val decryptedUser = userSecretsService.decryptUserSecrets(user)
            // durable 상태 로드를 엔진 등록보다 먼저 — 여기서 터지면 새 엔진을 등록하지 않는다.
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
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down") // 종료 중 신규 엔진 기동 차단(M5 일관)
        // 도는 엔진의 start 는 no-op 이라 목록이 바뀌지 않는다 — 부작용(저장) 전에 판정해야 응답이 실제 상태와 맞는다(#226).
        val running = engines[userId]?.takeIf { it.isRunning() }
        if (running != null) return@withLock respondToRunningEngine(userId, running, tickers)
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")

        if (user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            return@withLock mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        // restoreOne 과 같은 이유로 durable 로드를 엔진 등록 앞에 둔다(실패 시 새로 만든 엔진이 기동되지 않은 채 남지 않게).
        val initialStates = loadInitialStates(userId)
        val engine = registerNewEngine(userId, decryptedUser)

        val tickerList = tickers ?: tradingProperties.tickerList()
        exitParamsDeclarationCheck?.report("수동 기동 user=$userId")
        // 저장은 기동 바로 앞, 실패할 수 있는 마지막 단계 — 기동 뒤에 저장하면 실패해도 봇이 돌고 재시작 때 복원되지 않는다.
        try {
            persistOrFail(userId, START_UNPERSISTED_MESSAGE) { saveRunningState(userId, tickerList) }
        } catch (e: BotControlPersistFailedException) {
            engines.remove(userId, engine) // 기동하지 않은 엔진을 남기지 않는다(restoreOne·reload 선례)
            throw e
        }
        unpersistedStops.remove(userId)
        // 맨 앞 확인 뒤 조회·저장 중에 종료가 시작됐을 수 있다 — 루프를 띄우지 않는다. 기동 뒤에도 한 번 더 본다: 그 사이 종료가
        // 맵을 보고 지나갔을 수 있다. 어느 쪽이든 저장한 running=true 는 두어 재시작 복원이 이 시작을 잇는다.
        if (shuttingDown) return@withLock mapOf("error" to START_SAVED_SHUTTING_DOWN_MESSAGE)
        engine.start(tickerList, initialStates)
        if (stopIfShutdownStarted(userId, engine)) return@withLock mapOf("error" to START_SAVED_SHUTTING_DOWN_MESSAGE)
        mapOf("status" to "started", "strategy" to strategy.name)
    }

    /**
     * 이미 도는 엔진에 start 가 온 경우. 다른 목록이면 아무것도 바꾸지 않고 거절한다 — 저장만 하고 started 로 답하면 엔진은
     * 옛 목록으로 계속 돌다가 다음 재시작 때 새 목록이 조용히 적용된다. 목록이 없거나 같으면 저장 행을 running·엔진의 사용자
     * 목록으로 맞춘다(설정 목록으로 덮지 않는다) — 저장이 실패하면 재시작 복원이 어긋날 수 있어 503 으로 알린다.
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
     * 엔진을 멈추고 bot_state 의 running 을 내린다. 저장이 실패해도 엔진은 멈춘다 — 운영자가 누른 정지를 DB 장애가 막으면
     * 안 된다. 엔진이 없어도 행이 running 이면 내린다(복원이 실패해 엔진 없이 DB 만 running 인 경우 — 두면 재시작 때 복원된다).
     * 저장이 확인될 때까지 사용자는 [unpersistedStops] 에 있다 — 저장이 실패하든 걸리든 요청이 끊기든 종료 저장·복원이 이 정지를 안다.
     */
    suspend fun stopBot(userId: Long): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId]
        // 정지와 표시까지는 요청이 취소돼도 마친다 — 엔진만 멈추고 표시가 빠지면 재시작 때 이 봇이 다시 뜬다(reload 의 NonCancellable 선례).
        withContext(NonCancellable) {
            if (engine != null) {
                engine.stop()
                engines.remove(userId)
            }
            unpersistedStops.add(userId)
            // 정지·표시를 확정한 뒤에 기록한다(결과에 달리지 않게). 엔진은 이제 맵에 없어 여기서 끊기면 미기록 매도가 메모리와 함께 사라진다.
            if (engine != null) flushOrAlert(userId, engine)
        }
        // 저장은 취소·상한이 있게 둔다 — 걸린 DB 호출이 이 사용자의 lock 을 무기한 쥐지 않게. 실패·시간 초과면 표시가 남아 종료 저장이 이어받는다.
        persistOrFail(userId, STOP_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS) { markStopped(userId) } ?: throw IllegalStateException("bot_state 저장 시간 초과")
        }
        unpersistedStops.remove(userId)
        mapOf("status" to if (engine != null) "stopped" else "not_running")
    }

    fun getStatus(userId: Long): Map<String, Any> {
        val engine = engines[userId]
        // 맵에는 기동 전에 실패했거나 정지된 채 남은 엔진이 있을 수 있다 — 그 옛 목록을 실행 중인 것처럼 보이지 않는다.
        val live = engine?.takeIf { it.isRunning() }
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            // 전략은 주입된 하나뿐이라 엔진이 없어도 다음 시작이 쓸 전략과 같다.
            "strategy" to strategy.name,
            // engine.getActiveTickers() is set synchronously by start();
            // states keys only populate once the background loop initializes
            // them, so reading from states here would briefly return [] right
            // after /api/bot/start.
            "tickers" to (engine?.getActiveTickers() ?: emptyList<String>()),
            // 화면용 분류 — 활성 집합(tickers)은 사용자 목록·청산 대기가 섞인 파생 집합이라, 뺀 티커가 그대로 보이면
            // 목록이 적용되지 않은 것으로 읽힌다(#226). default_tickers 는 목록 없이 시작할 때 쓰는 설정 목록이다.
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
                    // 보유 여부 미확정으로 매수가 막힌 상태 — 로그를 안 보고도 원인을 알 수 있게 노출한다.
                    "unsynced" to state.unsynced,
                    // 미해소 주문이 있으면 그 티커의 매매 평가가 멈춘다(#246) — 해제하려면 정지 뒤 /api/bot/pending/clear.
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
     * #246: 자동으로 풀리지 않는 pending(흔적 있는 identifier-only 주문, 오래 막힌 매도)을 사람이 거래소를 확인한 뒤 지운다.
     * **정지된 봇에서만** 받는다 — 도는 엔진은 매 tick 그 티커를 reconcile 하므로 같은 state 를 동시에 고치게 된다. 엔진이 없어도
     * 저장 행이 running 이면(복원 재시도·자동 시작 대기) 곧 기동되므로 거절한다. 다음 시작의 잔고 동기화가 보유를 다시 맞춘다.
     */
    suspend fun clearPending(userId: Long, ticker: String, side: TradeSide): Map<String, Any> = lockFor(userId).withLock {
        // 종료의 기록(lock 밖)과 겹치면 해제 전 메모리 값으로 되쓸 수 있다.
        if (shuttingDown) return@withLock mapOf("error" to "Service is shutting down")
        val engine = engines[userId]
        val running = engine?.isRunning() == true || (
            userId !in unpersistedStops &&
                botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()?.running == true
            )
        if (running) {
            return@withLock mapOf("error" to "Stop the bot before clearing a pending order", "code" to CONFLICT_CODE)
        }
        // 맵에 남은 정지 엔진의 미기록 행을 먼저 남긴다 — 남겨 둔 채 해제하면 다음 시작의 기록이 메모리 값으로 해제를 되돌린다.
        // 그래도 DB 에 없을 수 있는 매도가 남으면 해제하지 않는다: 엔진을 버리면 그 주문의 유일한 기록이 사라지고, 남겨 두면
        // 다음 시도·시작이 다시 기록한다(loadInitialStates 와 같은 규칙).
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
        // 지운 값의 유일한 기록이다 — 저장 뒤에 남기면 저장은 됐는데 요청이 끊긴 경우 사라진다. 체결됐다면 이 값으로 거래 기록을 맞춘다.
        log.warn("Clearing pending {} {} ({} {}) by hand for user {} — fields: {}", side, ticker, ref.by, ref.ref, userId, cleared)
        state.releasePending(side, TradingDay.of(LocalDateTime.now(TradingDay.KST)))
        persistOrFail(userId, PENDING_CLEAR_UNPERSISTED_MESSAGE) {
            withTimeoutOrNull(STOP_SAVE_TIMEOUT_MS) { tradingStateService.upsert(userId, state) }
                ?: throw IllegalStateException("trading_states 저장 시간 초과")
        }
        log.info("Pending {} {} cleared for user {}", side, ticker, userId)
        mapOf("status" to "cleared", "ticker" to ticker, "side" to side.name.lowercase(), "cleared" to cleared)
    }

    /** #19: halt 된 ticker 수동 해제 — 다음 tick 부터 reconcile/매매 재개. */
    suspend fun clearHalt(userId: Long, ticker: String): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId] ?: return@withLock mapOf("status" to "not_running")
        // durable 반영이 실패하면 엔진이 메모리 해제를 되돌린다 — 해제되지 않았음을 그대로 알린다(재시도는 사용자 몫).
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

    /** 키·웹훅 저장 뒤 엔진을 저장된 설정으로 바꾼다. 끊긴 요청에는 503 이 가지 않으므로, 교체 전에 끊겨 이전 설정 엔진이 돌 수 있으면 여기서 알린다. */
    suspend fun reloadUserRuntime(userId: Long) {
        var locked = false
        try {
            lockFor(userId).withLock {
                locked = true
                reloadLocked(userId)
            }
        } catch (e: CancellationException) {
            // catch 가 withLock 밖에 있어야 lock 대기의 취소를 받는다. 그때는 lock 을 쥔 요청(저장 전에 사용자를 읽은 시작·복원·reload)이
            // 뒤이어 이전 설정으로 엔진을 기동할 수 있어 지금 도는 엔진이 없어도 알린다. lock 을 쥔 뒤의 취소는 교체 전이다(교체에는 중단
            // 지점이 없다) — 도는 엔진이 남았을 때만 알리고, 정지 엔진이면 다음 시작·복원이 저장된 설정으로 새 엔진을 만든다. lock 을 놓은
            // 뒤 판단해 그 사이 다른 요청이 새 설정으로 바꿨을 수도 있으므로 가능성으로 알린다(다시 저장은 무해하다).
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
        if (shuttingDown) return // 종료 중 — 엔진 교체·재기동 안 함(M5 일관)
        val existing = engines[userId] ?: return
        val decryptedUser = try {
            val user = userRepository.findById(userId).awaitSingleOrNull() ?: return
            userSecretsService.decryptUserSecrets(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 옛 엔진은 아직 멈추지 않았다 — 저장은 됐는데 반영만 못 했고 이전 설정으로 계속 돈다. 원래 예외(500)로 올리면 저장
            // 실패로 읽힌다(#283). 도는 엔진이 없으면 반영할 곳이 없다 — 다음 시작이 저장된 설정을 읽는다.
            if (existing.isRunning()) throw RuntimeReloadFailedException(userId, e, engineRestored = true)
            log.warn("reload: user {} 정지 엔진의 설정을 읽지 못해 그대로 둔다 — 다음 시작이 저장된 설정을 읽는다: {}", userId, e.message)
            return
        }
        val wasRunning = existing.isRunning()
        // 사용자 목록 그대로 — 활성 집합(잔류 포함)을 넘기면 잔류 티커가 새 엔진의 신규 진입 대상이 되고,
        // 빈 목록을 설정 목록으로 바꾸면 신규 진입 대상이 조용히 생긴다(#226).
        val tickers = existing.getUserTickers()
        // 요청이 끊겨도 루프 join 까지 기다린다 — 취소가 여기서 새면 정지된 엔진만 남고(#262), join 전에 복귀하면 옛 루프의
        // 꼬리와 되살린 루프가 겹친다. 도는 엔진이었으면 취소는 아래 첫 취소 확인(기록의 시간 상한)에서 드러나 복귀 경로를 탄다.
        withContext(NonCancellable) { existing.stop() }
        if (leftToShutdown(userId)) return
        // stop 이후에 읽어야 마지막 tick 의 기록까지 잡힌다 — 먼저 읽으면 그 사이 발생한 주문이 스냅샷에서 빠져 orphan pending
        // 이 된다(#20). tick 안에서 기록하지 못한 매도는 flush 가 한 번 더 남기고, 그래도 못 남기면 새 엔진은 DB 에 없는 그
        // 주문을 모르므로 교체하지 않고 아래 복귀 경로를 탄다 — 옛 엔진이 메모리의 그 주문을 다음 tick 에 다시 기록한다.
        val initialStates = if (wasRunning) {
            try {
                val unrecorded = flushStoppedEngine(userId, existing)
                check(unrecorded.isEmpty()) { "매도 주문 기록을 DB 에 남기지 못함: ${sellRefs(unrecorded)}" }
                tradingStateService.loadStates(userId)
            } catch (e: CancellationException) {
                // 취소여도 stop() 은 이미 일어났다 — 복구를 건너뛰면 정지된 엔진이 남아 손절이
                // 무기한 멈추고, 이후 reload 는 wasRunning=false 로 보아 되살리지도 않는다.
                // 복구는 취소에 영향받지 않도록 NonCancellable 로 돌린 뒤 취소를 재전파한다(미반영은 [reloadUserRuntime] 이 알린다).
                if (!leftToShutdown(userId)) {
                    withContext(NonCancellable) {
                        // 직전 사용자 목록·메모리 상태 그대로 — 빈 상태로 되살리면 목록 밖 잔류 포지션의 청산 관리가 끊긴다.
                        runCatching { existing.resume() }
                            .onFailure { log.error("reload: user {} 취소 중 기존 엔진 복귀 실패 — 엔진 정지 상태", userId, it) }
                        stopIfShutdownStarted(userId, existing)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (leftToShutdown(userId, e)) return
                // 교체 실패는 정지 의도가 아니다 — 여기서 포기하면 stop 된 엔진만 남아 보유 포지션의 손절이
                // 무기한 중단된다(무증상). 옛 엔진을 원래 상태로 되살린다.
                log.error("reload: user {} 새 엔진에 넘길 상태를 준비하지 못함 — 기존 엔진으로 복귀: {}", userId, e.message, e)
                try {
                    existing.resume()
                } catch (restoreFailure: CancellationException) {
                    throw restoreFailure
                } catch (restoreFailure: Exception) {
                    // 되살리기마저 실패 — 엔진이 정지된 채 남는다. "이전 설정으로 거래 중" 과 정반대
                    // 상황이라 호출자가 다른 문구를 쓰도록 구분해 알린다.
                    log.error("reload: user {} 기존 엔진 복귀 실패 — 엔진이 정지 상태로 남는다", userId, restoreFailure)
                    // 그 매도를 알리면 사람이 DB 를 맞추므로 엔진은 알린 자리에서 버린다 — 맵에 남겨 두면 다음 시작·정지가 이 엔진의
                    // 메모리 값으로 맞춘 DB 를 되쓴다.
                    alertDroppedSells(userId, existing.unpersistedSells())
                    engines.remove(userId, existing)
                    restoreFailure.addSuppressed(e)
                    throw RuntimeReloadFailedException(userId, restoreFailure, engineRestored = false)
                }
                if (stopIfShutdownStarted(userId, existing)) return
                // 되살린 엔진은 교체 전 자격증명·webhook 을 그대로 쓴다. 조용히 반환하면 호출자가
                // 키 교체를 성공으로 응답해, 사용자는 이전 계정에서 계속 주문되는 것을 모른다(#51).
                // 재기동을 마친 뒤에 던져야 손절 연속성이 유지된다.
                throw RuntimeReloadFailedException(userId, e, engineRestored = true)
            }
        } else {
            // 맵에 남은 정지 엔진(취소된 reload 의 복귀 실패 등) — 교체하면 그 states 가 버려진다.
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
     * 옛 엔진을 멈춘 뒤 되살리거나 교체하기 전에 종료가 시작됐으면, 옛 엔진을 맵에 정지 상태로 두고 종료에 맡긴다(#262, #263).
     * 종료는 플래그를 세운 뒤 맵의 엔진을 멈추고 기록하므로, 그 뒤에 되살리거나 교체한 엔진은 아무도 멈추지 않는다.
     * 종료는 웹 서버가 진행 중 요청을 먼저 끝낸(phase 순서) 뒤에 시작하므로 여기 닿는 것은 그 대기를 넘긴 reload 뿐이다.
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
     * 엔진을 기동한 뒤(reload 의 되살리기·교체, startBot) 종료가 시작됐는지 다시 본다 — 기동 전 확인과 기동 사이에 종료가 맵을 보고
     * 지나갔을 수 있다. 종료는 플래그를 쓴 뒤 맵을 보고, 여기는 맵·엔진 상태를 바꾼 뒤 플래그를 읽으므로 둘 중 하나는 반드시
     * 상대를 본다. 이중 정지는 엔진이 막는다(stopMutex).
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

    /**
     * 그림자 관측기. 설정이 꺼져 있거나 저장소가 없으면 null(=관측 없음)이다 —
     * 저장할 곳 없이 켜면 매 tick 계산만 하고 결과가 사라져 "돌고 있다"는 착각만 남는다.
     */
    private fun shadowExitObserver(userId: Long): ShadowExitObserver? {
        // 켜짐/꺼짐을 **둘 다** 로그로 남긴다. 이 값은 앱 기본값 → GitHub 시크릿 → 서버 .env → compose 전달목록
        // → 컨테이너 순으로 네 계층을 지나며, 어느 한 곳에서 빠지면 조용히 기본값으로 돈다(이 repo 에서 세 번 났다).
        // 그때 "켰다고 믿는데 안 켜진" 상태를 로그만으로 구분할 수 있어야 한다 — 첫 관측이 몇 주 뒤일 수 있어
        // `[shadow-exit]` 발동 로그를 기다리는 것으로는 늦다.
        if (!shadowExitProperties.enabled) {
            log.info("[shadow-exit] 관측 off (trading.shadow-exit.enabled=false) — user {}", userId)
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

    /**
     * 실행 중 상태를 bot_state 에 쓴다 — 기존 행과 running·전략·목록이 같으면 쓰지 않는다. 실패는 호출자에게 올린다.
     * 전략 칸은 이 코드가 읽지 않지만 계속 쓴다 — 옛 이미지로 롤백하면 복원이 이 값으로 전략을 고르고, 비워 두면 새 행이 DB 기본값을 받는다.
     */
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

    /** bot_state 가 running 이면 내린다. 행이 없거나 이미 내려가 있으면 쓸 것이 없다(응답이 유실된 재호출도 멱등). */
    private suspend fun markStopped(userId: Long) {
        val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull() ?: return
        if (!existing.running) return
        botStateRepository.save(existing.copy(running = false, updatedAt = LocalDateTime.now())).awaitSingle()
    }

    /** 제어 상태 저장 실패를 사용자 문구를 담은 [BotControlPersistFailedException] 으로 바꾼다 — 삼키면 실패가 성공 응답이 된다(#228). */
    private inline fun <T> persistOrFail(userId: Long, userMessage: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("봇 제어 상태 저장 실패 user={} — {}", userId, userMessage, e)
            throw BotControlPersistFailedException(userMessage, e)
        }

    /** 멈춘 엔진의 미기록 pending(매수 포함)·고점을 상한 안에서 한 번 더 남기고, 그래도 DB 에 없을 수 있는 매도를 돌려준다. */
    private suspend fun flushStoppedEngine(userId: Long, engine: TradingEngine): List<TradingState> {
        // withTimeout 이 아니다 — 시간 초과가 취소로 번지면 reload 가 옛 엔진 복귀를 503 으로 알리지 못한다.
        if (withTimeoutOrNull(FLUSH_TIMEOUT_MS) { engine.flushUnpersisted() } == null) {
            log.warn("user {} 의 미기록 주문·고점 재기록이 {}ms 안에 끝나지 않았다", userId, FLUSH_TIMEOUT_MS)
        }
        return engine.unpersistedSells()
    }

    /** [flushStoppedEngine] 과 같되 취소 외의 예외는 올리지 않는다(ERROR 로 남긴다) — 뒤따르는 정지 저장·로드가 이 결과에 달리면 안 된다. */
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
     * 시작·복원의 초기 상태를 DB 에서 읽는다. 맵에 남은 정지 엔진은 뒤이어 새 엔진으로 바뀌어 그 states 가 버려지므로 읽기 전에
     * 미기록 주문·고점을 남긴다. 알림은 읽기가 성공한 뒤에만 낸다 — 실패하면 바꾸지 않아 버린 것이 없고, 엔진이 남아 다음 시도가 다시 기록한다.
     */
    private suspend fun loadInitialStates(userId: Long): Map<String, TradingState> {
        val unrecorded = engines[userId]?.let { flushOrLog(userId, it) }.orEmpty()
        val loaded = tradingStateService.loadStates(userId)
        alertDroppedSells(userId, unrecorded)
        return loaded
    }

    /**
     * 방금 읽은 사용자 값으로 만든 엔진을 맵에 넣는다. 맵에 남은 정지 엔진은 재사용하지 않는다 — 저장 전 자격증명·웹훅으로 만들어졌을
     * 수 있다(취소된 reload 가 남긴 엔진 등, #51). [loadInitialStates] 뒤에 불러야 그 엔진의 기록이 먼저 남는다.
     */
    private fun registerNewEngine(userId: Long, user: UserEntity): TradingEngine =
        createEngine(user).also { engines[userId] = it }

    /**
     * 엔진 상태를 버리기 직전에 부른다. 선기록까지 실패한 매도면 이후 아무도 확정하지 않는다. 다만 선기록이 성공하고 uuid 기록만
     * 실패한 매도도 같은 플래그라 섞여 있다 — 그쪽은 다음 기동이 identifier 로 스스로 확정하므로, 곧바로 수동 기록하면 이중 계상된다.
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
        /** `startBot` 오류 맵의 `code` — 도는 엔진에 다른 목록을 요청했다(컨트롤러가 409 로 매핑). */
        const val CONFLICT_CODE = "conflict"

        private const val RESTORE_MAX_ATTEMPTS = 5
        private const val SHUTDOWN_TIMEOUT_MS = 25_000L // Spring timeout-per-shutdown-phase(30s) 안쪽 self-bound
        // 맨 앞의 "Service is shutting down"(저장 안 함 — 재시작해도 뜨지 않는다)과 구별한다.
        internal const val START_SAVED_SHUTTING_DOWN_MESSAGE =
            "Service is shutting down — the start is saved and the bot resumes after the restart"
        private const val FINAL_STOP_SAVE_TIMEOUT_MS = 5_000L
        private const val STOP_SAVE_TIMEOUT_MS = 10_000L
        private const val FLUSH_TIMEOUT_MS = 5_000L

        // bot_state 는 (user_id, exchange) 별 1행(V17) — 현재 거래소는 Upbit 뿐이다.
        private const val EXCHANGE = "UPBIT"
    }
}
