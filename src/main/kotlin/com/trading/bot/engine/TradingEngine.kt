package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradingState
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.strategy.TradingStrategy
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Component
class TradingEngine(
    private val upbitClient: UpbitClient,
    private val positionManager: PositionManager,
    private val dailyResetManager: DailyResetManager,
    private val tradeRecordRepository: TradeRecordRepository,
    private val discordNotifier: DiscordNotifier,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val running = AtomicBoolean(false)
    private val states = ConcurrentHashMap<String, TradingState>()
    private var activeStrategy: TradingStrategy? = null

    @PostConstruct
    fun init() {
        activeStrategy = strategies.find { it.name == tradingProperties.strategy }
            ?: strategies.first().also {
                log.warn("Strategy '{}' not found, using '{}'", tradingProperties.strategy, it.name)
            }

        if (tradingProperties.autoStart) {
            start()
        }
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting trading engine with strategy: {}", activeStrategy?.name)
            scope.launch { runLoop() }
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping trading engine")
        }
    }

    fun isRunning(): Boolean = running.get()

    fun getStates(): Map<String, TradingState> = states.toMap()

    fun getActiveStrategyName(): String = activeStrategy?.name ?: "none"

    fun setStrategy(strategyName: String): Boolean {
        val strategy = strategies.find { it.name == strategyName } ?: return false
        activeStrategy = strategy
        log.info("Strategy changed to: {}", strategyName)
        return true
    }

    private suspend fun runLoop() {
        val tickers = tradingProperties.tickerList()
        tickers.forEach { ticker ->
            states.computeIfAbsent(ticker) { TradingState(it) }
        }

        // Sync existing positions on start
        tickers.forEach { ticker ->
            positionManager.syncPosition(ticker, states[ticker]!!)
        }

        discordNotifier.sendMessage("Trading bot started — strategy: ${activeStrategy?.name}, tickers: $tickers")

        while (running.get() && scope.isActive) {
            try {
                dailyResetManager.checkAndReset(states)

                for (ticker in tickers) {
                    if (!running.get()) break
                    processTicker(ticker)
                }

                delay(tradingProperties.intervalSeconds * 1000)
            } catch (e: Exception) {
                log.error("Trading loop error: {}", e.message, e)
                discordNotifier.sendMessage("Error: ${e.message}")
                delay(60_000)
            }
        }

        discordNotifier.sendMessage("Trading bot stopped")
    }

    private suspend fun processTicker(ticker: String) {
        val state = states[ticker] ?: return
        val strategy = activeStrategy ?: return

        try {
            val tickers = upbitClient.getTicker(ticker)
            val currentPrice = tickers.firstOrNull()?.tradePrice ?: return

            // Check sell conditions first
            if (state.position) {
                val sellRecord = when {
                    positionManager.checkStopLoss(state, currentPrice) ->
                        positionManager.sell(ticker, state, currentPrice, SellReason.STOP_LOSS)
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

            // Check buy conditions
            if (!state.position && !state.boughtToday) {
                val candles = upbitClient.getDayCandles(ticker, 30)
                val shouldBuy = strategy.shouldBuy(candles, currentPrice, tradingProperties)
                if (shouldBuy) {
                    val buyRecord = positionManager.buy(ticker, state, currentPrice, strategy.name)
                    if (buyRecord != null) {
                        onTrade(buyRecord)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error processing {}: {}", ticker, e.message)
        }
    }

    private suspend fun onTrade(record: TradeRecord) {
        tradeRecordRepository.save(record)
        val message = when (record.side) {
            com.trading.bot.domain.TradeSide.BUY ->
                "[매수] ${record.ticker} / 가격: %,.0f원 / 금액: %,.0f원".format(record.price, record.totalAmount)
            com.trading.bot.domain.TradeSide.SELL ->
                "[매도 - ${record.reason}] ${record.ticker} / 가격: %,.0f원 / 수익률: %+.2f%%".format(record.price, record.pnlPercent ?: 0.0)
        }
        discordNotifier.sendMessage(message)
    }
}
