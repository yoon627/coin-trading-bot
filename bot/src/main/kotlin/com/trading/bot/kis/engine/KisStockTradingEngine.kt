package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.marketdata.StockCandleAdapter
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.config.TradingProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.strategy.TradingStrategy
import java.time.Instant
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

    // REST 폴백 전용 로컬 캐시(M-D). store 는 @Scheduled 폴러가 단독 writer 로 유지한다.
    private val priceCache = ConcurrentHashMap<String, TimedValue<Long>>()
    private val candleCache = ConcurrentHashMap<String, TimedValue<List<NormalizedCandle>>>()
    private val fallbackFailures = ConcurrentHashMap<String, FallbackFailure>()

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

    private var lastTradingDay: LocalDate? = null

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                if (marketCalendar.isTradingNow()) {
                    resetDailyIfNeeded()
                    // live: 잔고조회 실패면 빈 holdings 로 sync 하지 말고 패스 skip(손절 누락 방지).
                    val holdings: List<KisHolding>? =
                        if (liveEnabled) runCatching { client.getHoldings() }.getOrNull() else emptyList()
                    if (holdings == null) {
                        log.warn("KIS engine user={}: holdings fetch failed — skip pass", userId)
                    } else {
                        for (symbol in activeSymbols) processSymbol(symbol, holdings)
                    }
                }
            } catch (e: Exception) {
                log.error("KIS engine loop error user={}: {}", userId, e.message, e)
            }
            delay(tradingProperties.intervalSeconds * 1000)
        }
    }

    /** 거래일(KST) 경계에서 boughtToday 리셋 — 전일 매수/매도 후 당일 정상 재평가(M-A). */
    internal fun resetDailyIfNeeded() {
        val today = LocalDate.now(KST)
        if (today != lastTradingDay) {
            positions.values.forEach { it.boughtToday = false }
            lastTradingDay = today
        }
    }

    private suspend fun processSymbol(symbol: String, holdings: List<KisHolding>) {
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

    /**
     * store(일봉) 우선, 부족하면 REST 폴백. 폴백 결과는 **엔진 로컬 캐시**에만 담는다 — store 에 쓰면
     * @Scheduled 폴러와 writer 가 둘이 되는데 MarketDataStore 의 addCandle(put+size+trim)은 비원자적이라
     * 단일 writer 를 전제한다(M-D).
     */
    private suspend fun candles(symbol: String): List<NormalizedCandle> {
        val cached = marketDataStore.getCandles(Exchange.KIS, symbol, CandleInterval.D1, CANDLE_LOOKBACK)
        if (cached.size >= MIN_CANDLES) return cached

        candleCache[symbol]?.takeIf { it.isFresh(CANDLE_TTL_MS) }?.let { return it.value }
        if (!shouldAttemptFallback(symbol)) return cached

        return try {
            val today = LocalDate.now(KST)
            val from = today.minusDays(CANDLE_BACKFILL_DAYS)
            val fetched = client.getDailyCandles(symbol, from.format(YMD), today.format(YMD))
                .map { StockCandleAdapter.toNormalized(symbol, it, CandleInterval.D1) }
            candleCache[symbol] = TimedValue(fetched)
            onFallbackSuccess(symbol)
            fetched
        } catch (e: Exception) {
            onFallbackFailure(symbol)
            log.warn("candle REST fallback failed {}: {}", symbol, e.message)
            cached
        }
    }

    /** store ticker 는 신선할 때만 신뢰한다 — 폴링이 멈추면 낡은 가격으로 매매하게 된다(M-D). */
    private suspend fun currentPrice(symbol: String): Long? {
        val ticker = marketDataStore.getLatestTicker(Exchange.KIS, symbol)
        if (ticker != null && ticker.price > 0 && isFresh(ticker.timestamp, PRICE_TTL_MS)) {
            return ticker.price.toLong()
        }

        priceCache[symbol]?.takeIf { it.isFresh(PRICE_TTL_MS) }?.let { return it.value }
        if (!shouldAttemptFallback(symbol)) return null

        return try {
            val price = client.getCurrentPrice(symbol)
            priceCache[symbol] = TimedValue(price)
            onFallbackSuccess(symbol)
            price
        } catch (e: Exception) {
            onFallbackFailure(symbol)
            log.warn("price fetch failed {}: {}", symbol, e.message)
            null
        }
    }

    // ---- REST 폴백 캐시·backoff (M-D) ----

    private class TimedValue<T>(val value: T, private val at: Long = System.currentTimeMillis()) {
        fun isFresh(ttlMs: Long): Boolean = System.currentTimeMillis() - at < ttlMs
    }

    private class FallbackFailure(val count: Int, val retryAtMs: Long)

    private fun isFresh(timestamp: Instant, ttlMs: Long): Boolean =
        System.currentTimeMillis() - timestamp.toEpochMilli() < ttlMs

    /** 연속 실패 심볼은 지수 backoff — rate limit 을 실패 재시도로 더 악화시키지 않는다. */
    private fun shouldAttemptFallback(symbol: String): Boolean {
        val f = fallbackFailures[symbol] ?: return true
        return System.currentTimeMillis() >= f.retryAtMs
    }

    private fun onFallbackSuccess(symbol: String) {
        fallbackFailures.remove(symbol)
    }

    private fun onFallbackFailure(symbol: String) {
        val prev = fallbackFailures[symbol]?.count ?: 0
        val count = prev + 1
        val delay = minOf(BACKOFF_BASE_MS shl minOf(count - 1, BACKOFF_MAX_SHIFT), BACKOFF_CAP_MS)
        fallbackFailures[symbol] = FallbackFailure(count, System.currentTimeMillis() + delay)
    }

    private companion object {
        const val MIN_CANDLES = 20
        const val CANDLE_LOOKBACK = 60
        const val CANDLE_BACKFILL_DAYS = 100L

        // KisMarketDataService 의 폴링 주기(price 3s / candle 300s)보다 약간 길게 — 폴러가 살아있으면
        // store 히트로 끝나고, 죽었을 때만 폴백이 이 TTL 간격으로 돈다.
        const val PRICE_TTL_MS = 5_000L
        const val CANDLE_TTL_MS = 300_000L

        const val BACKOFF_BASE_MS = 1_000L
        const val BACKOFF_CAP_MS = 60_000L
        const val BACKOFF_MAX_SHIFT = 6

        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
