package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.strategy.TradingStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    private val log = LoggerFactory.getLogger(javaClass)
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
                delay(60_000)
            }
        }
    }

    private fun getRealtimePrice(ticker: String): Double? {
        val wsPrice = webSocketClient?.latestPrice(ticker)
        if (wsPrice != null && System.currentTimeMillis() - wsPrice.timestamp < 30_000) {
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

            val candles = upbitClient.getDayCandles(ticker, 30)
            val shouldBuy = strategy.shouldBuy(candles, currentPrice, tradingProperties)
            if (shouldBuy) {
                val buyRecord = positionManager.buy(ticker, state, currentPrice, strategy.name)
                if (buyRecord != null) {
                    onTrade(buyRecord)
                }
            }
        } catch (e: Exception) {
            log.error("Error processing {} (user {}): {}", ticker, userId, e.message)
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
