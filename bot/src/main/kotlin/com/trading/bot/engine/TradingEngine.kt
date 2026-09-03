package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.AccumulateProperties
import com.trading.common.config.TradingProperties
import com.trading.common.config.UniverseProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.AccumulateLadder
import com.trading.common.strategy.LadderAction
import com.trading.common.strategy.TradingStrategy
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
    private val accumulateProperties: AccumulateProperties = AccumulateProperties(),
    private val universeProperties: UniverseProperties = UniverseProperties(),
    // null = 자동 선정 없음. 켜짐 여부의 스위치는 universeProperties.auto 하나다.
    private val universeSource: UniverseSource? = null,
    // null = 엔진의 인증 클라이언트로 직접 조회(단위 테스트·레거시 경로). 운영은 싱글톤 캐시를 주입한다.
    private val dailyCandleCache: DailyCandleCache? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal enum class TickerProfile { SWING, ACCUMULATE }

    // 적립 티커 집합은 설정에서 한 번 만든다 — tick 마다 문자열을 파싱하지 않고, start() 의 합집합과 같은 소스를 쓴다.
    private val accumulateTickers: Set<String> = accumulateProperties.tickerList().toSet()

    // 자동 선정이 마지막으로 고른 알트 집합. 보유 때문에 잔류한 티커는 여기 없으므로 청산 뒤 재진입하지 못한다 —
    // 잔류의 의미는 "청산될 때까지"이지 "다음 갱신까지 새로 사도 된다"가 아니다. auto 면 첫 선정이 성공하기 전까지
    // 빈 집합(신규 진입 없음, 청산만) — null(제한 없음)로 두면 선정 API 장애 중 durable 잔재 전부가 진입 대상이 된다.
    @Volatile
    private var swingUniverse: Set<String>? = if (universeProperties.auto) emptySet() else null

    // auto 재시작에서 진입 흔적이 없어 활성에 싣지 않은 durable 행. 실제 잔고가 있으면 runLoop 초입에서 되살린다.
    @Volatile
    private var dormantStates: Map<String, TradingState> = emptyMap()

    // 적립 티커의 주기 재동기화 시각. 수동 매매(/api/trade)는 TradingState 를 건드리지 않아 장부가 낡는다.
    private val ladderSyncedAtMs = ConcurrentHashMap<String, Long>()

    internal fun profileOf(ticker: String): TickerProfile =
        if (ticker in accumulateTickers) TickerProfile.ACCUMULATE else TickerProfile.SWING

    /** 상태 API 용 wire 값 — enum 이름을 그대로 내보내면 리팩터가 응답 계약을 조용히 바꾼다. */
    internal fun profileNameOf(ticker: String): String = when (profileOf(ticker)) {
        TickerProfile.SWING -> "swing"
        TickerProfile.ACCUMULATE -> "accumulate"
    }

    companion object {
        private const val ERROR_RETRY_DELAY_MS = 60_000L
        // store 가격 신선도 한계 — 초과분은 REST 폴백으로.
        private const val PRICE_STALE_THRESHOLD_MS = 30_000L
        // stale 폴백 WARN 은 ticker 당 1분 1회 — 피드 장애 시 tick(기본 10s)마다 반복되는 스팸 방지.
        private const val STALE_WARN_INTERVAL_MS = 60_000L
        // 일봉 지표 최소 캔들(데드크로스 5/20 = longPeriod+1). 매수·청산 공통 D1 충분 게이트.
        // lookback 은 distinct 방어 여유분 포함(store openTime upsert 후엔 중복 없으나 안전망).
        private const val MIN_DAILY_CANDLES = 21
        private const val MAX_DAILY_CANDLE_LOOKBACK = 60
        // 자동 선정 알트가 채울 수 있는 활성 티커 수의 목표치(API 입력 상한 20 과 동일). 적립·보유·pending 티커는
        // 자르지 않으므로 실제 활성 총수는 이를 넘을 수 있다 — 하드 상한이 아니라 알트 몫의 cap 이다.
        internal const val SWING_UNIVERSE_CAP = 20
        // 적립 티커 계좌 재조회 주기 — 수동 매매를 이 시간 안에 장부에 반영한다(4종 × 1/60s 라 부하는 무시할 수준).
        private const val LADDER_SYNC_INTERVAL_MS = 60_000L
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()
    private val staleWarnAtMs = ConcurrentHashMap<String, Long>()
    // 캔들 부족 경고도 tick 마다 반복되므로 같은 방식으로 억제한다. 키에 전략·경로를 넣는 이유:
    // 런타임 setStrategy 로 전략이 바뀌면 새로 알려야 하고, 매수/청산이 중복 발화하면 안 된다.
    private val candleWarnAtMs = ConcurrentHashMap<String, Long>()
    // 컨트롤러 스레드(setStrategy/start)와 runLoop 코루틴이 함께 접근 → 가시성 보장.
    @Volatile
    private var activeStrategy: TradingStrategy? = null
    @Volatile
    private var activeTickers: List<String> = emptyList()

    init {
        activeStrategy = strategies.find { it.name == tradingProperties.strategy }
            ?: strategies.firstOrNull()
    }

    fun start(
        tickers: List<String> = tradingProperties.tickerList(),
        initialStates: Map<String, TradingState> = emptyMap(),
    ) {
        if (running.compareAndSet(false, true)) {
            // 적립 티커는 설정이 정하고 사용자 목록과 합친다. 사용자 목록(bot_state.tickers)은 건드리지 않는다 —
            // 파생 집합을 거기 되쓰면 프로파일을 꺼도 그날의 목록이 남아 되돌릴 수 없다.
            // 자동 유니버스가 고른 티커는 bot_state.tickers 에 없으므로, 재시작 때 durable 행 중 보유·pending 흔적이 있는 것을
            // 실어야 applyTickers 의 보호 집합에 들어간다. 진입 메타가 없는 행(청산 완료 잔재)은 싣지 않는다 — 유니버스가
            // 여러 날 회전하면 행이 쌓이고, 전부 syncPosition 하면 기동 시 계좌 조회가 그만큼 반복된다.
            val restored = if (universeProperties.auto) {
                initialStates.filterValues { it.pendingBuyUuid != null || it.pendingSellUuid != null || it.entryStrategy != null || it.buyDate != null }.keys.toList()
            } else {
                emptyList()
            }
            val active = (accumulateTickers + tickers + restored).distinct()
            if (accumulateTickers.isNotEmpty() && active.size == accumulateTickers.size) {
                log.warn("User {} has no swing tickers — every active ticker is on the accumulate profile", userId)
            }
            activeTickers = active
            // durable 복원 상태를 seed — runLoop 의 computeIfAbsent 가 이 값을 유지하고, syncPosition 이 position/잔고만 덮는다.
            // 이번 실행의 활성 ticker 만 — 과거 ticker 까지 실으면 tick 이 안 도는 상태가 getStates·일일 리셋에 섞인다.
            initialStates.filterKeys { it in active }.forEach { (ticker, state) -> states[ticker] = state }
            // 메타 없는 행(외부·수동 보유를 syncPosition 으로 편입한 것)은 잔고가 있을 때만 살린다 — runLoop 가 계좌를 1회 조회해 판정.
            dormantStates = if (universeProperties.auto) initialStates.filterKeys { it !in active } else emptyMap()
            // 드롭한 ticker 에 미해소 주문이 남아 있으면 아무도 reconcile 하지 않는다 — 사람이 알아야 한다.
            initialStates.filterKeys { it !in active }
                .filterValues { it.pendingBuyUuid != null || it.pendingSellUuid != null }
                .forEach { (ticker, state) ->
                    log.error(
                        "비활성 ticker {} 에 미해소 주문이 남아 있습니다(buy={}, sell={}) — 이 실행에서는 reconcile 되지 않습니다.",
                        ticker, state.pendingBuyUuid, state.pendingSellUuid,
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

    fun getActiveStrategyName(): String = activeStrategy?.name ?: "none"

    fun setStrategy(strategyName: String): Boolean {
        val strategy = strategies.find { it.name == strategyName } ?: return false
        activeStrategy = strategy
        log.info("User {} ({}) strategy changed to: {}", userId, username, strategyName)
        return true
    }

    private suspend fun runLoop() {
        reviveHeldDormantStates()
        activeTickers.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it) }
        }

        activeTickers.forEach { ticker ->
            positionManager.syncPosition(ticker, states[ticker]!!)
        }
        // 기동 시 1회 + 매 09:00 경계. 재시작 첫 tick 도 경계로 잡히는데 그것 역시 "기동 시 갱신"이다.
        // while 의 복구 경계 밖이라 여기서 던지면 running=true 인 채 루프가 죽는다 — 실패는 직전 목록 유지로 흡수.
        try {
            refreshUniverse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Initial universe refresh failed for user {} — keeping {}: {}", userId, activeTickers, e.message, e)
        }

        while (running.get() && scope.isActive) {
            try {
                if (dailyResetManager.checkAndReset(states)) {
                    // 9AM 리셋(boughtToday=false)을 durable 로 flush — 리셋 직후 재시작 시 boughtToday=true 복원으로 당일 재진입이 재차단되는 것 방지.
                    states.values.forEach { positionManager.persistState(it) }
                    refreshUniverse()
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
     * auto 재시작에서 진입 흔적이 없어 싣지 않은 durable 행 중 거래소에 실제 잔고가 있는 것을 활성에 되살린다.
     * 외부·수동 보유를 syncPosition 으로 편입한 포지션은 entryStrategy·buyDate 가 없어 흔적 필터에 걸리지 않는데,
     * 빠뜨리면 청산 평가를 영영 못 받는다. 계좌 조회 1회로 판정하며, 실패하면 살리지 않고 다음 기동에 맡긴다.
     */
    private suspend fun reviveHeldDormantStates() {
        val dormant = dormantStates
        if (dormant.isEmpty()) return
        val held = try {
            positionManager.heldCurrencies()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not check exchange holdings for dormant tickers of user {} — leaving {} dormant: {}", userId, dormant.keys, e.message)
            return
        }
        // 조회가 성공했을 때만 판정을 끝낸다 — 실패는 위에서 return 해 dormant 를 남기고 다음 기회(루프·09:00 갱신)에 재시도.
        dormantStates = emptyMap()
        val revived = dormant.filterKeys { it.substringAfter("-") in held }
        if (revived.isEmpty()) return
        revived.forEach { (ticker, state) -> states[ticker] = state }
        activeTickers = (activeTickers + revived.keys).distinct()
        log.info("Revived held tickers without entry metadata for user {}: {}", userId, revived.keys)
    }

    /**
     * 자동 유니버스가 켜져 있으면 알트 스윙 목록을 새로 골라 활성 집합을 교체한다.
     * @return 교체했으면 true. 꺼져 있거나 조회가 실패하면 false — 실패 시 직전 목록을 유지한다.
     */
    internal suspend fun refreshUniverse(): Boolean {
        val source = universeSource?.takeIf { universeProperties.auto } ?: return false
        // 기동 시 계좌 조회가 실패해 남은 dormant 행이 있으면 여기서 다시 판정한다 — 새 목록에 없으면 이번이 살릴 기회다.
        if (dormantStates.isNotEmpty()) reviveHeldDormantStates()
        val selected = source.select(exclude = accumulateTickers, count = universeProperties.altCount)
        if (selected == null) {
            log.warn("Universe refresh failed for user {} — keeping {}", userId, activeTickers)
            return false
        }
        applyTickers(selected)
        log.info("Universe refreshed for user {}: active={}", userId, activeTickers)
        return true
    }

    /**
     * 활성 티커 집합 교체의 유일한 경로. 목록만 갈아끼우면 새 티커는 `states` 에 없어 매 tick 조용히 skip 되고,
     * 빠진 티커의 상태는 리셋·status 에 계속 섞인다 — 시딩·동기화·정리를 여기서 한꺼번에 한다.
     *
     * 보유 중·미해소 주문이 있는 티커는 목록에서 빠져도 청산될 때까지 남긴다(신규 진입은 스윙 규칙이 막지 않으나
     * 다음 갱신에서 다시 빠진다). 알트 몫은 [SWING_UNIVERSE_CAP] 까지만 채우고 적립·보유 티커는 자르지 않는다.
     *
     * 신규 티커는 durable 복원본으로 시딩한다 — 빈 상태로 만들면 재시작 전에 남긴 pending uuid·halt·boughtToday 가
     * 다음 upsert 에서 지워진다(자동 선정이 재시작 후 같은 티커를 다시 고르는 경우가 그렇다).
     */
    internal suspend fun applyTickers(next: List<String>) {
        // unsynced 는 "보유 여부를 아직 모른다" — 실제 포지션일 수 있으니 확인될 때까지 목록에서 빼지 않는다.
        val protectedSet = states.filterValues { it.position || it.unsynced || it.pendingBuyUuid != null || it.pendingSellUuid != null }.keys
        // 현재 순서를 보존한다 — ConcurrentHashMap 키 순서는 삽입 순서가 아니다.
        val protected = activeTickers.filter { it in protectedSet } + protectedSet.filter { it !in activeTickers }
        val pinned = (accumulateTickers + protected).distinct()
        val room = (SWING_UNIVERSE_CAP - pinned.size).coerceAtLeast(0)
        val active = pinned + next.filter { it !in pinned }.distinct().take(room)

        for (ticker in active) {
            if (states.containsKey(ticker)) continue
            val restored = positionManager.loadState(ticker) ?: TradingState(ticker)
            // 09:00 갱신은 checkAndReset 뒤에 돈다 — 옛 boughtDate 를 그대로 두면 하루 종일 진입이 막힌다.
            restored.resetDaily(dailyResetManager.getTradingDate())
            val state = states.computeIfAbsent(ticker) { restored }
            positionManager.syncPosition(ticker, state)
        }
        states.keys.filter { it !in active }.forEach { states.remove(it) }
        activeTickers = active
        swingUniverse = next.toSet()
    }

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
            // 적립 프로파일은 트레일링을 쓰지 않으므로 보유 중 고점을 기록하지 않는다(무포지션 고점은 runAccumulate).
            if (state.position && profileOf(ticker) == TickerProfile.SWING) {
                val newHigh = state.updatePeakPrice(currentPrice)
                if (newHigh || state.peakPersistFailed) positionManager.persistPeak(state)
            }

            // H8: 미해소 매수 주문(placeOrder 성공 후 체결확인 실패분)이 있으면 먼저 reconcile.
            // 진행중이면 이 tick 의 매수/매도 평가는 skip(중복매수·미확정 상태 평가 방지).
            if (state.pendingBuyUuid != null) {
                // 체결이 확정되면 PositionManager 가 상태 전이와 감사 기록을 원자 커밋하고 알림까지 끝낸다(#52).
                if (positionManager.reconcilePendingBuy(ticker, state, currentPrice) != null) {
                    // 매수 확정 tick 은 일반 buy 경로와 동일하게 종료(막 산 포지션에 같은 tick 손절·익절 평가 방지).
                    return
                }
                if (state.pendingBuyUuid != null) return // 아직 미해소 — 이 tick 매수/매도 평가 skip
            }

            // 매도판 H8: 미해소 매도 주문(placeOrder 성공 후 체결확인 실패/미확정분)이 있으면 매도/매수 평가 전에 reconcile.
            // 확정되면 청산 기록 후 종료, 미해소면 이 tick 평가 skip(같은 포지션에 이중 매도 주문 방지).
            if (state.pendingSellUuid != null) {
                if (positionManager.reconcilePendingSell(ticker, state, currentPrice) != null) return
                if (state.pendingSellUuid != null) return // 아직 미해소 — 이 tick 매도/매수 평가 skip
            }

            // 여기까지가 두 프로파일 공용 preamble. 청산·진입 규칙은 프로파일이 정한다.
            when (profileOf(ticker)) {
                TickerProfile.SWING -> runSwing(ticker, state, strategy, currentPrice)
                TickerProfile.ACCUMULATE -> runAccumulate(ticker, state, currentPrice)
            }
        } catch (e: CancellationException) {
            throw e // 취소 전파(runLoop 와 동일 이유 — 삼키면 loop 가 계속 돌아 join 지연·오탐 ERROR).
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
        }
    }

    private suspend fun runSwing(ticker: String, state: TradingState, strategy: TradingStrategy, currentPrice: Double) {
        if (state.position) {
            val reason = decideSell(state, currentPrice, ticker, resolveExitStrategy(state, strategy))
            if (reason != null && positionManager.sell(ticker, state, currentPrice, reason) != null) return
        }

        // 당일 1회 진입: 이미 보유 중이거나 오늘 매수했으면 신규 매수 평가 자체를 생략.
        if (state.position || state.boughtToday) return
        // 자동 유니버스에서 빠졌는데 보유 때문에 잔류했던 티커 — 청산됐으면 새로 사지 않는다(다음 갱신에서 빠진다).
        if (swingUniverse?.let { ticker !in it } == true) return

        // 매수도 청산과 동일: store 에 충분한 D1 이 있으면 store, 부족하면(부팅 직후/신규 마켓) REST 폴백.
        // 구 `size>=2` 게이트는 오염(중복 누적)에 가려 늘 store 를 탔고, 오염 제거 후엔 warm-up 동안 적은 캔들로
        // 전략을 죽였다(MeanReversion 등 size<21 false) → loadStoreDailyCandles 게이트로 매수/청산 통일.
        val minCandles = effectiveMinCandles(strategy)
        val storeCandles = loadStoreDailyCandles(ticker, minCandles)
        val shouldBuy = if (storeCandles != null) {
            strategy.shouldBuyNormalized(storeCandles, currentPrice, tradingProperties)
        } else {
            val candles = fetchDailyCandles(ticker)
            // 부족해도 막지 않는다 — 전략이 자기 가드로 false 를 내므로 결과는 같고, 여기서 끊으면
            // volatility_breakout(진입 2봉)처럼 짧은 이력으로도 매매하던 전략의 계약이 바뀐다.
            // 목적은 차단이 아니라 "왜 신호가 없는지"를 드러내는 것이다.
            if (candles.size < minCandles) warnInsufficientCandles(ticker, strategy, "buy", candles.size, blocked = false)
            strategy.shouldBuy(candles, currentPrice, tradingProperties)
        }
        if (shouldBuy) {
            // 체결 확정·상태 전이·감사 기록·커밋 후 알림은 PositionManager.commitFill 이 담당한다(#52).
            positionManager.buy(ticker, state, currentPrice, strategy.name, reservedKrw())
        }
    }

    /**
     * 적립 프로파일 — 손절·익절·트레일링·보유상한 없이 [AccumulateLadder] 판정만 따른다.
     * 잔고 불명(unsynced)이면 판정하지 않는다: 예산 게이트가 거래소 실측을 전제로 한다.
     */
    private suspend fun runAccumulate(ticker: String, state: TradingState, currentPrice: Double) {
        if (state.unsynced) return
        // 수동 매매(/api/trade)는 TradingState 를 갱신하지 않으므로 주기적으로 계좌를 다시 읽어 장부 정합의 입력을 최신화한다.
        val now = System.currentTimeMillis()
        if (now - (ladderSyncedAtMs[ticker] ?: 0L) >= LADDER_SYNC_INTERVAL_MS) {
            ladderSyncedAtMs[ticker] = now
            positionManager.syncPosition(ticker, state, clearWhenEmpty = true)
            if (state.unsynced) return
        }
        val params = accumulateProperties.ladderParams()
        // 매 tick 돌려도 정합 상태에서는 no-op 이라 사람이 고친 장부를 덮지 않는다. 런타임에 장부와 잔고가 갈라지면
        // (부분체결·수동 매매) decide 가 Hold 로 멈추는데, 적립엔 다른 청산 게이트가 없어 여기 말고는 풀 곳이 없다.
        val flatPeakBefore = state.flatPeak
        val note = LadderStateMapper.reconcile(state, params, currentPrice)
        if (note != null) {
            log.warn("Ladder reconciled for {} (user {}): {}", ticker, userId, note)
            positionManager.persistState(state)
        } else if (state.flatPeak != flatPeakBefore) {
            positionManager.persistPeak(state)
        }
        if (!state.position) {
            // 무포지션 고점은 첫 단의 기준선 — peakPrice 와 같은 "갱신 tick 만 flush + 실패 시 재시도" 규약.
            if (state.updateFlatPeak(currentPrice) || state.peakPersistFailed) positionManager.persistPeak(state)
        }
        when (val action = AccumulateLadder.decide(LadderStateMapper.toInput(state, currentPrice), params)) {
            is LadderAction.Buy -> positionManager.buyRung(ticker, state, currentPrice, action, params)
            is LadderAction.Sell -> positionManager.sellVolume(ticker, state, currentPrice, action)
            LadderAction.Hold -> Unit
        }
    }

    /** 적립 티커가 아직 투입하지 않은 예산의 합 — 스윙 매수가 이 현금을 쓰지 못하게 사이징에서 뺀다. */
    internal fun reservedKrw(): Double {
        if (accumulateTickers.isEmpty()) return 0.0
        val budget = accumulateProperties.budgetKrw
        return accumulateTickers.sumOf { ticker ->
            val s = states[ticker]
            val invested = if (s == null) 0.0 else s.avgBuyPrice * s.holdVolume
            (budget - invested).coerceAtLeast(0.0)
        }
    }

    private suspend fun fetchDailyCandles(ticker: String): List<Candle> =
        dailyCandleCache?.get(ticker, MAX_DAILY_CANDLE_LOOKBACK) ?: upbitClient.getDayCandles(ticker, MAX_DAILY_CANDLE_LOOKBACK)

    // 청산은 진입 전략으로 평가(진입-청산 일관성). entryStrategy 는 durable 복원되지만, 전략이 목록에서 사라졌으면
    // (전략 제거/rename) 활성 전략으로 폴백한다. 폴백은 청산 기준이 진입과 달라지므로 WARN.
    internal fun resolveExitStrategy(state: TradingState, fallback: TradingStrategy): TradingStrategy {
        val entry = state.entryStrategy ?: return fallback
        return strategies.find { it.name == entry } ?: run {
            log.warn("entryStrategy '{}' not found for {} — exit falls back to '{}'", entry, state.ticker, fallback.name)
            fallback
        }
    }

    // 매도 사유 우선순위: 손익% 안전망(손절>트레일링>익절)이 먼저, 차트청산은 그 뒤(이익실현 보호), 일일리셋은 최후.
    // when short-circuit 으로 가격 안전망이 트리거되면 chartExit(캔들 조회 포함)는 평가하지 않는다.
    internal suspend fun decideSell(
        state: TradingState,
        currentPrice: Double,
        ticker: String,
        strategy: TradingStrategy,
    ): SellReason? = when {
        positionManager.checkStopLoss(state, currentPrice) -> SellReason.STOP_LOSS
        positionManager.checkTrailingStop(state, currentPrice) -> SellReason.TRAILING_STOP
        positionManager.checkTakeProfit(state, currentPrice) -> SellReason.TAKE_PROFIT
        chartExitTriggered(ticker, currentPrice, strategy) -> SellReason.CHART_EXIT
        dailyResetManager.shouldSellForDailyReset(state) -> SellReason.DAILY_RESET
        else -> null
    }

    // off 면 즉시 false(캔들 조회 0 → 기존 동작 보존). 데이터 조회(REST 포함) 실패가
    // 가격 안전망/매수 평가까지 막지 않도록 예외를 격리한다.
    private suspend fun chartExitTriggered(
        ticker: String,
        currentPrice: Double,
        strategy: TradingStrategy,
    ): Boolean {
        if (!tradingProperties.chartExitEnabled) return false
        return try {
            evaluateChartExit(ticker, currentPrice, strategy)
        } catch (e: CancellationException) {
            throw e // 취소 전파 — runCatching 은 CE 까지 삼켜 종료 중에도 후속 매수/청산 평가가 계속된다.
        } catch (e: Exception) {
            log.debug("chartExit evaluation failed for {}: {}", ticker, e.message)
            false
        }
    }

    /**
     * 차트청산 신호 평가. 충분한 D1 이 store 에 있으면(loadStoreDailyCandles) 그것을, 부족하면
     * 매수 경로와 동일한 getDayCandles REST 폴백. 그래도 부족하면 skip(false).
     */
    internal suspend fun evaluateChartExit(
        ticker: String,
        currentPrice: Double,
        strategy: TradingStrategy,
    ): Boolean {
        // 여기 strategy 는 resolveExitStrategy 가 복원한 **진입 전략**이다. 활성 전략 값을 쓰면
        // knee(41)로 산 포지션을 활성 volatility_breakout(21) 기준으로 판단해, 21~40봉 store 가
        // "충분"으로 통과하고 ShoulderExit 이 영영 false 가 된다 — 차트청산이 죽은 포지션이 생긴다.
        val minCandles = effectiveMinCandles(strategy)
        val storeCandles = loadStoreDailyCandles(ticker, minCandles)
        if (storeCandles != null) {
            return strategy.shouldSellNormalized(storeCandles, currentPrice, tradingProperties)
        }
        val candles = fetchDailyCandles(ticker)
        if (candles.size < minCandles) {
            warnInsufficientCandles(ticker, strategy, "chartExit", candles.size, blocked = true)
            return false
        }
        return strategy.shouldSell(candles, currentPrice, tradingProperties)
    }

    /** 전략 요구와 엔진 하한 중 큰 쪽. 하한을 두는 이유는 21 미만 선언 전략이 더 짧은 store 를
     * 고르게 되어(REST 60봉 대신) 지표 값 자체가 달라지기 때문이다 — 특히 RSI 는 window 길이에 민감하다. */
    private fun effectiveMinCandles(strategy: TradingStrategy): Int =
        max(MIN_DAILY_CANDLES, strategy.minCandles)

    /**
     * 캔들이 모자라 신호를 못 내는 상황을 알린다. 조용히 false 를 반환하면 원인을 코드로만 알 수 있다.
     *
     * [blocked] 를 문구에 반영하는 이유: 매수 경로는 부족해도 전략을 그대로 호출하므로(짧은 이력으로도
     * 매매하는 전략이 있다) "건너뛴다"고 적으면 실제로 체결된 진입을 운영자가 차단된 것으로 오해한다.
     */
    private fun warnInsufficientCandles(
        ticker: String,
        strategy: TradingStrategy,
        path: String,
        actual: Int,
        blocked: Boolean,
    ) {
        val key = "$ticker:${strategy.name}:$path"
        val now = System.currentTimeMillis()
        val last = candleWarnAtMs[key]
        if (last != null && now - last < STALE_WARN_INTERVAL_MS) return
        candleWarnAtMs[key] = now
        log.warn(
            "{} for {} (user {}): D1 캔들 {}개 < {} 전략 요구 {}개 — {}",
            path, ticker, userId, actual, strategy.name, effectiveMinCandles(strategy),
            if (blocked) "신호 평가를 건너뛴다" else "신호 평가는 계속하나 대부분 false 다",
        )
    }

    /**
     * 매수·청산 공통 D1 캔들 로딩. store 에 충분한(>=MIN_DAILY_CANDLES) D1 이 있으면 반환, 없으면 null(호출측 REST 폴백).
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
