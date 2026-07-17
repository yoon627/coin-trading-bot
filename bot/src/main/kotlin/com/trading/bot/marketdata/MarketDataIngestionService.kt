package com.trading.bot.marketdata

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.MarketDataPersistenceService
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
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 구 collector 모듈(Kafka 발행)을 흡수한 in-process 시세 수집기.
 * UpbitMarketFeed 에서 ticker(WS)/candle(REST 폴링)을 받아 MarketDataStore(메모리) 와
 * MarketDataPersistenceService(DB+집계) 로 직접 fan-out 한다. 단일 JVM 이라 메시지 버스 불필요.
 */
@Component
class MarketDataIngestionService(
    private val upbitMarketFeed: UpbitMarketFeed,
    private val marketDataStore: MarketDataStore,
    private val persistenceService: MarketDataPersistenceService,
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

    // 마지막 ticker ingest 시각 — half-open(무수신) 워치독 판정용. start/재시작 시 now 로 리셋해 폭주 방지.
    @Volatile
    private var lastTickerAt = 0L

    @Volatile
    private var tickerJob: Job? = null
    private val shuttingDown = AtomicBoolean(false)

    // 워치독 재시작 직렬화 — 연속 tick 이 동시에 재시작을 시도해 다중 job/연결을 만들지 않도록.
    private val restartMutex = Mutex()

    companion object {
        private const val CANDLE_COLLECT_INTERVAL_MS = 60_000L
        // 부팅 시 store D1 버퍼 1회 백필 개수. Upbit /v1/candles/days 최대 200.
        private const val SEED_DAILY_CANDLE_COUNT = 200
    }

    @PostConstruct
    fun start() {
        val m = watchlistProperties.tickerList().map { MarketPair.normalize(Exchange.UPBIT, it) }
        if (m.isEmpty()) {
            log.warn("No watchlist markets configured; market data ingestion disabled")
            return
        }
        markets = m
        lastTickerAt = System.currentTimeMillis() // grace: 부팅 직후 워치독 오발동 방지
        log.info("Starting in-process market data ingestion for {} markets: {}", m.size, m)
        tickerJob = scope.launch { runTickerCollection(m) }
        scope.launch { collectCandlesPeriodically(m) }
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping market data ingestion")
        shuttingDown.set(true)
        scope.cancel()
    }

    // WS flow 가 (에러/정상) 종료해도 backoff 후 재구독 — 구 구현은 catch 후 종료라 한 번 끊기면 영영 수집이 멈췄다.
    private suspend fun runTickerCollection(markets: List<String>) {
        while (currentCoroutineContext().isActive) {
            try {
                upbitMarketFeed.tickerFlow(markets).collect { ticker -> ingestTicker(ticker) }
                log.warn("Ticker flow completed unexpectedly; restarting in {}ms", watchdogProperties.restartBackoffMs)
            } catch (e: CancellationException) {
                throw e // 취소는 정상 종료 경로(재시작/shutdown) — 재구독하지 않는다.
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
            for (market in markets) {
                try {
                    val candles = upbitMarketFeed.getCandles(market, CandleInterval.M1, 1)
                    for (candle in candles) ingestCandle(candle)
                } catch (e: Exception) {
                    log.warn("Candle collection failed for {}: {}", market, e.message)
                }
            }
            delay(CANDLE_COLLECT_INTERVAL_MS)
        }
    }

    // half-open(TCP 살아있으나 무수신) 자동복구: 임계 초과 시 수집 코루틴을 재기동한다. half-open 은 flow 재구독이
    // 아니라 새 연결로만 풀리므로 job 을 취소·재생성한다. 매매 정확성은 TradingEngine staleness 게이팅이 이미 보호하므로
    // 여기선 수집 복구가 목적이고, 오판 시 enabled 로 런타임 비활성할 수 있다.
    @Scheduled(
        fixedDelayString = "\${marketdata.watchdog.interval-ms:20000}",
        initialDelayString = "\${marketdata.watchdog.initial-delay-ms:30000}",
    )
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
                // mutex 대기 중 새 tick 이 도착했으면(더 이상 stale 아님) 재시작하지 않는다 — late-tick 을
                // 불필요한 WS 재연결로 만들지 않도록 cancel 직전에 재확인(TOCTOU).
                if (!watchdogProperties.isStale(System.currentTimeMillis(), lastTickerAt)) return@withLock
                tickerJob?.cancelAndJoin() // 이전 flow 완전 종료 후 재시작 — 이중 WS 연결 창 제거
                if (shuttingDown.get()) return@withLock
                lastTickerAt = System.currentTimeMillis() // reset grace
                tickerJob = scope.launch { runTickerCollection(markets) }
            }
        }
    }

    // fan-out 격리: store / persistence 를 각각 독립 try/catch 로 감싼다.
    // 한 sink 의 실패가 다른 sink 나 수집 코루틴(Flow collect)을 죽이지 않게 — 구 Kafka 2-consumer-group 격리와 등가.
    internal fun ingestTicker(ticker: NormalizedTicker) {
        lastTickerAt = System.currentTimeMillis()
        try {
            marketDataStore.updateTicker(ticker)
        } catch (e: Exception) {
            log.warn("store.updateTicker failed for {}: {}", ticker.market, e.message)
        }
        try {
            persistenceService.persistTicker(ticker)
        } catch (e: Exception) {
            log.warn("persistTicker failed for {}: {}", ticker.market, e.message)
        }
    }

    internal fun ingestCandle(candle: NormalizedCandle) {
        try {
            marketDataStore.addCandle(candle)
        } catch (e: Exception) {
            log.warn("store.addCandle failed for {}: {}", candle.market, e.message)
        }
        try {
            persistenceService.persistCandle(candle)
        } catch (e: Exception) {
            log.warn("persistCandle failed for {}: {}", candle.market, e.message)
        }
    }

    // 부팅 직후 store D1 버퍼를 과거 일봉으로 1회 채운다. 미실행 시 매수/청산(TradingEngine.loadStoreDailyCandles)이
    // store 부족으로 warm-up(D1 은 분봉 집계라 하루 1개씩만 누적 → 최대 ~21일) 동안 매 tick REST 폴백을 탄다.
    // collectCandlesPeriodically 와 같은 코루틴에서 호출되므로 candle writer 단일성 유지(MarketDataStore trim race 방지).
    // store.addCandle 직접 — ingestCandle 의 persistCandle→aggregator.onMinuteCandle 은 분봉 전용이라 D1 을 오집계함.
    internal suspend fun seedDailyCandles(markets: List<String>) {
        for (market in markets) {
            try {
                val candles = upbitMarketFeed.getCandles(market, CandleInterval.D1, SEED_DAILY_CANDLE_COUNT)
                candles.forEach { marketDataStore.addCandle(it) }
                log.info("Seeded {} D1 candles into store for {}", candles.size, market)
            } catch (e: Exception) {
                log.warn("D1 seed failed for {}: {}", market, e.message)
            }
        }
    }
}
