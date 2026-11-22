package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.MarketRules
import com.trading.bot.exchange.TradingCalendar
import com.trading.bot.exchange.UpbitMarketRules
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.PriceEventsProperties
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.strategy.TradingStrategy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

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
    // 끄면 체결을 받지 않아 바퀴만 판단한다.
    private val priceEvents: PriceEventsProperties = PriceEventsProperties(),
    // 시험이 가상 시간으로 시계·작업자를 돌린다.
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 사용자가 준 목록(bot_state.tickers). 신규 진입은 이 목록 안에서만 한다. 활성 목록에는 보유·미해소 주문 때문에
    // 남은 티커가 섞이므로 재기동 입력으로 쓰면 그 티커가 신규 진입 대상이 된다. null 은 start 전뿐이다.
    @Volatile
    private var userTickers: List<String>? = null

    companion object {
        internal val STALL_THRESHOLD: java.time.Duration = java.time.Duration.ofMinutes(5)

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

    // 끝남은 loopJob 과 시계·작업자의 완료 처리가 알린다. 처리기가 없으면 Error 가 스레드 기본 처리기로 가 stderr 에만 찍힌다.
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
    private val running = AtomicBoolean(false)

    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()

    // 컨트롤러 스레드(start)가 채우고, 작업자들이 정리될 때 원자적으로 뺀다.
    private val activeRef = AtomicReference<List<String>>(emptyList())
    private val active: List<String> get() = activeRef.get()

    // 도는 실행의 작업자. 정리된 작업자는 빠진다.
    private val workers = ConcurrentHashMap<String, TickerWorker>()

    private val buyWindow = BuyWindow { ticker -> workers[ticker]?.markProgress() }

    private val roster = object : TickerRoster {
        override fun state(ticker: String): TradingState? = states[ticker]

        override fun isExitOnly(ticker: String): Boolean = this@TradingEngine.isExitOnly(ticker)

        override fun release(ticker: String) {
            states.remove(ticker)
            workers.remove(ticker)
            activeRef.updateAndGet { tickers -> tickers - ticker }
            log.info("User {}: released ticker outside the list with nothing left to manage: {}", userId, ticker)
        }
    }

    internal val trader = TickerTrader(
        upbitClient = upbitClient,
        positionManager = positionManager,
        dailyResetManager = dailyResetManager,
        strategy = strategy,
        tradingProperties = tradingProperties,
        roster = roster,
        buyWindow = buyWindow,
        userId = userId,
        marketDataStore = marketDataStore,
        exchange = exchange,
        dailyCandleCache = dailyCandleCache,
        shadowExitObserver = shadowExitObserver,
        clock = clock,
        calendar = calendar,
        rules = rules,
    )

    // 감시 스케줄러가 읽는다. 시계는 바퀴마다, 작업자는 메시지마다 갱신한다.
    @Volatile
    private var clockProgressAt: Instant? = null

    // 엔진이 도는 중에 시계나 정리되지 않은 작업자가 끝났다. 정지 뒤 새 실행에서 지운다.
    @Volatile
    private var deadPart: String? = null

    fun start(
        tickers: List<String> = tradingProperties.tickerList(),
        initialStates: Map<String, TradingState> = emptyMap(),
    ) {
        if (!running.compareAndSet(false, true)) return
        // 감시가 이 사이에 옛 실행의 진행 시각으로 멈춤을 판정하지 않게 바로 기록한다.
        workers.clear()
        buyWindow.clearWaiting()
        deadPart = null
        clockProgressAt = clock.instant()
        // 루프 없이 running=true 로 남으면 다른 경로가 도는 엔진으로 착각하므로 되돌린다.
        try {
            val own = ownStates(initialStates)
            userTickers = tickers.toList()
            // 목록에서 빠진 티커라도 진입 흔적(보유·미해소 주문)이 있으면 싣는다. 안 실으면 아무도 청산·reconcile 하지 않는다.
            val requested = tickers.toSet()
            val restored = own.filterValues { it.hasEntryTrace() }.keys.filter { it !in requested }
            val active = (tickers + restored).distinct()
            activeRef.set(active)
            // 활성 ticker 의 상태만 남긴다. 먼저 채우고 나머지를 빼는 순서는 잠금 없이 읽는 getStates() 에서
            // 계속 활성인 티커가 잠깐 사라지지 않게 하려는 것이다.
            val seeded = own.filterKeys { it in active }
            seeded.forEach { (ticker, state) -> states[ticker] = state }
            states.keys.retainAll(seeded.keys)
            if (restored.isNotEmpty()) {
                // 보유 기간이 이미 지난 티커는 첫 tick 에 팔리므로 buyDate 를 같이 남긴다.
                log.warn("User {}: 사용자 목록 밖 티커를 청산될 때까지 관리합니다(ticker=buyDate): {}", userId, restored.associateWith { own[it]?.buyDate })
            }
            // 진입 흔적이 미해소 주문을 실으므로 여기 걸리면 안 된다. 걸린 주문은 이 실행에서 reconcile 되지 않아 확정되지 않는다.
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
            // 시계·작업자를 띄우기 전(기동 잔고 조회 등)에 끝나면 그 부분의 완료 처리가 없으므로 여기서 알린다.
            loopJob = scope.launch { runLoop() }.also { job -> job.invokeOnCompletion { cause -> if (cause != null) onPartEnded("engine", released = false, cause) } }
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
            // 진행 중인 주문 후처리가 끝날 때까지 기다린다. 기다리지 않고 새 엔진을 띄우면 옛 작업자와 새 작업자가 함께 매매한다.
            job?.cancelAndJoin()
            loopJob = null
        } else {
            job?.join()
        }
    }

    fun isRunning(): Boolean = running.get()

    /** 시계와 활성 작업자 중 가장 오래 진행하지 않은 쪽의 시각. 매수 창구 앞에서 기다리는 작업자는 뺀다. */
    fun lastProgressAt(): Instant? {
        val clockAt = clockProgressAt ?: return null
        return workers.values.filter { !buyWindow.isWaiting(it.ticker) }.map { it.progressAt }.plus(clockAt).min()
    }

    /** 도는 엔진이 [STALL_THRESHOLD] 넘게 진행하지 않았다. 정지·끝난 엔진은 false — 끝남은 완료 처리가 알린다. */
    fun isStalled(now: Instant): Boolean {
        val last = lastProgressAt() ?: return false
        return isLoopAlive() && java.time.Duration.between(last, now) > STALL_THRESHOLD
    }

    /** start 중(loopJob 대입 전)은 살아 있는 것으로 본다. 시계나 정리되지 않은 작업자가 끝났으면 죽은 것이다. */
    fun isLoopAlive(): Boolean = running.get() && loopJob?.isActive != false && deadPart == null

    fun getStates(): Map<String, TradingState> = states.toMap()

    fun getActiveTickers(): List<String> = active.toList()

    fun getUserTickers(): List<String> = userTickers.orEmpty().toList()

    fun getEntryTickers(): List<String> = active.filter { !isExitOnly(it) }

    fun getExitOnlyTickers(): List<String> = active.filter { isExitOnly(it) }

    /**
     * reload 가 실패했을 때 직전 목록·상태로 같은 엔진을 다시 띄운다. stop 이 루프를 join 한 뒤에 부른다.
     * start 가 states 를 바꾸므로 상태는 사본으로 넘긴다.
     */
    fun resume() = start(userTickers.orEmpty(), getStates())

    /**
     * 작업자가 저장하지 못한 pending 과 고점을 다시 저장한다. 엔진이 멈추면 다음 tick 의 재시도가 없으므로
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
     * 버전이 충돌한 종목의 매도도 넣는다 — 그 뒤로 기록하지 못했고, DB 의 행은 다른 쓰기가 바꿨다.
     */
    internal fun unpersistedSells(): List<TradingState> =
        states.values.filter { (it.pendingPersistFailed || it.versionConflict) && it.hasPendingSell() }

    /**
     * 종목마다 작업자를 띄우고 공용 시계가 바퀴를 보낸다. 한 작업자의 실패는 다른 작업자를 멈추지 않는다.
     * 정지는 이 코루틴을 취소하고 모든 작업자가 끝날 때까지 기다린다(주문 중이면 그 주문이 끝날 때까지).
     */
    private suspend fun runLoop(): Unit = supervisorScope {
        active.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it, exchange = exchange) }
        }
        val accounts = startupAccounts()
        for (ticker in active) {
            val worker = TickerWorker(ticker, states.getValue(ticker), trader, clock)
            workers[ticker] = worker
            launch { worker.run(accounts) }.invokeOnCompletion { cause -> onPartEnded("ticker worker $ticker", worker.released, cause) }
        }
        // 작업자가 생긴 뒤에 구독한다. 그 전의 체결은 받지 않는다 — 시작 가격은 첫 바퀴가 정한다.
        if (priceEvents.enabled && marketDataStore != null) {
            // 저장소는 내부 표기(BTC/KRW)로 싣는다. 정리된 작업자는 우편함이 닫혀 받지 않는다.
            val routes = workers.values.associateBy { MarketPair.normalize(exchange, it.ticker) }
            launch { collectTrades(marketDataStore, routes, TradeAgeMonitor(clock, userId)) }.invokeOnCompletion { cause -> onPartEnded("trade collector", released = false, cause) }
        }
        launch { runClock() }.invokeOnCompletion { cause -> onPartEnded("clock", released = false, cause) }
    }

    /** 자기 거래소의 체결을 그 종목 작업자에게 넣는다. 끝나면 체결 판단이 멈추므로 엔진이 도는 중의 끝남은 죽음이다. */
    private suspend fun collectTrades(store: MarketDataStore, routes: Map<String, TickerWorker>, ages: TradeAgeMonitor) {
        store.trades.collect { trade ->
            if (trade.exchange != exchange) return@collect
            val worker = routes[trade.market] ?: return@collect
            ages.observe(trade.tradeTime)
            worker.offerTrade(trade.price, trade.tradeTime)
        }
    }

    /** 기동 동기화에 쓸 잔고를 한 번 받아 나눠 쓴다. 실패하면 작업자가 각자 다시 묻는다. */
    private suspend fun startupAccounts(): List<Account>? =
        try {
            upbitClient.getAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Startup balance lookup for user {} failed — each ticker syncs on its own: {}", userId, e.message)
            null
        }

    private suspend fun runClock() {
        var announcedDate: LocalDate? = null
        while (true) {
            try {
                val tradingDate = dailyResetManager.getTradingDate()
                if (tradingDate != announcedDate) {
                    log.info("Trading date {} for user {}", tradingDate, userId)
                    announcedDate = tradingDate
                }
                val round = Round(tradingDate, trader.prefetchRestPrices(active))
                workers.values.forEach { it.offer(round) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Trading clock error (user {}): {}", userId, e.message, e)
            }
            // 티커가 없어도 한 바퀴는 진행이다.
            clockProgressAt = clock.instant()
            delay(tradingProperties.intervalSeconds.seconds)
        }
    }

    // Error·뜻밖의 취소로 끝나면 running 은 true 인 채 그 부분의 손절 평가가 멈춘다. 정지는 running 을 먼저 내린다.
    private fun onPartEnded(part: String, released: Boolean, cause: Throwable?) {
        if (released) return
        if (running.get()) {
            deadPart = part
            log.error("[ENGINE DEAD] {} for user {} ({}) ended without stop — 손절·청산 평가가 멈췄다. 봇을 정지한 뒤 다시 시작한다", part, userId, username, cause)
        } else if (cause != null && cause !is CancellationException) {
            // 정지 중 후처리에서 난 Error 는 scope 처리기가 버리므로 여기서만 남는다.
            log.warn("{} for user {} ({}) failed while stopping", part, userId, username, cause)
        }
    }

    private fun isExitOnly(ticker: String): Boolean = userTickers?.let { ticker !in it } == true
}
