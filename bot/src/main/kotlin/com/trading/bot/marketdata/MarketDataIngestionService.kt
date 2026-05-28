package com.trading.bot.marketdata

import com.trading.bot.config.WatchlistProperties
import com.trading.bot.stream.MarketDataPersistenceService
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> log.error("Market data ingestion coroutine failed: {}", e.message, e) },
    )

    companion object {
        private const val CANDLE_COLLECT_INTERVAL_MS = 60_000L
    }

    @PostConstruct
    fun start() {
        val markets = watchlistProperties.tickerList().map { MarketPair.normalize(Exchange.UPBIT, it) }
        if (markets.isEmpty()) {
            log.warn("No watchlist markets configured; market data ingestion disabled")
            return
        }
        log.info("Starting in-process market data ingestion for {} markets: {}", markets.size, markets)
        scope.launch { collectTickers(markets) }
        scope.launch { collectCandlesPeriodically(markets) }
    }

    @PreDestroy
    fun stop() {
        log.info("Stopping market data ingestion")
        scope.cancel()
    }

    private suspend fun collectTickers(markets: List<String>) {
        try {
            upbitMarketFeed.tickerFlow(markets).collect { ticker -> ingestTicker(ticker) }
        } catch (e: Exception) {
            log.error("Ticker collection stopped: {}", e.message, e)
        }
    }

    private suspend fun collectCandlesPeriodically(markets: List<String>) {
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

    // fan-out 격리: store / persistence 를 각각 독립 try/catch 로 감싼다.
    // 한 sink 의 실패가 다른 sink 나 수집 코루틴(Flow collect)을 죽이지 않게 — 구 Kafka 2-consumer-group 격리와 등가.
    internal fun ingestTicker(ticker: NormalizedTicker) {
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
}
