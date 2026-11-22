package com.trading.bot.engine

import com.trading.bot.client.UpbitApiException
import com.trading.bot.domain.Account
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.ExchangeClient
import com.trading.bot.exchange.MarketRules
import com.trading.bot.exchange.TradingCalendar
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.math.max

/** 트레이더가 읽고 바꾸는 엔진의 관리 목록. 상태·작업자·활성 목록은 엔진이 가진다. */
internal interface TickerRoster {
    fun state(ticker: String): TradingState?

    /** 사용자 목록 밖에서 청산될 때까지만 관리하는 티커. */
    fun isExitOnly(ticker: String): Boolean

    /** 그 티커의 상태·작업자·활성 목록 항목을 관리에서 뺀다. */
    fun release(ticker: String)
}

/** 종목 하나의 매매 판단과 주문. 작업자들이 함께 쓰고, 종목 사이에 나누는 것은 매수 창구와 REST 시세 기록뿐이다. */
internal class TickerTrader(
    private val upbitClient: ExchangeClient,
    private val positionManager: PositionManager,
    private val dailyResetManager: DailyResetManager,
    private val strategy: TradingStrategy,
    private val tradingProperties: TradingProperties,
    private val roster: TickerRoster,
    private val buyWindow: BuyWindow,
    private val userId: Long,
    private val marketDataStore: MarketDataStore?,
    private val exchange: Exchange,
    private val dailyCandleCache: DailyCandleCache?,
    private val shadowExitObserver: ShadowExitObserver?,
    private val clock: Clock,
    private val calendar: TradingCalendar,
    private val rules: MarketRules,
) : TickerWork {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val PRICE_STALE_THRESHOLD_MS = 30_000L

        // 경고는 ticker 당 1분에 한 번. 피드 장애 중에는 tick 마다 같은 경고가 난다.
        private const val STALE_WARN_INTERVAL_MS = 60_000L
        private const val MIN_DAILY_CANDLES = 21
        private const val MAX_DAILY_CANDLE_LOOKBACK = 60

        // 1분봉 폴링 주기와 캐시 TTL 때문에 경계 직후 몇 분은 오늘 일봉이 없어도 정상이다.
        private const val STALE_DAILY_CANDLE_WARN_MS = 5 * 60_000L
    }

    private val staleWarnAtMs = ConcurrentHashMap<String, Long>()
    private val candleWarnAtMs = ConcurrentHashMap<String, Long>()

    /**
     * 따로 물어도 거래소가 모른다고 한(404) 마켓. 묶음에 넣으면 묶음 전체가 실패하므로 묶음에서만 뺀다 — 보유·미해소 주문이 있으면
     * 일시적인 404 였을 수 있어 손절을 위해 계속 따로 묻는다. 시계와 작업자들이 함께 쓴다.
     */
    private val unknownRestMarkets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun sync(ticker: String, state: TradingState, accounts: List<Account>?) =
        positionManager.syncPosition(ticker, state, accounts)

    override suspend fun resetDay(state: TradingState, tradingDate: LocalDate) {
        // 재시작하면 마지막 리셋일을 모르므로 해제 여부는 state 의 boughtDate 로 판단해 당일 재매수를 막는다.
        state.resetDaily(tradingDate)
        positionManager.persistState(state)
    }

    /**
     * 목록 밖 티커가 더 관리할 것이 없으면 뺀다. 엔진 밖에서 팔린 티커는 진입 메타가 남아 있어서,
     * 지우지 않으면 사람이 다시 샀을 때 옛 buyDate 로 보유 기간 매도가 나간다. 그 종목의 작업자만 부른다.
     */
    override suspend fun releaseIfSettled(ticker: String, state: TradingState): Boolean {
        if (!roster.isExitOnly(ticker) || state.mustKeep()) return false
        if (state.hasEntryTrace()) {
            state.clearEntryMeta()
            positionManager.persistState(state)
        }
        roster.release(ticker)
        return true
    }

    override suspend fun process(ticker: String, state: TradingState, round: Round) {
        if (!willProcess(ticker)) return
        processTicker(ticker, state, strategy, round.rest)
    }

    /** 시세가 낡은 활성 종목을 라운드 앞에서 한 번에 묻는다. 시계만 부른다. */
    suspend fun prefetchRestPrices(active: List<String>): RestRound {
        val markets = active.filter { it !in unknownRestMarkets && willProcess(it) && getRealtimePrice(it) == null }
        if (markets.isEmpty()) return RestRound(emptyMap())
        val prices = fetchRestPrices(markets)
        return RestRound(markets.associateWith { prices[it] })
    }

    private suspend fun restPrice(ticker: String, round: RestRound?): Double? {
        if (ticker in unknownRestMarkets) {
            return if (roster.state(ticker)?.let { it.position || it.hasPendingOrder() } == true) fetchRestPrices(listOf(ticker))[ticker] else null
        }
        if (round != null && round.asked(ticker)) return round.price(ticker)
        return fetchRestPrices(listOf(ticker))[ticker]
    }

    /**
     * WS 시세가 끊기면 모든 종목이 여기로 온다. 종목마다 따로 부르면 IP 당 ticker 한도를 넘어 429 가 쌓이고 418(IP 차단)이 되면
     * 차단 동안 손절 평가가 멈추므로, 시세가 낡은 종목을 한 번에 묶어 받는다. 묶음이 한도·장애로 실패하면 그 라운드는 REST 시세 없이
     * 넘긴다(따로 다시 부르면 차단으로 가는 부하를 키운다). 마켓 하나가 틀려(404) 묶음이 실패했으면 따로 받고 그 마켓은 묶음에서 뺀다.
     */
    private suspend fun fetchRestPrices(markets: List<String>): Map<String, Double> =
        try {
            val tickers = upbitClient.getTicker(markets.joinToString(","))
            // 한 마켓만 물으면 응답 하나가 그 마켓이다.
            if (markets.size == 1) tickers.firstOrNull()?.let { mapOf(markets.single() to it.tradePrice) }.orEmpty()
            else tickers.filter { it.market in markets }.associate { it.market to it.tradePrice }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (markets.size > 1 && e.isMarketNotFound()) {
                markets.mapNotNull { m -> singleRestPrice(m)?.let { m to it } }.toMap()
            } else {
                if (markets.size == 1 && e.isMarketNotFound() && unknownRestMarkets.add(markets.single())) {
                    log.warn("{} is unknown to the exchange — excluded from batched REST ticker fallback (user {})", markets.single(), userId)
                }
                log.error("REST ticker fallback for {} failed (user {}): {}", markets.joinToString(","), userId, e.message, e)
                emptyMap()
            }
        }

    private suspend fun singleRestPrice(market: String): Double? = fetchRestPrices(listOf(market))[market]

    private fun Throwable.isMarketNotFound(): Boolean = this is UpbitApiException && statusCode == 404

    internal fun getRealtimePrice(ticker: String): Double? {
        // 수집이 멈추면 store 가격이 그대로 얼어붙으므로 신선한 값만 쓴다.
        val normalizedMarket = MarketPair.normalize(exchange, ticker)
        val storeTicker = marketDataStore?.getLatestTicker(exchange, normalizedMarket)
        if (storeTicker != null) {
            val now = clock.millis()
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

    // 장외에도 미해소 주문은 확정해야 다음 세션까지 결과가 밀리지 않는다.
    private fun willProcess(ticker: String): Boolean {
        val state = roster.state(ticker) ?: return false
        return calendar.isSessionOpen(clock.instant()) || state.hasPendingOrder()
    }

    internal suspend fun processTicker(ticker: String, state: TradingState, strategy: TradingStrategy, round: RestRound? = null) {
        try {
            val currentPrice = getRealtimePrice(ticker) ?: restPrice(ticker, round) ?: return

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
            // 충돌한 종목은 체결을 기록할 수 없어 reconcile 이 tick 마다 같은 실패를 올린다. 첫 충돌에 이미 알렸다.
            if (state.alreadyConflicted(e)) {
                log.debug("Processing {} (user {}) stopped at the state version conflict", ticker, userId)
            } else {
                log.error("Error processing {} (user {}): {}", ticker, userId, e.message, e)
            }
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
        if (roster.isExitOnly(ticker)) {
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
        if (shouldBuy) buyThroughWindow(ticker, state, currentPrice)
    }

    private suspend fun buyThroughWindow(ticker: String, state: TradingState, currentPrice: Double) {
        buyWindow.withWindow(ticker) { positionManager.buy(ticker, state, currentPrice, strategy.name) }
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
        val now = clock.millis()
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
        val now = clock.millis()
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

// unsynced 는 보유 여부를 아직 모른다는 뜻이라 확인될 때까지 빼지 않는다.
private fun TradingState.mustKeep(): Boolean = position || unsynced || hasPendingOrder()

// 저장된 봇 장부가 남아 있어도 관리할 보유다.
internal fun TradingState.hasEntryTrace(): Boolean =
    hasPendingOrder() || entryStrategy != null || buyDate != null || (currentBookVolume() ?: 0.0) > VOLUME_EPSILON

internal fun TradingState.hasPendingOrder(): Boolean = hasPendingBuy() || hasPendingSell()
