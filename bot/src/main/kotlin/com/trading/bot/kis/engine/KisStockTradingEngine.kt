package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.marketdata.FallbackCache
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
import kotlinx.coroutines.CancellationException
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
    // price/candle 을 분리해야 캔들 실패가 가격 조회를 막거나, 가격 성공이 캔들의 실패 누적을 지우지 않는다.
    private val priceCache = FallbackCache<Long>(PRICE_TTL_MS)
    private val candleCache = FallbackCache<List<NormalizedCandle>>(CANDLE_TTL_MS)

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

        candleCache.get(symbol)?.let { return it }
        if (!candleCache.shouldAttempt(symbol)) return cached

        return try {
            val today = LocalDate.now(KST)
            val from = today.minusDays(CANDLE_BACKFILL_DAYS)
            val fetched = client.getDailyCandles(symbol, from.format(YMD), today.format(YMD))
                .map { StockCandleAdapter.toNormalized(symbol, it, CandleInterval.D1) }
            candleCache.put(symbol, fetched)
            candleCache.recordSuccess(symbol)
            fetched
        } catch (e: CancellationException) {
            throw e // 엔진 정지에 의한 취소는 API 실패가 아니다 — backoff 에 기록하지 않는다.
        } catch (e: Exception) {
            candleCache.recordFailure(symbol)
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

        priceCache.get(symbol)?.let { return it }
        if (!priceCache.shouldAttempt(symbol)) return null

        return try {
            val price = client.getCurrentPrice(symbol)
            priceCache.put(symbol, price)
            priceCache.recordSuccess(symbol)
            price
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            priceCache.recordFailure(symbol)
            log.warn("price fetch failed {}: {}", symbol, e.message)
            null
        }
    }

    /** 외부(store) timestamp 는 단조시계로 바꿀 수 없다 — 대신 미래 시각도 stale 로 본다. */
    private fun isFresh(timestamp: Instant, ttlMs: Long): Boolean {
        val age = System.currentTimeMillis() - timestamp.toEpochMilli()
        return age in 0 until ttlMs
    }

    private companion object {
        const val MIN_CANDLES = 20
        const val CANDLE_LOOKBACK = 60
        const val CANDLE_BACKFILL_DAYS = 100L

        // KisMarketDataService 의 폴링 주기(price 3s / candle 300s)보다 약간 길게 — 폴러가 살아있으면
        // store 히트로 끝나고, 죽었을 때만 폴백이 이 TTL 간격으로 돈다.
        const val PRICE_TTL_MS = 5_000L
        const val CANDLE_TTL_MS = 300_000L


        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
