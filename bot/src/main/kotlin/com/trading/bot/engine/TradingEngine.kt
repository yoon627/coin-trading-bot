package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.math.max

class TradingEngine(
    private val upbitClient: UpbitClient,
    private val positionManager: PositionManager,
    private val dailyResetManager: DailyResetManager,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val userId: Long = 0,
    private val username: String = "",
    private val discordWebhookUrl: String? = null,
    private val marketDataStore: MarketDataStore? = null,
    private val exchange: Exchange = Exchange.UPBIT,
    // null = 엔진의 인증 클라이언트로 직접 조회(단위 테스트·레거시 경로). 운영은 싱글톤 캐시를 주입한다.
    private val dailyCandleCache: DailyCandleCache? = null,
    // null = 그림자 관측 off. 켜도 매매는 바뀌지 않는다 — 후보 청산 파라미터를 나란히 평가해 기록만 한다.
    private val shadowExitObserver: ShadowExitObserver? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 신규 스윙 진입을 허용하는 집합 — start 가 사용자 목록으로 채운다. 여기 없는 활성 티커는 보유·미해소 주문 때문에
    // 잔류한 것이라 청산 뒤 재진입하지 못한다 — 잔류의 의미는 "청산될 때까지"이지 "새로 사도 된다"가 아니다.
    // null(제한 없음)은 start 전(processTicker 를 직접 부르는 단위 테스트)뿐이다.
    @Volatile
    private var swingUniverse: Set<String>? = null

    // 사용자가 준 목록(bot_state.tickers). 활성 집합은 여기에 잔류가 합쳐진 파생 집합이라, 재기동이나 실행 중
    // start 비교가 활성 집합을 사용자 의도로 쓰면 잔류 티커가 신규 진입 대상으로 승격된다(#226).
    @Volatile
    private var userTickers: List<String> = emptyList()

    companion object {
        private const val ERROR_RETRY_DELAY_MS = 60_000L
        // store 가격 신선도 한계 — 초과분은 REST 폴백으로.
        private const val PRICE_STALE_THRESHOLD_MS = 30_000L
        // stale 폴백 WARN 은 ticker 당 1분 1회 — 피드 장애 시 tick(기본 10s)마다 반복되는 스팸 방지.
        private const val STALE_WARN_INTERVAL_MS = 60_000L
        // store D1 을 쓸 최소 봉 수(엔진 하한) — 이보다 짧은 store window 는 쓰지 않는다(effectiveMinCandles).
        // lookback 은 distinct 방어 여유분 포함(store openTime upsert 후엔 중복 없으나 안전망).
        private const val MIN_DAILY_CANDLES = 21
        private const val MAX_DAILY_CANDLE_LOOKBACK = 60
        // 경계 뒤 이 시간 안에 오늘 D1 이 없는 것은 정상(1분봉 폴링 주기 60s + 마켓 간 간격, 캐시 TTL 60s)이고, 넘기면 수집 정지로 본다.
        private const val STALE_DAILY_CANDLE_WARN_MS = 5 * 60_000L
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()
    private val staleWarnAtMs = ConcurrentHashMap<String, Long>()
    // 캔들 부족·낡은 D1 경고도 tick 마다 반복되므로 같은 방식으로 억제한다. 키에 전략(또는 소스)을 넣는 이유:
    // 런타임 setStrategy 로 전략이 바뀌면 새로 알려야 하고, store/REST 가 서로의 경고를 삼키면 안 된다.
    private val candleWarnAtMs = ConcurrentHashMap<String, Long>()
    // 컨트롤러 스레드(setStrategy/start)와 runLoop 코루틴이 함께 접근 → 가시성 보장.
    @Volatile
    private var activeStrategy: TradingStrategy? = null
    @Volatile
    private var activeTickers: List<String> = emptyList()

    init {
        activeStrategy = strategies.firstOrNull()
    }

    fun start(
        tickers: List<String> = tradingProperties.tickerList(),
        initialStates: Map<String, TradingState> = emptyMap(),
    ) {
        if (running.compareAndSet(false, true)) {
            userTickers = tickers.toList()
            // 목록 밖이라도 엔진이 산 스윙 포지션·미해소 주문(진입 흔적이 있는 durable 행)은 싣는다 — 사용자가 목록에서 뺀
            // 티커는 bot_state.tickers 에 없어 안 실으면 아무도 청산·reconcile 하지 않는다(#226).
            // 진입 메타가 없는 행(청산 완료 잔재)은 싣지 않는다 — 전부 syncPosition 하면 기동 시 계좌 조회가 그만큼 반복된다.
            val requested = tickers.toSet()
            val restored = initialStates.filterValues { it.hasEntryTrace() }.keys.filter { it !in requested }
            val active = (tickers + restored).distinct()
            activeTickers = active
            swingUniverse = tickers.toSet()
            // durable 복원 상태를 seed — runLoop 의 computeIfAbsent 가 이 값을 유지하고, syncPosition 이 position/잔고만 덮는다.
            // 이번 실행의 활성 ticker 만 — 직전 실행(같은 엔진의 재기동)이나 과거 ticker 까지 남기면 tick 이 안 도는 상태가
            // getStates·일일 리셋에 섞인다. 채운 뒤 나머지를 빼는 순서는 정확성과 무관하다(최종 상태 동일) — 같은 엔진을
            // 다시 start 할 때(주로 resume) 잠금 없이 읽는 getStates()(상태 API)에서 전후 모두 활성인 티커가 잠깐 사라지지
            // 않게 할 뿐이다(표시 전용).
            val seeded = initialStates.filterKeys { it in active }
            seeded.forEach { (ticker, state) -> states[ticker] = state }
            states.keys.retainAll(seeded.keys)
            if (restored.isNotEmpty()) {
                // buyDate 를 함께 남긴다 — 보유상한이 이미 지난 행은 첫 tick 에 청산되므로 무엇이 곧 팔릴지 로그로 보이게.
                log.warn("User {}: 사용자 목록 밖 티커를 청산될 때까지 관리합니다(ticker=buyDate): {}", userId, restored.associateWith { initialStates[it]?.buyDate })
            }
            // 드롭한 ticker 에 미해소 주문이 남아 있으면 아무도 reconcile 하지 않는다 — 사람이 알아야 한다.
            initialStates.filterKeys { it !in active }
                .filterValues { it.hasPendingOrder() }
                .forEach { (ticker, state) ->
                    log.error(
                        "비활성 ticker {} 에 미해소 주문이 남아 있습니다(buy={}, sell={}) — 이 실행에서는 reconcile 되지 않습니다.",
                        ticker, state.pendingBuyRef(), state.pendingSellRef(),
                    )
                }
            warnIfExitConfigInert()
            log.info("Starting trading engine for user {} ({}) with strategy: {}", userId, username, activeStrategy?.name)
            loopJob = scope.launch { runLoop() }
        }
    }

    // 파라미터화는 dead branch 를 설정 가능하게 할 뿐 제거하지 않는다(#27) — 무의미한 조합은 기동 시 경고.
    private fun warnIfExitConfigInert() {
        val p = tradingProperties
        if (p.takeProfitPct <= p.trailingStopPct || p.takeProfitPct <= p.trailingArmPct) {
            log.warn(
                "takeProfitPct({}) <= trailingStopPct({}) or trailingArmPct({}) — take-profit 이 선행해 트레일링이 사실상 도달 불가(dead)입니다",
                p.takeProfitPct, p.trailingStopPct, p.trailingArmPct,
            )
        }
        if (p.trailingArmPct > 0 && p.trailingArmPct <= p.trailingStopPct) {
            log.warn(
                "0 < trailingArmPct({}) <= trailingStopPct({}) — arm 임계가 수학적으로 자동 충족되어 효과가 없습니다",
                p.trailingArmPct, p.trailingStopPct,
            )
        }
    }

    // stop 동시호출(shutdownAll ↔ reload/stopBot)을 직렬화해 CAS 실패자도 같은 loopJob 을 join 하게 한다(M4).
    private val stopMutex = Mutex()

    suspend fun stop() = stopMutex.withLock {
        val job = loopJob
        if (running.compareAndSet(true, false)) {
            log.info("Stopping trading engine for user {} ({})", userId, username)
            // 취소 후 완료까지 대기(join). 진행 중이던 tick 의 주문 후처리(PositionManager NonCancellable 구간)가
            // 끝난 뒤 반환한다. cancel 만 하고 즉시 새 엔진을 기동하면(reload) 구 루프와 경합해 이중 매매가 된다. scope 는 재시작 위해 유지.
            job?.cancelAndJoin()
            loopJob = null
        } else {
            // 이미 다른 호출자가 stop 수행/완료 중 — 같은 loop 완료를 함께 기다려 조기 반환(미드레이닝)을 막는다.
            job?.join()
        }
    }

    fun isRunning(): Boolean = running.get()

    fun getStates(): Map<String, TradingState> = states.toMap()

    /** #19: halt 된 ticker 목록(status 노출용). */
    fun getHaltedTickers(): List<String> = states.filterValues { it.halted }.keys.toList()

    /**
     * #19: halt 수동 해제 — state 를 clear 하고 durable 반영(재시작 후 halt 재발 방지). 해제되면 true, halt 가 아니었으면 false.
     * durable 기록이 실패하면 메모리 해제를 되돌리고 예외를 올린다 — 성공으로 응답하면 사용자는 풀린 줄 알지만
     * 재시작 시 halt 가 되살아난다.
     */
    suspend fun clearHalt(ticker: String): Boolean {
        val state = states[ticker] ?: return false
        if (!state.halted) return false
        val reason = state.haltReason
        val failureCount = state.reconcileFailureCount
        state.clearHalt()
        try {
            positionManager.persistStateOrThrow(state)
        } catch (e: Exception) {
            state.halted = true
            state.haltReason = reason
            state.reconcileFailureCount = failureCount
            throw e
        }
        log.info("Halt cleared for {} ({})", ticker, username)
        return true
    }

    fun getActiveTickers(): List<String> = activeTickers.toList()

    /** 사용자가 준 목록 — 잔류가 섞이지 않은 재기동 입력. */
    fun getUserTickers(): List<String> = userTickers.toList()

    // 화면용 분류(status). 활성 집합 = 진입 허용 ∪ 청산 대기 — 진입 판정과 같은 isExitOnly 로 가른다.

    /** 신규 진입을 받는 티커 — 활성 중 진입 허용 집합(사용자 목록) 안의 것. */
    fun getEntryTickers(): List<String> = activeTickers.filter { !isExitOnly(it) }

    /** 청산될 때까지만 관리하는 티커 — 활성이지만 신규 진입 허용 집합 밖이다. */
    fun getExitOnlyTickers(): List<String> = activeTickers.filter { isExitOnly(it) }

    /**
     * 같은 엔진을 직전 실행의 사용자 목록·상태 그대로 다시 기동한다 — reload 가 새 엔진으로 넘어가지 못했을 때의 복귀 경로.
     * 빈 상태로 start 하면 목록 밖 잔류 포지션이 빠진다. stop 이 루프를 join 한 뒤에 부른다.
     * 상태는 사본으로 넘긴다 — start 가 states 를 바꾸므로 live view 를 넘기면 순회 중에 바뀐다.
     */
    fun resume() = start(userTickers, getStates())

    /**
     * 루프가 기록하지 못한 pending([TradingState.pendingPersistFailed])과 고점([TradingState.peakPersistFailed], #54)을 한 번 더
     * 기록한다. 재기록은 다음 tick 몫이라 멈춘 엔진에서는 일어나지 않고, 이 엔진의 states 를 버리면 DB 에서 시작하는 다음 엔진은
     * 그 주문을 모르고 뒤처진 고점으로 트레일링한다. stop 이 루프를 join 한 뒤에 부른다.
     */
    internal suspend fun flushUnpersisted() {
        // pending 을 모두 먼저 — 고점 쓰기가 호출자의 시간 상한을 먹어 매도 기록이 밀리면 안 된다.
        states.values.forEach { positionManager.retryPendingPersistIfNeeded(it) }
        // 재기록이 성공한 state 는 전체 스냅샷이라 고점도 이미 썼다. 실패한 state 는 건너뛴다 — 같은 스냅샷을 한 flush 에서 두 번 시도하지 않는다.
        states.values.filter { it.peakPersistFailed && !it.pendingPersistFailed }.forEach { positionManager.persistPeak(it) }
    }

    /**
     * DB 에 없을 수 있는 매도 pending. 매도만 선기록이 실패해도 주문을 보낸다 — 매수는 선기록이 성공해야 보내므로 uuid 기록이
     * 실패해도 DB 의 identifier 로 확정된다.
     */
    internal fun unpersistedSells(): List<TradingState> =
        states.values.filter { it.pendingPersistFailed && it.hasPendingSell() }

    fun getActiveStrategyName(): String = activeStrategy?.name ?: "none"

    fun setStrategy(strategyName: String): Boolean {
        val strategy = strategies.find { it.name == strategyName } ?: return false
        activeStrategy = strategy
        log.info("User {} ({}) strategy changed to: {}", userId, username, strategyName)
        return true
    }

    private suspend fun runLoop() {
        activeTickers.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it) }
        }

        activeTickers.forEach { ticker ->
            positionManager.syncPosition(ticker, states[ticker]!!)
        }
        releaseSettledTickers()

        while (running.get() && scope.isActive) {
            try {
                if (dailyResetManager.checkAndReset(states)) {
                    // 9AM 리셋(boughtToday=false)을 durable 로 flush — 리셋 직후 재시작 시 boughtToday=true 복원으로 당일 재진입이 재차단되는 것 방지.
                    states.values.forEach { positionManager.persistState(it) }
                    // flush 뒤에 뺀다 — 먼저 빼면 그 티커의 리셋이 durable 에 남지 않는다.
                    releaseSettledTickers()
                }

                for (ticker in activeTickers) {
                    if (!running.get()) break
                    processTicker(ticker)
                }

                delay(tradingProperties.intervalSeconds * 1000)
            } catch (e: CancellationException) {
                throw e // stop/reload 의 취소는 정상 종료 — 삼키면 ERROR 로그(Discord 스팸)로 둔갑하고 delay 재진입으로 join 이 지연된다.
            } catch (e: Exception) {
                log.error("Trading loop error (user {}): {}", userId, e.message, e)
                delay(ERROR_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 청산 대기(진입 허용 집합 밖) 티커 중 더 지킬 것이 없는 것(보유·unsynced·미해소 주문 없음)을 활성과 states
     * 에서 뺀다. 엔진 매도는 진입 메타를 지워 다음 기동에 싣지 않지만, 엔진 밖에서 팔린 행은 메타가 남아 기동마다 실린다.
     * 그 메타 위에 사람이 다시 사면 다음 기동에 잔류로 실려 옛 buyDate 로 보유상한 매도되므로, 여기서 durable 메타도 비운다 —
     * unsynced 가 아니라 귀속 불명 락이 없으니 코인이 돌아올 여지(#122 가 메타를 남기는 이유)도 없다. persistState 는 실패를
     * 삼키므로 던지지 않는다(기동 시 호출은 runLoop 복구 경계 밖이다).
     */
    private suspend fun releaseSettledTickers() {
        val settled = states.filter { (ticker, state) -> isExitOnly(ticker) && !state.mustKeep() }
        if (settled.isEmpty()) return
        settled.forEach { (ticker, state) ->
            states.remove(ticker)
            if (state.hasEntryTrace()) {
                state.clearEntryMeta()
                positionManager.persistState(state)
            }
        }
        activeTickers = activeTickers.filter { it !in settled.keys }
        log.info("User {}: released tickers outside the list with nothing left to manage: {}", userId, settled.keys)
    }

    /** 활성이지만 신규 진입 허용 집합 밖인 티커 — 목록에서 빠졌는데 보유·미해소 주문 때문에 남은 것. start 전(null)은 없음. */
    private fun isExitOnly(ticker: String): Boolean = swingUniverse?.let { ticker !in it } == true

    // unsynced 는 "보유 여부를 아직 모른다" — 실제 포지션일 수 있으니 확인될 때까지 목록에서 빼지 않는다.
    private fun TradingState.mustKeep(): Boolean = position || unsynced || hasPendingOrder()

    private fun TradingState.hasEntryTrace(): Boolean = hasPendingOrder() || entryStrategy != null || buyDate != null

    private fun TradingState.hasPendingOrder(): Boolean = hasPendingBuy() || hasPendingSell()

    internal fun getRealtimePrice(ticker: String): Double? {
        // Prefer the in-process MarketDataStore — 단 신선한 ticker 만. timestamp 없이 가격만 쓰면
        // 수집 중단(피드 코루틴 사망/WS 재연결 실패) 시 얼어붙은 가격으로 매매 판단하게 된다 (이슈 #27).
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeTicker = marketDataStore?.getLatestTicker(exchange, normalizedMarket)
        if (storeTicker != null) {
            val now = System.currentTimeMillis()
            val ageMs = now - storeTicker.timestamp.toEpochMilli()
            if (ageMs < PRICE_STALE_THRESHOLD_MS) {
                return storeTicker.price
            }
            if (now - (staleWarnAtMs[ticker] ?: 0L) >= STALE_WARN_INTERVAL_MS) {
                staleWarnAtMs[ticker] = now
                log.warn("Stale store price for {} (age {}ms) — falling back to REST", ticker, ageMs)
            }
        }

        // store 미보유(watchlist 밖 티커)·stale 이면 null 반환 → processTicker 가 REST(upbitClient.getTicker)로 폴백.
        return null
    }

    private suspend fun processTicker(ticker: String) {
        val state = states[ticker] ?: return
        val strategy = activeStrategy ?: return
        processTicker(ticker, state, strategy)
    }

    internal suspend fun processTicker(ticker: String, state: TradingState, strategy: TradingStrategy) {
        try {
            val currentPrice = getRealtimePrice(ticker)
                ?: upbitClient.getTicker(ticker).firstOrNull()?.tradePrice
                ?: return

            // syncPosition(runLoop) 이 보유 여부를 확정하지 못했으면(unsynced — 조회 실패이거나 우리 주문으로
            // 설명 안 되는 locked) 매수 평가 전에 재시도. 해소되면 풀리고, 지속되면 buy() 초입 가드가
            // 신규 진입을 막아 이중 포지션을 방지한다.
            if (state.unsynced) {
                positionManager.syncPosition(ticker, state)
            }

            // pending durable 기록이 실패해 매수가 막힌 상태면 매 tick 재기록을 시도한다 — buy() 초입 가드가
            // 재기록 경로까지 막아버려서, 여기서 풀어주지 않으면 그 ticker 는 영영 매수 불가로 남는다.
            positionManager.retryPendingPersistIfNeeded(state)

            // 신고점은 트레일링 스톱의 기준선 — 영속 안 하면 재시작 후 peak 이 0 에서 다시 쌓여
            // 이미 발동했어야 할 청산이 안 걸린다. 갱신된 tick 에만 flush 하되(매 tick upsert 는
            // write 증폭), 직전 flush 가 실패했으면 갱신이 없어도 재시도한다 — 하락 전환 후에는
            // 갱신될 일이 없어 그 1회 실패가 그대로 고점 유실이 된다(#54).
            // 아래 pending reconcile 분기보다 앞에 둔다: 미해소가 길어지는 동안에도 재시도가 돌아야 한다.
            if (state.position) {
                val newHigh = state.updatePeakPrice(currentPrice)
                if (newHigh || state.peakPersistFailed) positionManager.persistPeak(state)
            }

            // H8: 미해소 매수 주문(체결확인 실패분, 또는 응답을 못 받아 identifier 만 있는 주문)이 있으면 먼저 reconcile.
            // 진행중이면 이 tick 의 매수/매도 평가는 skip(중복매수·미확정 상태 평가 방지).
            if (state.hasPendingBuy()) {
                // 체결이 확정되면 PositionManager 가 상태 전이와 감사 기록을 원자 커밋하고 알림까지 끝낸다(#52).
                if (positionManager.reconcilePendingBuy(ticker, state, currentPrice) != null) {
                    // 매수 확정 tick 은 일반 buy 경로와 동일하게 종료(막 산 포지션에 같은 tick 손절·익절 평가 방지).
                    return
                }
                if (state.hasPendingBuy()) return // 아직 미해소 — 이 tick 매수/매도 평가 skip
            }

            // 매도판 H8: 미해소 매도 주문(체결확인 실패/미확정분, identifier 만 있는 주문 포함)이 있으면 매도/매수 평가 전에 reconcile.
            // 확정되면 청산 기록 후 종료, 미해소면 이 tick 평가 skip(같은 포지션에 이중 매도 주문 방지).
            if (state.hasPendingSell()) {
                val decidedAt = state.pendingSellSince // 확정 전이가 pending 을 지우기 전에 읽는다
                val settled = positionManager.reconcilePendingSell(ticker, state, currentPrice)
                if (settled != null) {
                    // 늦게 확정된 스윙 청산도 그림자 관측에 보고한다 — 빠지면 #178 표본이 체결 확인 창 안에 끝난 매도만 담는다.
                    // 기록 price 가 판단가다. 부분 체결(포지션 유지)은 보고하지 않는다 — 발동 기록을 여기서 쓰면 잔량에서
                    // 다시 발동해 한 포지션에 관측이 둘 생긴다. 잔량이 팔리는 확정에서 보고한다.
                    if (!state.position) {
                        shadowExitObserver?.onLiveExit(ticker, settled.price, settled.reason, settled.executedVwap, decidedAt)
                    }
                    return
                }
                if (state.hasPendingSell()) return // 아직 미해소 — 이 tick 매도/매수 평가 skip
            }

            runSwing(ticker, state, strategy, currentPrice)
        } catch (e: CancellationException) {
            throw e // 취소 전파(runLoop 와 동일 이유 — 삼키면 loop 가 계속 돌아 join 지연·오탐 ERROR).
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
        }
    }

    private suspend fun runSwing(ticker: String, state: TradingState, strategy: TradingStrategy, currentPrice: Double) {
        // 거래소 최소주문 미만이라 팔 수 없는 보유(dust)는 진입을 막지 않는다(#234). 청산 평가는 그대로 돈다 — 기록상 수량이
        // 낡았을 수 있어 여기서 끄면 실제로는 팔 수 있는 포지션이 손절을 잃는다. 주문 여부는 PositionManager 가 실잔고로 정한다.
        val dust = state.isDustAt(currentPrice)
        if (state.position) {
            val reason = decideSell(state, currentPrice)
            // 라이브 판정 **뒤에** 관측한다 — decideSell 이 peak 을 갱신한 뒤라야 같은 tick 을 본다.
            // (손절이 먼저 걸린 tick 은 peak 갱신을 건너뛰지만, 그 구간은 진입가 아래라 후보도 발동하지 않는다.)
            // dust 는 관측 대상 포지션이 아니다 — 흡수 뒤의 새 포지션이 옛 관측과 짝지어지지 않게 흘린다.
            if (dust) shadowExitObserver?.forget(ticker) else shadowExitObserver?.onTick(ticker, state, currentPrice)
            val sold = if (reason != null) positionManager.sell(ticker, state, currentPrice, reason) else null
            if (sold != null) {
                // 실체결 단가를 함께 넘긴다 — currentPrice 와의 차이가 실행 슬리피지이고 모델 청산가에는 없는 항목이다.
                shadowExitObserver?.onLiveExit(ticker, currentPrice, reason!!.name, sold.executedVwap)
                return
            }
        } else {
            // 재동기화·수동 청산으로 포지션이 사라졌으면 관측 상태를 흘리지 않는다(다음 포지션에 섞이면 짝이 깨진다).
            shadowExitObserver?.forget(ticker)
        }

        // 당일 1회 진입: 이미 (팔 수 있는) 보유 중이거나 오늘 매수했으면 신규 매수 평가 자체를 생략.
        if ((state.position && !dust) || state.boughtToday) return
        // 사용자 목록에서 빠졌는데 보유 때문에 잔류했던 티커 — 청산됐으면 새로 사지 않는다(09:00 에 빠진다).
        // 그런 티커의 dust 는 흡수될 길도 팔 길도 없어 잔류가 영구화되므로 장부에서 내린다(실잔고 확인은 PositionManager).
        if (isExitOnly(ticker)) {
            if (dust) positionManager.releaseDust(ticker, state, currentPrice)
            return
        }

        // store 에 충분한 D1 이 있으면 store, 부족하면(부팅 직후/신규 마켓) REST 폴백.
        // 구 `size>=2` 게이트는 오염(중복 누적)에 가려 늘 store 를 탔고, 오염 제거 후엔 warm-up 동안 적은 캔들로
        // 전략을 죽였다(전략의 최소 봉 수 가드가 false) → loadStoreDailyCandles 게이트.
        val minCandles = effectiveMinCandles(strategy)
        val storeCandles = loadStoreDailyCandles(ticker, minCandles)
        // 09:00 경계 직후 store 의 최신 D1 은 새 날 첫 1분봉이 폴링되기까지(약 60~120초) 어제 봉이다. 그 window 로 판정하면
        // "당일시가" 가 어제 시가라 어제 매수를 만든 신호가 그대로 참이고, 방금 보유상한으로 판 포지션을 같은 가격에
        // 되산다(#128 의 0.0h 재매수). 그래서 오늘 봉이 있는 소스로만 판정한다.
        val dayOpen = currentTradingDayOpen()
        val currentStoreCandles = storeCandles?.takeIf { isCurrentDay(it.first().openTime, dayOpen) }
        val shouldBuy = if (currentStoreCandles != null) {
            strategy.shouldBuyNormalized(currentStoreCandles, currentPrice, tradingProperties)
        } else {
            if (storeCandles != null) {
                // 경계 직후 잠깐은 다음 1분봉이 채우므로 REST 를 치지 않고 건너뛴다. 그 이상 지속되면 캔들 수집이 멈춘 것이라
                // (캔들 폴링 코루틴은 워치독 밖이다) 경고하고 REST 로 간다 — 거기엔 오늘 봉이 있고 DailyCandleCache 가 60초로 묶는다.
                if (!pastBoundaryGrace(dayOpen)) return
                warnStaleDailyCandle("store", ticker, dayOpen, storeCandles.first().openTime, "falling back to REST")
            }
            val candles = fetchDailyCandles(ticker)
            // 부족해도 막지 않는다 — 전략이 자기 가드로 false 를 내므로 결과는 같다.
            // 목적은 차단이 아니라 "왜 신호가 없는지"를 드러내는 것이다.
            if (candles.size < minCandles) warnInsufficientCandles(ticker, strategy, candles.size)
            // Upbit 일봉도 그날 첫 체결 전엔 어제 봉이 [0] 이고 캐시 TTL 이 60초라 store 와 같은 경계 문제가 있다.
            val newestOpen = candles.firstOrNull()?.openTimeUtcOrNull()
            if (candles.isNotEmpty() && !isCurrentDay(newestOpen, dayOpen)) {
                if (pastBoundaryGrace(dayOpen)) warnStaleDailyCandle("rest", ticker, dayOpen, newestOpen, "buy evaluation skipped")
                return
            }
            strategy.shouldBuy(candles, currentPrice, tradingProperties)
        }
        if (shouldBuy) {
            // 체결 확정·상태 전이·감사 기록·커밋 후 알림은 PositionManager.commitFill 이 담당한다(#52).
            positionManager.buy(ticker, state, currentPrice, strategy.name)
        }
    }

    private suspend fun fetchDailyCandles(ticker: String): List<Candle> =
        dailyCandleCache?.get(ticker, MAX_DAILY_CANDLE_LOOKBACK) ?: upbitClient.getDayCandles(ticker, MAX_DAILY_CANDLE_LOOKBACK)

    // 매도 사유 우선순위: 손익% 안전망(손절>트레일링>익절)이 먼저, 일일리셋은 최후.
    internal fun decideSell(state: TradingState, currentPrice: Double): SellReason? = when {
        positionManager.checkStopLoss(state, currentPrice) -> SellReason.STOP_LOSS
        positionManager.checkTrailingStop(state, currentPrice) -> SellReason.TRAILING_STOP
        positionManager.checkTakeProfit(state, currentPrice) -> SellReason.TAKE_PROFIT
        dailyResetManager.shouldSellForDailyReset(state) -> SellReason.DAILY_RESET
        else -> null
    }

    /** 전략 요구와 엔진 하한 중 큰 쪽. 하한을 두는 이유는 21 미만 선언 전략이 더 짧은 store 를
     * 고르게 되어(REST 60봉 대신) 지표 값 자체가 달라지기 때문이다 — 특히 RSI 는 window 길이에 민감하다. */
    private fun effectiveMinCandles(strategy: TradingStrategy): Int =
        max(MIN_DAILY_CANDLES, strategy.minCandles)

    /**
     * 캔들이 모자라 매수 신호를 못 내는 상황을 알린다. 조용히 false 를 반환하면 원인을 코드로만 알 수 있다.
     * 평가는 막지 않는다 — 전략이 자기 가드로 false 를 내므로 "건너뛴다"고 적으면 운영자가 차단으로 오해한다.
     */
    private fun warnInsufficientCandles(ticker: String, strategy: TradingStrategy, actual: Int) {
        val key = "$ticker:${strategy.name}:min-candles"
        val now = System.currentTimeMillis()
        val last = candleWarnAtMs[key]
        if (last != null && now - last < STALE_WARN_INTERVAL_MS) return
        candleWarnAtMs[key] = now
        log.warn(
            "buy for {} (user {}): D1 캔들 {}개 < {} 전략 요구 {}개 — 신호 평가는 계속하나 대부분 false 다",
            ticker, userId, actual, strategy.name, effectiveMinCandles(strategy),
        )
    }

    /** 현재 거래일 D1 의 openTime — UTC 자정(= KST 09:00). [CandleAggregator] 의 D1 정렬·Upbit `candle_date_time_utc` 와 같은 기준이다. */
    private fun currentTradingDayOpen(): Instant =
        dailyResetManager.getTradingDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    /**
     * 최신 D1 이 현재 거래일(이후)의 봉인가. `>=` 인 이유: 시계가 경계에서 조금 뒤처져 봉이 먼저 넘어가도 오늘 봉이지
     * 낡은 봉이 아니다. [newestOpen] 이 null(파싱 불가)이면 오늘 봉으로 보지 않는다.
     */
    private fun isCurrentDay(newestOpen: Instant?, dayOpen: Instant): Boolean =
        newestOpen != null && !newestOpen.isBefore(dayOpen)

    /** 경계 뒤 [STALE_DAILY_CANDLE_WARN_MS] 가 지났는가 — 그 안쪽은 정상 지연, 바깥은 수집 이상. */
    private fun pastBoundaryGrace(dayOpen: Instant): Boolean =
        clock.millis() - dayOpen.toEpochMilli() > STALE_DAILY_CANDLE_WARN_MS

    // 조용히 매수만 막지 않도록 알린다. tick 마다 반복되지 않게 소스·ticker 당 1분 1회.
    private fun warnStaleDailyCandle(source: String, ticker: String, dayOpen: Instant, newestOpen: Instant?, action: String) {
        val now = System.currentTimeMillis()
        val key = "$ticker:$source:stale-d1"
        if (now - (candleWarnAtMs[key] ?: 0L) < STALE_WARN_INTERVAL_MS) return
        candleWarnAtMs[key] = now
        log.warn(
            "{} D1 for {} (user {}) has no candle for trading-day open {} (newest {}) — {}s past the boundary; {}",
            source, ticker, userId, dayOpen, newestOpen, (clock.millis() - dayOpen.toEpochMilli()) / 1000, action,
        )
    }

    /** REST 일봉의 `candle_date_time_utc`("yyyy-MM-ddTHH:mm:ss") → openTime. [UpbitMarketFeed] 가 store 에 넣을 때와 같은 변환이다. */
    private fun Candle.openTimeUtcOrNull(): Instant? =
        runCatching { LocalDateTime.parse(candleDateTimeUtc).toInstant(ZoneOffset.UTC) }.getOrNull()

    /**
     * 매수 D1 캔들 로딩. store 에 충분한(>=MIN_DAILY_CANDLES) D1 이 있으면 반환, 없으면 null(호출측 REST 폴백).
     * MarketDataStore 가 openTime upsert 로 dedup 하므로 distinctBy 는 방어망(store 회귀 대비, 평상시 no-op).
     */
    internal fun loadStoreDailyCandles(ticker: String, minCandles: Int = MIN_DAILY_CANDLES): List<NormalizedCandle>? {
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeCandles = marketDataStore
            ?.getCandles(exchange, normalizedMarket, CandleInterval.D1, MAX_DAILY_CANDLE_LOOKBACK)
            ?.distinctBy { it.openTime }
        return if (storeCandles != null && storeCandles.size >= minCandles) storeCandles else null
    }

}
