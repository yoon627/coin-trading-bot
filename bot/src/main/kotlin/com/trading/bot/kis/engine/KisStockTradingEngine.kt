package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.marketdata.StockCandleAdapter
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * 사용자별 주식 자동매매 루프. 크립토 TradingEngine 의 상태기계/매도 우선순위를 미러하되, 주문은 WAL([StockPositionManager])
 * 경유. 장외엔 skip(KisMarketCalendar). 전략은 공용 7종(NormalizedCandle) 무변경 — 일봉 기반(MVP).
 */
class KisStockTradingEngine(
    private val userId: Long,
    private val positionManager: StockPositionManager,
    private val client: KisClient,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val marketDataStore: MarketDataStore,
    private val marketCalendar: KisMarketCalendar,
    private val liveEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val positions = ConcurrentHashMap<String, StockPosition>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var activeSymbols: List<String> = emptyList()
    @Volatile private var activeStrategy: TradingStrategy =
        strategies.firstOrNull() ?: error("no strategies configured")
    @Volatile private var loopJob: Job? = null

    fun start(symbols: List<String>) {
        activeSymbols = symbols
        symbols.forEach { positions.computeIfAbsent(it) { s -> StockPosition(s) } }
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
        log.info("KIS engine started user={} symbols={} strategy={} live={}", userId, symbols, activeStrategy.name, liveEnabled)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun isRunning(): Boolean = loopJob?.isActive == true
    fun getActiveSymbols(): List<String> = activeSymbols
    fun getActiveStrategyName(): String = activeStrategy.name
    fun getPositions(): Map<String, StockPosition> = positions

    fun setStrategy(name: String): Boolean {
        val s = strategies.find { it.name == name } ?: return false
        activeStrategy = s
        return true
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                if (marketCalendar.isTradingNow()) {
                    val holdings = if (liveEnabled) runCatching { client.getHoldings() }.getOrDefault(emptyList()) else emptyList()
                    for (symbol in activeSymbols) {
                        processSymbol(symbol, holdings)
                    }
                }
            } catch (e: Exception) {
                log.error("KIS engine loop error user={}: {}", userId, e.message, e)
            }
            delay(tradingProperties.intervalSeconds * 1000)
        }
    }

    private suspend fun processSymbol(symbol: String, holdings: List<com.trading.bot.kis.domain.KisHolding>) {
        val pos = positions.computeIfAbsent(symbol) { StockPosition(it) }
        if (liveEnabled) {
            val h = holdings.find { it.pdno == symbol }
            positionManager.syncFromHoldings(pos, h?.heldQty() ?: 0L, h?.avgBuyPrice() ?: 0.0)
        }
        val price = currentPrice(symbol) ?: return

        if (pos.position) {
            decideSell(symbol, pos, price)?.let { reason ->
                positionManager.submitSell(pos, reason, liveEnabled)
            }
        } else if (!pos.boughtToday) {
            if (shouldBuy(symbol, price)) {
                positionManager.submitBuy(pos, price, activeStrategy.name, liveEnabled)
            }
        }
    }

    internal suspend fun decideSell(symbol: String, pos: StockPosition, price: Long): SellReason? = when {
        positionManager.checkStopLoss(pos, price) -> SellReason.STOP_LOSS
        positionManager.checkTrailingStop(pos, price) -> SellReason.TRAILING_STOP
        positionManager.checkTakeProfit(pos, price) -> SellReason.TAKE_PROFIT
        tradingProperties.chartExitEnabled && chartExitTriggered(symbol, price) -> SellReason.CHART_EXIT
        else -> null
    }

    private suspend fun chartExitTriggered(symbol: String, price: Long): Boolean {
        val candles = candles(symbol)
        if (candles.size < MIN_CANDLES) return false
        return activeStrategy.shouldSellNormalized(candles, price.toDouble(), tradingProperties)
    }

    private suspend fun shouldBuy(symbol: String, price: Long): Boolean {
        val candles = candles(symbol)
        if (candles.size < MIN_CANDLES) return false
        return activeStrategy.shouldBuyNormalized(candles, price.toDouble(), tradingProperties)
    }

    /** store(일봉) 우선, 부족하면 REST 폴백(getDailyCandles → NormalizedCandle). */
    private suspend fun candles(symbol: String): List<NormalizedCandle> {
        val cached = marketDataStore.getCandles(Exchange.KIS, symbol, CandleInterval.D1, CANDLE_LOOKBACK)
        if (cached.size >= MIN_CANDLES) return cached
        return try {
            val today = LocalDate.now(KST)
            val from = today.minusDays(CANDLE_BACKFILL_DAYS)
            client.getDailyCandles(symbol, from.format(YMD), today.format(YMD))
                .map { StockCandleAdapter.toNormalized(symbol, it, CandleInterval.D1) }
        } catch (e: Exception) {
            log.warn("candle REST fallback failed {}: {}", symbol, e.message)
            cached
        }
    }

    private suspend fun currentPrice(symbol: String): Long? {
        val ticker = marketDataStore.getLatestTicker(Exchange.KIS, symbol)
        if (ticker != null && ticker.price > 0) return ticker.price.toLong()
        return try {
            client.getCurrentPrice(symbol)
        } catch (e: Exception) {
            log.warn("price fetch failed {}: {}", symbol, e.message)
            null
        }
    }

    private companion object {
        const val MIN_CANDLES = 20
        const val CANDLE_LOOKBACK = 60
        const val CANDLE_BACKFILL_DAYS = 100L
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
