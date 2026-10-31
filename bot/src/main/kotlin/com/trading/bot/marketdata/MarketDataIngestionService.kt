package com.trading.bot.marketdata

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.config.reportableEntries
import com.trading.bot.stream.CandleAggregator
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Upbit ticker(WS)와 분봉(REST 폴링)을 받아 MarketDataStore 에 넣는다.
 * 분봉은 CandleAggregator 로도 보내 엔진이 읽는 D1 등 상위 봉을 만든다.
 */
@Component
class MarketDataIngestionService(
    private val upbitMarketFeed: UpbitMarketFeed,
    private val marketDataStore: MarketDataStore,
    private val candleAggregator: CandleAggregator,
    private val watchlistProperties: WatchlistProperties,
    private val watchdogProperties: MarketDataWatchdogProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> log.error("Market data ingestion coroutine failed: {}", e.message, e) },
    )

    @Volatile
    private var markets: List<String> = emptyList()

    // 워치독이 무수신을 판정하는 기준. 시작·재시작 때 now 로 맞춰 연속 재시작을 막는다.
    @Volatile
    private var lastTickerAt = 0L

    @Volatile
    private var tickerJob: Job? = null
    private val shuttingDown = AtomicBoolean(false)

    // 연속 tick 이 동시에 재시작해 WS 연결이 여러 개 생기지 않게 한다.
    private val restartMutex = Mutex()

    companion object {
        private const val CANDLE_COLLECT_INTERVAL_MS = 60_000L
        // Upbit /v1/candles/days 의 최대 개수.
        private const val SEED_DAILY_CANDLE_COUNT = 200

        // Upbit candles 그룹 제한은 초당 10회다. 마켓별 요청을 몰아 보내면 429 가 나므로 간격을 둔다.
        internal const val CANDLE_REQUEST_SPACING_MS = 150L

        // 1 이면 진행 중인 분봉만 받아 완결본을 놓친다. 5 면 몇 라운드가 실패해도 그 사이 완결본이 들어온다.
        // Upbit 은 무거래 분을 생략해 꼬리에 오래된 분봉이 섞일 수 있는데, 그 재유입은 CandleAggregator 가 막는다.
        internal const val M1_FETCH_COUNT = 5

        private const val RATE_LIMIT_RETRY_DELAY_MS = 1_000L
    }

    @PostConstruct
    fun start() {
        val m = watchlistProperties.validTickers().map { MarketPair.normalize(Exchange.UPBIT, it) }
        if (m.isEmpty()) {
            if (watchlistProperties.tickerList().isEmpty()) {
                log.warn("No watchlist markets configured; market data ingestion disabled")
            } else {
                log.warn("Every watchlist market is malformed; market data ingestion disabled")
            }
            return
        }
        markets = m
        lastTickerAt = System.currentTimeMillis()
        log.info("Starting in-process market data ingestion for {} markets: {}", m.size, m)
        tickerJob = scope.launch { runTickerCollection(m) }
        scope.launch { collectCandlesPeriodically(m) }
    }

    // @PostConstruct 는 Discord appender 가 붙기 전이라 그 ERROR 는 Discord 에 가지 않는다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun reportInvalidWatchlist() {
        val invalid = watchlistProperties.invalidTickers()
        if (invalid.isEmpty()) return
        val outcome = if (watchlistProperties.validTickers().isEmpty()) "를 빼면 남는 마켓이 없어 시세 수집을 끕니다" else "는 빼고 시세를 수집합니다"
        log.error(
            "WATCHLIST_TICKERS 의 형식이 틀린 항목 {} {} — 배포 환경(.env·VULTR_DEPLOY_ENV 시크릿)을 고치세요",
            reportableEntries(invalid), outcome,
        )
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping market data ingestion")
        shuttingDown.set(true)
        scope.cancel()
    }

    // WS flow 가 에러로 끝나든 정상 종료하든 backoff 후 다시 구독한다.
    private suspend fun runTickerCollection(markets: List<String>) {
        while (currentCoroutineContext().isActive) {
            try {
                upbitMarketFeed.tickerFlow(markets).collect { ticker -> ingestTicker(ticker) }
                log.warn("Ticker flow completed unexpectedly; restarting in {}ms", watchdogProperties.restartBackoffMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(
                    "Ticker collection error; restarting in {}ms: {}",
                    watchdogProperties.restartBackoffMs, e.message, e,
                )
            }
            delay(watchdogProperties.restartBackoffMs)
        }
    }

    private suspend fun collectCandlesPeriodically(markets: List<String>) {
        seedDailyCandles(markets)
        while (true) {
            collectCandlesRound(markets)
            delay(CANDLE_COLLECT_INTERVAL_MS)
        }
    }

    internal suspend fun collectCandlesRound(markets: List<String>) {
        for ((index, market) in markets.withIndex()) {
            if (index > 0) delay(CANDLE_REQUEST_SPACING_MS)
            try {
                fetchM1WithRateLimitRetry(market)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Candle collection failed for {}: {}", market, e.message)
            }
        }
    }

    private suspend fun fetchM1WithRateLimitRetry(market: String) {
        try {
            ingest(upbitMarketFeed.getCandles(market, CandleInterval.M1, M1_FETCH_COUNT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 연결 리셋·파싱 오류는 바로 다시 해도 결과가 같아 429 만 재시도한다.
            if (!isRateLimited(e)) throw e
            log.debug("Rate limited on {}; retrying in {}ms", market, RATE_LIMIT_RETRY_DELAY_MS)
            delay(RATE_LIMIT_RETRY_DELAY_MS)
            ingest(upbitMarketFeed.getCandles(market, CandleInterval.M1, M1_FETCH_COUNT))
        }
    }

    // Upbit 은 최신순으로 준다. 집계기는 더 새 분봉이 와야 이전 분봉을 확정하므로 오래된 것부터 넣는다.
    private fun ingest(candles: List<NormalizedCandle>) {
        for (candle in candles.sortedBy { it.openTime }) ingestCandle(candle)
    }

    // 429 가 다른 예외에 감싸여 올 수 있어 메시지도 본다.
    private fun isRateLimited(e: Throwable): Boolean =
        (e as? WebClientResponseException)?.statusCode?.value() == 429 ||
            e.message?.contains("429") == true

    // TCP 는 살아 있는데 수신이 끊긴 half-open 상태는 재구독이 아니라 새 연결로만 풀려서 job 을 새로 띄운다.
    fun checkTickerHealth() {
        if (!watchdogProperties.enabled || shuttingDown.get()) return
        val m = markets
        if (m.isEmpty()) return
        if (watchdogProperties.isStale(System.currentTimeMillis(), lastTickerAt)) {
            log.warn("No ticker for >{}ms; restarting collection", watchdogProperties.staleMs)
            restartTickerCollection(m)
        }
    }

    private fun restartTickerCollection(markets: List<String>) {
        scope.launch {
            restartMutex.withLock {
                if (shuttingDown.get()) return@withLock
                // mutex 를 기다리는 사이 tick 이 들어왔으면 재시작하지 않는다.
                if (!watchdogProperties.isStale(System.currentTimeMillis(), lastTickerAt)) return@withLock
                // 이전 연결이 완전히 닫힌 뒤 새로 연다.
                tickerJob?.cancelAndJoin()
                if (shuttingDown.get()) return@withLock
                lastTickerAt = System.currentTimeMillis()
                tickerJob = scope.launch { runTickerCollection(markets) }
            }
        }
    }

    internal fun ingestTicker(ticker: NormalizedTicker) {
        lastTickerAt = System.currentTimeMillis()
        try {
            marketDataStore.updateTicker(ticker)
        } catch (e: Exception) {
            log.warn("store.updateTicker failed for {}: {}", ticker.market, e.message)
        }
    }

    // 한쪽 실패가 다른 쪽이나 수집 코루틴을 멈추지 않게 따로 감싼다.
    internal fun ingestCandle(candle: NormalizedCandle) {
        try {
            marketDataStore.addCandle(candle)
        } catch (e: Exception) {
            log.warn("store.addCandle failed for {}: {}", candle.market, e.message)
        }
        try {
            candleAggregator.onMinuteCandle(candle)
        } catch (e: Exception) {
            log.warn("candle aggregation failed for {}: {}", candle.market, e.message)
        }
    }

    private fun primeTodayAggregate(market: String, today: NormalizedCandle, fetchedAt: Instant): Boolean = try {
        candleAggregator.prime(today, fetchedAt)
        true
    } catch (e: IllegalArgumentException) {
        // seed 자체는 store 에 들어갔으므로 seed 실패와 구분해 남긴다.
        log.error("Failed to prime D1 aggregate for {}: {}", market, e.message)
        false
    }

    // 부팅 직후 과거 일봉을 store 에 채운다. D1 은 분봉 집계라 하루에 하나씩만 쌓이기 때문이다.
    // 분봉 수집과 같은 코루틴에서 돌아 store 에 쓰는 쪽이 하나로 유지된다.
    // 집계기는 분봉 전용이라 D1 은 store 에 바로 넣는다.
    internal suspend fun seedDailyCandles(markets: List<String>) {
        for ((index, market) in markets.withIndex()) {
            if (index > 0) delay(CANDLE_REQUEST_SPACING_MS)
            // 첫 분봉 라운드가 어제 분봉을 실어 오면 어제 일봉이 부분봉으로 덮이므로 지금 이전 period 는 집계하지 않는다.
            candleAggregator.startFrom(Exchange.UPBIT, market, Instant.now())
            try {
                val candles = upbitMarketFeed.getCandles(market, CandleInterval.D1, SEED_DAILY_CANDLE_COUNT)
                val fetchedAt = Instant.now()
                candles.forEach { marketDataStore.addCandle(it) }
                // 오늘(UTC) 봉은 이후 분봉이 이어서 집계하므로 집계기에 등록해야 첫 분봉이 seed 를 덮지 않는다.
                val today = fetchedAt.truncatedTo(ChronoUnit.DAYS)
                val primed = candles.firstOrNull { it.openTime == today }?.let { primeTodayAggregate(market, it, fetchedAt) } ?: false
                log.info("Seeded {} D1 candles into store for {} (primed today={})", candles.size, market, primed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("D1 seed failed for {}: {}", market, e.message)
            }
        }
    }
}
