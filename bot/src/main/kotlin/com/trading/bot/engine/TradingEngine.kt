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
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    private val states = ConcurrentHashMap<String, TradingState>()
    private var activeStrategy: TradingStrategy? = null
    private var activeTickers: List<String> = emptyList()

    init {
        activeStrategy = strategies.find { it.name == tradingProperties.strategy }
            ?: strategies.firstOrNull()
    }

    fun start(tickers: List<String> = tradingProperties.tickerList()) {
        if (running.compareAndSet(false, true)) {
            activeTickers = tickers
            log.info("Starting trading engine for user {} ({}) with strategy: {}", userId, username, activeStrategy?.name)
            scope.launch { runLoop() }
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping trading engine for user {} ({})", userId, username)
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
                val sellRecord = when {
                    positionManager.checkStopLoss(state, currentPrice) ->
                        positionManager.sell(ticker, state, currentPrice, SellReason.STOP_LOSS)
                    positionManager.checkTrailingStop(state, currentPrice) ->
                        positionManager.sell(ticker, state, currentPrice, SellReason.TRAILING_STOP)
                    positionManager.checkTakeProfit(state, currentPrice) ->
                        positionManager.sell(ticker, state, currentPrice, SellReason.TAKE_PROFIT)
                    dailyResetManager.shouldSellForDailyReset(state) ->
                        positionManager.sell(ticker, state, currentPrice, SellReason.DAILY_RESET)
                    else -> null
                }
                if (sellRecord != null) {
                    onTrade(sellRecord)
                    return
                }
            }

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

    private suspend fun onTrade(record: TradeRecord) {
        tradeExecutionService.saveAndNotify(
            record = record.copy(userId = userId),
            client = upbitClient,
            username = username,
            discordWebhookUrl = discordWebhookUrl,
        )
    }
}
