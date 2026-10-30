package com.trading.bot.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.MarketRules
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
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
import org.slf4j.event.Level
import kotlin.math.max

class TradingEngine(
    private val upbitClient: ExchangeClient,
    private val positionManager: PositionManager,
    private val dailyResetManager: DailyResetManager,
    private val strategy: TradingStrategy,
    private val tradingProperties: TradingProperties,
    private val userId: Long = 0,
    private val username: String = "",
    private val discordWebhookUrl: String? = null,
    private val marketDataStore: MarketDataStore? = null,
    private val exchange: Exchange = Exchange.UPBIT,
    // null 이면 엔진 클라이언트로 직접 조회한다. 운영에서는 싱글톤 캐시를 주입한다.
    private val dailyCandleCache: DailyCandleCache? = null,
    private val shadowExitObserver: ShadowExitObserver? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val calendar: TradingCalendar = UpbitTradingCalendar,
    private val rules: MarketRules = UpbitMarketRules,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 사용자가 준 목록(bot_state.tickers). 신규 진입은 이 목록 안에서만 한다. activeTickers 에는 보유·미해소 주문 때문에
    // 남은 티커가 섞이므로 재기동 입력으로 쓰면 그 티커가 신규 진입 대상이 된다. null 은 start 전뿐이다.
    @Volatile
    private var userTickers: List<String>? = null

    companion object {
        private const val ERROR_RETRY_DELAY_MS = 60_000L
        private const val PRICE_STALE_THRESHOLD_MS = 30_000L
        // 경고는 ticker 당 1분에 한 번. 피드 장애 중에는 tick 마다 같은 경고가 난다.
        private const val STALE_WARN_INTERVAL_MS = 60_000L
        private const val MIN_DAILY_CANDLES = 21
        private const val MAX_DAILY_CANDLE_LOOKBACK = 60
        // 1분봉 폴링 주기와 캐시 TTL 때문에 경계 직후 몇 분은 오늘 일봉이 없어도 정상이다.
        private const val STALE_DAILY_CANDLE_WARN_MS = 5 * 60_000L

        /** 엔진 시작 경로(기동 복원·수동 시작·resume)에서 불리므로 어떤 값에도 예외를 던지지 않는다 — 문자열 템플릿만 쓴다. */
        internal fun startupConfigWarnings(p: TradingProperties, minOrderNotional: Double): List<ConfigWarning> = buildList {
            if (p.takeProfitPct <= p.trailingStopPct || p.takeProfitPct <= p.trailingArmPct) {
                add(ConfigWarning(Level.WARN, "takeProfitPct(${p.takeProfitPct}) <= trailingStopPct(${p.trailingStopPct}) or trailingArmPct(${p.trailingArmPct}) — take-profit 이 선행해 트레일링이 사실상 도달 불가(dead)입니다"))
            }
            if (p.trailingArmPct > 0 && p.trailingArmPct <= p.trailingStopPct) {
                add(ConfigWarning(Level.WARN, "0 < trailingArmPct(${p.trailingArmPct}) <= trailingStopPct(${p.trailingStopPct}) — arm 임계가 수학적으로 자동 충족되어 효과가 없습니다"))
            }
            // 청산 판정은 수수료 전 수익률이라 익절이 왕복 수수료 이하면 익절할 때마다 순손실이다.
            if (p.takeProfitPct <= p.roundTripFeeRate * 100) {
                add(ConfigWarning(Level.WARN, "takeProfitPct(${p.takeProfitPct}) <= roundTripFeeRate(${p.roundTripFeeRate}) × 100 — 익절해도 수수료를 빼면 순손실입니다"))
            }
            // 1회 매수액은 상한 이하라 상한이 걸리면 모든 매수가 걸린다. 매수 직전에는 DEBUG 로그뿐이라 ERROR 로 알린다.
            val maxLossPct = p.exitParamsSnapshot().maxLossPct
            when (buySkip(p.maxInvestAmount, maxLossPct, minOrderNotional)) {
                BuySkip.BELOW_MIN_ORDER -> add(ConfigWarning(Level.ERROR, "maxInvestAmount(${p.maxInvestAmount}) < 최소 주문($minOrderNotional) — 모든 매수가 건너뛰어집니다"))
                BuySkip.UNSELLABLE_AT_STOP_LOSS -> add(ConfigWarning(Level.ERROR, "maxInvestAmount(${p.maxInvestAmount}) × (1 − maxLossPct($maxLossPct)/100) < 최소 주문($minOrderNotional) — 손절가에서 팔 수 없는 크기라 모든 매수가 건너뛰어집니다"))
                null -> Unit
            }
        }
    }

    /** 엔진 시작 때 알릴 설정 조합. ERROR 는 Discord 로 간다. */
    internal data class ConfigWarning(val level: Level, val message: String)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()
    private val staleWarnAtMs = ConcurrentHashMap<String, Long>()
    private val candleWarnAtMs = ConcurrentHashMap<String, Long>()
    // 컨트롤러 스레드(start)와 runLoop 코루틴이 함께 읽고 쓴다.
    @Volatile
    private var activeTickers: List<String> = emptyList()

    fun start(
        tickers: List<String> = tradingProperties.tickerList(),
        initialStates: Map<String, TradingState> = emptyMap(),
    ) {
        if (!running.compareAndSet(false, true)) return
        // 루프 없이 running=true 로 남으면 다른 경로가 도는 엔진으로 착각하므로 되돌린다.
        try {
            val own = ownStates(initialStates)
            userTickers = tickers.toList()
            // 목록에서 빠진 티커라도 진입 흔적(보유·미해소 주문)이 있으면 싣는다. 안 실으면 아무도 청산·reconcile 하지 않는다.
            val requested = tickers.toSet()
            val restored = own.filterValues { it.hasEntryTrace() }.keys.filter { it !in requested }
            val active = (tickers + restored).distinct()
            activeTickers = active
            // 활성 ticker 의 상태만 남긴다. 먼저 채우고 나머지를 빼는 순서는 잠금 없이 읽는 getStates() 에서
            // 계속 활성인 티커가 잠깐 사라지지 않게 하려는 것이다.
            val seeded = own.filterKeys { it in active }
            seeded.forEach { (ticker, state) -> states[ticker] = state }
            states.keys.retainAll(seeded.keys)
            if (restored.isNotEmpty()) {
                // 보유 기간이 이미 지난 티커는 첫 tick 에 팔리므로 buyDate 를 같이 남긴다.
                log.warn("User {}: 사용자 목록 밖 티커를 청산될 때까지 관리합니다(ticker=buyDate): {}", userId, restored.associateWith { own[it]?.buyDate })
            }
            // 빠진 ticker 의 미해소 주문은 아무도 reconcile 하지 않으므로 사람이 봐야 한다.
            own.filterKeys { it !in active }
                .filterValues { it.hasPendingOrder() }
                .forEach { (ticker, state) ->
                    log.error(
                        "비활성 ticker {} 에 미해소 주문이 남아 있습니다(buy={}, sell={}) — 이 실행에서는 reconcile 되지 않습니다.",
                        ticker, state.pendingBuyRef(), state.pendingSellRef(),
                    )
                }
            reportStartupConfig()
            log.info("Starting trading engine for user {} ({}) with strategy: {}", userId, username, strategy.name)
            loopJob = scope.launch { runLoop() }
        } catch (e: Throwable) {
            running.set(false)
            throw e
        }
    }

    /** 다른 거래소의 행을 쓰면 그 거래소의 주문을 이 엔진이 reconcile·청산한다. 조회가 이미 거르므로 여기는 2차 방어다. */
    private fun ownStates(initialStates: Map<String, TradingState>): Map<String, TradingState> {
        val foreign = initialStates.filterValues { it.exchange != exchange }
        if (foreign.isEmpty()) return initialStates
        log.error(
            "User {}: {} 엔진이 다른 거래소의 상태를 받아 쓰지 않습니다(ticker=exchange): {}",
            userId, exchange, foreign.mapValues { it.value.exchange },
        )
        return initialStates.filterKeys { it !in foreign }
    }

    // 설정은 런타임 전체에 걸려 엔진마다 같은 문구가 나간다. 사용자를 붙이지 않아야 Discord 가 같은 알림을 하나로 묶는다.
    private fun reportStartupConfig() {
        startupConfigWarnings(tradingProperties, rules.minOrderNotional).forEach { log.atLevel(it.level).log(it.message) }
    }

    // 동시에 들어온 stop 도 같은 loopJob 이 끝날 때까지 기다리게 한다.
    private val stopMutex = Mutex()

    suspend fun stop() = stopMutex.withLock {
        val job = loopJob
        if (running.compareAndSet(true, false)) {
            log.info("Stopping trading engine for user {} ({})", userId, username)
            // 진행 중인 주문 후처리가 끝날 때까지 기다린다. 기다리지 않고 새 엔진을 띄우면 두 루프가 함께 매매한다.
            job?.cancelAndJoin()
            loopJob = null
        } else {
            job?.join()
        }
    }

    fun isRunning(): Boolean = running.get()

    fun getStates(): Map<String, TradingState> = states.toMap()

    fun getHaltedTickers(): List<String> = states.filterValues { it.halted }.keys.toList()

    /**
     * halt 를 수동으로 풀고 저장한다. 저장에 실패하면 메모리 상태도 되돌리고 예외를 던진다.
     * 그러지 않으면 재시작 때 halt 가 되살아난다.
     */
    suspend fun clearHalt(ticker: String): Boolean {
        val state = states[ticker] ?: return false
        if (!state.halted) return false
        // 확인이 필요한 주문은 pending 해제로만 푼다. 매니저가 먼저 409 로 거절한다. 이 검사도 runLoop 와 원자적이지 않아,
        // 검사 직후 생긴 review 는 halt 가 풀린 채 남고 다음 reconcile 의 markNeedsReview 가 다시 halt 한다.
        if (state.reviewReason != null) return false
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

    fun getUserTickers(): List<String> = userTickers.orEmpty().toList()

    fun getEntryTickers(): List<String> = activeTickers.filter { !isExitOnly(it) }

    fun getExitOnlyTickers(): List<String> = activeTickers.filter { isExitOnly(it) }

    /**
     * reload 가 실패했을 때 직전 목록·상태로 같은 엔진을 다시 띄운다. stop 이 루프를 join 한 뒤에 부른다.
     * start 가 states 를 바꾸므로 상태는 사본으로 넘긴다.
     */
    fun resume() = start(userTickers.orEmpty(), getStates())

    /**
     * 루프가 저장하지 못한 pending 과 고점을 다시 저장한다. 엔진이 멈추면 다음 tick 의 재시도가 없으므로
     * 버리기 전에 부른다. stop 이 루프를 join 한 뒤에 부른다.
     */
    internal suspend fun flushUnpersisted() {
        // 고점 저장이 호출자의 시간 제한을 먹지 않도록 pending 을 먼저 쓴다.
        states.values.forEach { positionManager.retryPendingPersistIfNeeded(it) }
        // pending 재저장에 성공한 state 는 전체 스냅샷이라 고점도 이미 들어갔다.
        states.values.filter { it.peakPersistFailed && !it.pendingPersistFailed }.forEach { positionManager.persistPeak(it) }
    }

    /**
     * DB 에 없을 수 있는 매도 pending. 매수는 선기록이 성공해야 주문을 보내지만 매도는 선기록이 실패해도 보낸다.
     */
    internal fun unpersistedSells(): List<TradingState> =
        states.values.filter { it.pendingPersistFailed && it.hasPendingSell() }

    private suspend fun runLoop() {
        activeTickers.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it, exchange = exchange) }
        }

        activeTickers.forEach { ticker ->
            positionManager.syncPosition(ticker, states[ticker]!!)
        }
        releaseSettledTickers()

        while (running.get() && scope.isActive) {
            try {
                if (dailyResetManager.checkAndReset(states)) {
                    // 리셋을 저장하지 않으면 재시작 때 boughtToday=true 가 복원돼 당일 진입이 막힌다.
                    states.values.forEach { positionManager.persistState(it) }
                    // 저장 뒤에 빼야 그 티커의 리셋도 저장된다.
                    releaseSettledTickers()
                }

                for (ticker in activeTickers) {
                    if (!running.get()) break
                    processTicker(ticker)
                }

                delay(tradingProperties.intervalSeconds * 1000)
            } catch (e: CancellationException) {
                throw e // 삼키면 stop 의 join 이 루프가 다시 돌 때까지 늦어진다.
            } catch (e: Exception) {
                log.error("Trading loop error (user {}): {}", userId, e.message, e)
                delay(ERROR_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 목록 밖 티커 중 더 관리할 것이 없는 것을 뺀다. 엔진 밖에서 팔린 티커는 진입 메타가 남아 있어서,
     * 지우지 않으면 사람이 다시 샀을 때 옛 buyDate 로 보유 기간 매도가 나간다.
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

    private fun isExitOnly(ticker: String): Boolean = userTickers?.let { ticker !in it } == true

    // unsynced 는 보유 여부를 아직 모른다는 뜻이라 확인될 때까지 빼지 않는다.
    private fun TradingState.mustKeep(): Boolean = position || unsynced || hasPendingOrder()

    private fun TradingState.hasEntryTrace(): Boolean = hasPendingOrder() || entryStrategy != null || buyDate != null

    private fun TradingState.hasPendingOrder(): Boolean = hasPendingBuy() || hasPendingSell()

    internal fun getRealtimePrice(ticker: String): Double? {
        // 수집이 멈추면 store 가격이 그대로 얼어붙으므로 신선한 값만 쓴다.
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

        return null
    }

    private suspend fun processTicker(ticker: String) {
        val state = states[ticker] ?: return
        // 장외에도 미해소 주문은 확정해야 다음 세션까지 결과가 밀리지 않는다.
        if (!calendar.isSessionOpen(clock.instant()) && !state.hasPendingOrder()) return
        processTicker(ticker, state, strategy)
    }

    internal suspend fun processTicker(ticker: String, state: TradingState, strategy: TradingStrategy) {
        try {
            val currentPrice = getRealtimePrice(ticker)
                ?: upbitClient.getTicker(ticker).firstOrNull()?.tradePrice
                ?: return

            // 보유 여부를 확정하지 못했으면 다시 확인한다. 계속 모르면 buy() 가 신규 진입을 막는다.
            if (state.unsynced) {
                positionManager.syncPosition(ticker, state)
            }

            // buy() 가 pending 저장 실패 상태에서는 진입하지 않으므로 재저장은 여기서 해야 한다.
            positionManager.retryPendingPersistIfNeeded(state)

            // 고점을 저장하지 않으면 재시작 뒤 트레일링 기준이 낮아진다. 하락 전환 뒤에는 새 고점이 없으므로
            // 실패한 저장은 갱신이 없어도 다시 시도한다. pending reconcile 이 길어져도 돌도록 그보다 앞에 둔다.
            if (state.position) {
                val newHigh = state.updatePeakPrice(currentPrice)
                if (newHigh || state.peakPersistFailed) positionManager.persistPeak(state)
            }

            // 미해소 주문이 있으면 먼저 reconcile 하고, 풀리지 않은 tick 에는 매수·매도를 평가하지 않는다.
            // 그래야 같은 포지션에 주문이 두 번 나가지 않는다.
            if (state.hasPendingBuy()) {
                // 막 체결된 포지션에 같은 tick 손절·익절을 평가하지 않는다.
                if (positionManager.reconcilePendingBuy(ticker, state, currentPrice) != null) {
                    return
                }
                if (state.hasPendingBuy()) return
            }

            if (state.hasPendingSell()) {
                val decidedAt = state.pendingSellSince // 확정되면 pending 이 지워지므로 미리 읽는다
                val settled = positionManager.reconcilePendingSell(ticker, state, currentPrice)
                if (settled != null) {
                    // 부분 체결은 보고하지 않는다. 남은 수량이 팔릴 때 보고해야 한 포지션에 관측이 하나만 남는다.
                    if (!state.position) {
                        shadowExitObserver?.onLiveExit(ticker, settled.price, settled.reason, settled.executedVwap, decidedAt)
                    }
                    return
                }
                if (state.hasPendingSell()) return
            }

            if (!calendar.isSessionOpen(clock.instant())) return
            runSwing(ticker, state, strategy, currentPrice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
        }
    }

    private suspend fun runSwing(ticker: String, state: TradingState, strategy: TradingStrategy, currentPrice: Double) {
        // 최소 주문 금액 미만 보유(dust)도 청산 평가는 한다. 기록된 수량이 낡았을 수 있어 실제로는 팔 수 있다.
        val dust = rules.isDust(state, currentPrice)
        if (state.position) {
            val reason = decideSell(state, currentPrice)
            // decideSell 이 peak 을 갱신한 뒤에 관측해야 같은 tick 을 본다.
            if (dust) shadowExitObserver?.forget(ticker) else shadowExitObserver?.onTick(ticker, state, currentPrice)
            val sold = if (reason != null) positionManager.sell(ticker, state, currentPrice, reason) else null
            if (sold != null) {
                shadowExitObserver?.onLiveExit(ticker, currentPrice, reason!!.name, sold.executedVwap)
                return
            }
        } else {
            shadowExitObserver?.forget(ticker)
        }

        if ((state.position && !dust) || state.boughtToday) return
        // 목록에서 빠진 티커는 다시 사지 않는다. 그 티커의 dust 는 팔 수도 흡수될 수도 없으므로 장부에서 내린다.
        if (isExitOnly(ticker)) {
            if (dust) positionManager.releaseDust(ticker, state, currentPrice)
            return
        }

        val minCandles = effectiveMinCandles(strategy)
        val storeCandles = loadStoreDailyCandles(ticker, minCandles)
        // 09:00 직후 store 의 최신 일봉은 한동안 어제 봉이다. 그대로 판정하면 방금 판 종목을 같은 신호로 되사므로
        // 오늘 봉이 있는 소스로만 판정한다.
        val dayOpen = currentTradingDayOpen()
        val currentStoreCandles = storeCandles?.takeIf { isCurrentDay(it.first().openTime, dayOpen) }
        val shouldBuy = if (currentStoreCandles != null) {
            strategy.shouldBuyNormalized(currentStoreCandles, currentPrice, tradingProperties)
        } else {
            if (storeCandles != null) {
                // 경계 직후에는 곧 다음 1분봉이 채우므로 건너뛴다. 유예 시간이 지나도 없으면 수집이 멈춘 것이라 REST 로 간다.
                if (!pastBoundaryGrace(dayOpen)) return
                warnStaleDailyCandle("store", ticker, dayOpen, storeCandles.first().openTime, "falling back to REST")
            }
            val candles = fetchDailyCandles(ticker)
            if (candles.size < minCandles) warnInsufficientCandles(ticker, strategy, candles.size)
            // Upbit 일봉도 그날 첫 체결 전에는 [0] 이 어제 봉이다.
            val newestOpen = candles.firstOrNull()?.openTimeUtcOrNull()
            if (candles.isNotEmpty() && !isCurrentDay(newestOpen, dayOpen)) {
                if (pastBoundaryGrace(dayOpen)) warnStaleDailyCandle("rest", ticker, dayOpen, newestOpen, "buy evaluation skipped")
                return
            }
            strategy.shouldBuy(candles, currentPrice, tradingProperties)
        }
        if (shouldBuy) {
            positionManager.buy(ticker, state, currentPrice, strategy.name)
        }
    }

    private suspend fun fetchDailyCandles(ticker: String): List<Candle> =
        dailyCandleCache?.get(ticker, MAX_DAILY_CANDLE_LOOKBACK) ?: upbitClient.getDayCandles(ticker, MAX_DAILY_CANDLE_LOOKBACK)

    // 손익 기준(손절 > 트레일링 > 익절)이 먼저이고 일일 리셋은 마지막이다.
    internal fun decideSell(state: TradingState, currentPrice: Double): SellReason? = when {
        positionManager.checkStopLoss(state, currentPrice) -> SellReason.STOP_LOSS
        positionManager.checkTrailingStop(state, currentPrice) -> SellReason.TRAILING_STOP
        positionManager.checkTakeProfit(state, currentPrice) -> SellReason.TAKE_PROFIT
        dailyResetManager.shouldSellForDailyReset(state) -> SellReason.DAILY_RESET
        else -> null
    }

    // 하한이 없으면 짧은 store window 를 쓰게 되어 RSI 처럼 길이에 민감한 지표 값이 달라진다.
    private fun effectiveMinCandles(strategy: TradingStrategy): Int =
        max(MIN_DAILY_CANDLES, strategy.minCandles)

    // 평가는 막지 않는다. 전략이 알아서 false 를 내지만 그 이유를 로그로 남긴다.
    private fun warnInsufficientCandles(ticker: String, strategy: TradingStrategy, actual: Int) {
        val key = "$ticker:min-candles"
        val now = System.currentTimeMillis()
        val last = candleWarnAtMs[key]
        if (last != null && now - last < STALE_WARN_INTERVAL_MS) return
        candleWarnAtMs[key] = now
        log.warn(
            "buy for {} (user {}): D1 캔들 {}개 < {} 전략 요구 {}개 — 신호 평가는 계속하나 대부분 false 다",
            ticker, userId, actual, strategy.name, effectiveMinCandles(strategy),
        )
    }

    // Upbit 일봉은 UTC 자정(KST 09:00)에 시작한다.
    private fun currentTradingDayOpen(): Instant =
        dailyResetManager.getTradingDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    // 로컬 시계가 경계에서 조금 늦어 봉이 먼저 넘어가도 오늘 봉으로 본다.
    private fun isCurrentDay(newestOpen: Instant?, dayOpen: Instant): Boolean =
        newestOpen != null && !newestOpen.isBefore(dayOpen)

    private fun pastBoundaryGrace(dayOpen: Instant): Boolean =
        clock.millis() - dayOpen.toEpochMilli() > STALE_DAILY_CANDLE_WARN_MS

    // store 와 REST 경고가 서로를 억제하지 않도록 키에 소스를 넣는다.
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

    private fun Candle.openTimeUtcOrNull(): Instant? =
        runCatching { LocalDateTime.parse(candleDateTimeUtc).toInstant(ZoneOffset.UTC) }.getOrNull()

    /** store 에 일봉이 [minCandles] 개 이상 있으면 반환하고, 모자라면 null 을 돌려 REST 로 넘긴다. */
    internal fun loadStoreDailyCandles(ticker: String, minCandles: Int = MIN_DAILY_CANDLES): List<NormalizedCandle>? {
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeCandles = marketDataStore
            ?.getCandles(exchange, normalizedMarket, CandleInterval.D1, MAX_DAILY_CANDLE_LOOKBACK)
            ?.distinctBy { it.openTime }
        return if (storeCandles != null && storeCandles.size >= minCandles) storeCandles else null
    }

}
