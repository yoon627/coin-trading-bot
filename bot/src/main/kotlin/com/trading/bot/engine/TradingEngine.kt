package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()
    private val staleWarnAtMs = ConcurrentHashMap<String, Long>()
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
            activeTickers = tickers
            // durable 복원 상태를 seed — runLoop 의 computeIfAbsent 가 이 값을 유지하고, syncPosition 이 position/잔고만 덮는다.
            // 이번 실행의 활성 ticker 만 — 과거 ticker 까지 실으면 tick 이 안 도는 상태가 getStates·일일 리셋에 섞인다.
            initialStates.filterKeys { it in tickers }.forEach { (ticker, state) -> states[ticker] = state }
            // 드롭한 ticker 에 미해소 주문이 남아 있으면 아무도 reconcile 하지 않는다 — 사람이 알아야 한다.
            initialStates.filterKeys { it !in tickers }
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
        activeTickers.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it) }
        }

        activeTickers.forEach { ticker ->
            positionManager.syncPosition(ticker, states[ticker]!!)
        }

        while (running.get() && scope.isActive) {
            try {
                if (dailyResetManager.checkAndReset(states)) {
                    // 9AM 리셋(boughtToday=false)을 durable 로 flush — 리셋 직후 재시작 시 boughtToday=true 복원으로 당일 재진입이 재차단되는 것 방지.
                    states.values.forEach { positionManager.persistState(it) }
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

            // 시작 시 syncPosition(runLoop) 이 실패했으면(unsynced) 매수 평가 전에 재시도. 성공 시 해소,
            // 실패 지속 시 buy() 초입 가드가 신규 진입을 막아 이중 포지션을 방지한다.
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

            if (state.position) {
                val reason = decideSell(state, currentPrice, ticker, resolveExitStrategy(state, strategy))
                if (reason != null && positionManager.sell(ticker, state, currentPrice, reason) != null) return
            }

            // 당일 1회 진입: 이미 보유 중이거나 오늘 매수했으면 신규 매수 평가 자체를 생략.
            if (state.position || state.boughtToday) return

            // 매수도 청산과 동일: store 에 충분한 D1 이 있으면 store, 부족하면(부팅 직후/신규 마켓) REST 폴백.
            // 구 `size>=2` 게이트는 오염(중복 누적)에 가려 늘 store 를 탔고, 오염 제거 후엔 warm-up 동안 적은 캔들로
            // 전략을 죽였다(MeanReversion 등 size<21 false) → loadStoreDailyCandles 게이트로 매수/청산 통일.
            val storeCandles = loadStoreDailyCandles(ticker)
            val shouldBuy = if (storeCandles != null) {
                strategy.shouldBuyNormalized(storeCandles, currentPrice, tradingProperties)
            } else {
                val candles = upbitClient.getDayCandles(ticker, MAX_DAILY_CANDLE_LOOKBACK)
                strategy.shouldBuy(candles, currentPrice, tradingProperties)
            }
            if (shouldBuy) {
                // 체결 확정·상태 전이·감사 기록·커밋 후 알림은 PositionManager.commitFill 이 담당한다(#52).
                positionManager.buy(ticker, state, currentPrice, strategy.name)
            }
        } catch (e: CancellationException) {
            throw e // 취소 전파(runLoop 와 동일 이유 — 삼키면 loop 가 계속 돌아 join 지연·오탐 ERROR).
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
        }
    }

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
        val storeCandles = loadStoreDailyCandles(ticker)
        if (storeCandles != null) {
            return strategy.shouldSellNormalized(storeCandles, currentPrice, tradingProperties)
        }
        val candles = upbitClient.getDayCandles(ticker, MAX_DAILY_CANDLE_LOOKBACK)
        if (candles.size < MIN_DAILY_CANDLES) {
            log.debug("chartExit skipped for {}: insufficient D1 candles ({})", ticker, candles.size)
            return false
        }
        return strategy.shouldSell(candles, currentPrice, tradingProperties)
    }

    /**
     * 매수·청산 공통 D1 캔들 로딩. store 에 충분한(>=MIN_DAILY_CANDLES) D1 이 있으면 반환, 없으면 null(호출측 REST 폴백).
     * MarketDataStore 가 openTime upsert 로 dedup 하므로 distinctBy 는 방어망(store 회귀 대비, 평상시 no-op).
     */
    internal fun loadStoreDailyCandles(ticker: String): List<NormalizedCandle>? {
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeCandles = marketDataStore
            ?.getCandles(exchange, normalizedMarket, CandleInterval.D1, MAX_DAILY_CANDLE_LOOKBACK)
            ?.distinctBy { it.openTime }
        return if (storeCandles != null && storeCandles.size >= MIN_DAILY_CANDLES) storeCandles else null
    }

}
