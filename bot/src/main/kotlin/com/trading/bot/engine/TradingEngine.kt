package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.strategy.TradingStrategy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class TradingEngine(
    private val upbitClient: UpbitClient,
    private val positionManager: PositionManager,
    private val dailyResetManager: DailyResetManager,
    private val tradeExecutionService: TradeExecutionService,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val userId: Long = 0,
    private val username: String = "",
    private val discordWebhookUrl: String? = null,
    private val webSocketClient: UpbitWebSocketClient? = null,
    private val marketDataStore: MarketDataStore? = null,
    private val exchange: Exchange = Exchange.UPBIT,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ERROR_RETRY_DELAY_MS = 60_000L
        private const val WS_PRICE_STALE_THRESHOLD_MS = 30_000L
        // 데드크로스(5/20) 최소 캔들 = longPeriod + 1. store D1 오염(같은 openTime 누적) 대비 lookback 은 넉넉히.
        private const val MIN_EXIT_CANDLES = 21
        private const val MAX_EXIT_CANDLE_LOOKBACK = 60
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    @Volatile
    private var loopJob: Job? = null
    private val states = ConcurrentHashMap<String, TradingState>()
    // 컨트롤러 스레드(setStrategy/start)와 runLoop 코루틴이 함께 접근 → 가시성 보장.
    @Volatile
    private var activeStrategy: TradingStrategy? = null
    @Volatile
    private var activeTickers: List<String> = emptyList()

    init {
        activeStrategy = strategies.find { it.name == tradingProperties.strategy }
            ?: strategies.firstOrNull()
    }

    fun start(tickers: List<String> = tradingProperties.tickerList()) {
        if (running.compareAndSet(false, true)) {
            activeTickers = tickers
            log.info("Starting trading engine for user {} ({}) with strategy: {}", userId, username, activeStrategy?.name)
            loopJob = scope.launch { runLoop() }
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping trading engine for user {} ({})", userId, username)
            // 실행 중인 루프 코루틴을 즉시 취소해 delay 대기/리소스 잔존 방지 (scope 는 재시작 위해 유지).
            loopJob?.cancel()
            loopJob = null
        }
    }

    fun isRunning(): Boolean = running.get()

    fun getStates(): Map<String, TradingState> = states.toMap()

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
                dailyResetManager.checkAndReset(states)

                for (ticker in activeTickers) {
                    if (!running.get()) break
                    processTicker(ticker)
                }

                delay(tradingProperties.intervalSeconds * 1000)
            } catch (e: Exception) {
                log.error("Trading loop error (user {}): {}", userId, e.message, e)
                delay(ERROR_RETRY_DELAY_MS)
            }
        }
    }

    private fun getRealtimePrice(ticker: String): Double? {
        // Prefer the in-process MarketDataStore
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storePrice = marketDataStore?.getLatestPrice(exchange, normalizedMarket)
        if (storePrice != null) return storePrice

        // Fallback to WebSocket
        val wsPrice = webSocketClient?.latestPrice(ticker)
        if (wsPrice != null && System.currentTimeMillis() - wsPrice.timestamp < WS_PRICE_STALE_THRESHOLD_MS) {
            return wsPrice.tradePrice
        }
        return null
    }

    private suspend fun processTicker(ticker: String) {
        val state = states[ticker] ?: return
        val strategy = activeStrategy ?: return

        try {
            val currentPrice = getRealtimePrice(ticker)
                ?: upbitClient.getTicker(ticker).firstOrNull()?.tradePrice
                ?: return

            if (state.position) {
                state.updatePeakPrice(currentPrice)
                val reason = decideSell(state, currentPrice, ticker, strategy)
                if (reason != null) {
                    val sellRecord = positionManager.sell(ticker, state, currentPrice, reason)
                    if (sellRecord != null) {
                        onTrade(sellRecord)
                        return
                    }
                }
            }

            // 당일 1회 진입: 이미 보유 중이거나 오늘 매수했으면 신규 매수 평가 자체를 생략.
            if (state.position || state.boughtToday) return

            val normalizedMarket = MarketPair.normalize(exchange, ticker)
            val storeCandles = marketDataStore?.getCandles(exchange, normalizedMarket, CandleInterval.D1, 30)
            val shouldBuy = if (storeCandles != null && storeCandles.size >= 2) {
                strategy.shouldBuyNormalized(storeCandles, currentPrice, tradingProperties)
            } else {
                val candles = upbitClient.getDayCandles(ticker, 30)
                strategy.shouldBuy(candles, currentPrice, tradingProperties)
            }
            if (shouldBuy) {
                val buyRecord = positionManager.buy(ticker, state, currentPrice, strategy.name)
                if (buyRecord != null) {
                    onTrade(buyRecord)
                }
            }
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
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
        return runCatching { evaluateChartExit(ticker, currentPrice, strategy) }
            .getOrElse {
                log.debug("chartExit evaluation failed for {}: {}", ticker, it.message)
                false
            }
    }

    /**
     * 차트청산 신호 평가. store 의 D1 버퍼는 CandleAggregator 가 같은 날 분봉을 반복 ingest 해
     * openTime 중복이 쌓일 수 있으므로(MarketDataStore.addCandle 은 dedup 없음) distinct 후 충분할 때만 사용,
     * 부족하면 매수 경로와 동일한 getDayCandles REST 폴백. 그래도 부족하면 skip(false).
     */
    internal suspend fun evaluateChartExit(
        ticker: String,
        currentPrice: Double,
        strategy: TradingStrategy,
    ): Boolean {
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeCandles = marketDataStore
            ?.getCandles(exchange, normalizedMarket, CandleInterval.D1, MAX_EXIT_CANDLE_LOOKBACK)
            ?.distinctBy { it.openTime }
        if (storeCandles != null && storeCandles.size >= MIN_EXIT_CANDLES) {
            return strategy.shouldSellNormalized(storeCandles, currentPrice, tradingProperties)
        }
        val candles = upbitClient.getDayCandles(ticker, 30)
        if (candles.size < MIN_EXIT_CANDLES) {
            log.debug("chartExit skipped for {}: insufficient D1 candles ({})", ticker, candles.size)
            return false
        }
        return strategy.shouldSell(candles, currentPrice, tradingProperties)
    }

    private suspend fun onTrade(record: TradeRecord) {
        tradeExecutionService.saveAndNotify(
            record = record.copy(userId = userId),
            client = upbitClient,
            username = username,
            discordWebhookUrl = discordWebhookUrl,
        )
    }
}
