package com.trading.bot.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.MarketBoardProperties
import com.trading.common.domain.NormalizedTicker
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.toKotlinDuration
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * 시세판 전용 업비트 WS 연결. 업비트 KRW 마켓 전체의 ticker 만 받아 [MarketBoardStore] 에 둔다. 봇 연결([MarketDataIngestionService])과
 * 따로라 봇 시작·정지가 시세판을 끊지 않고, 목록 갱신이 봇 체결을 끊지 않는다. 목록은 하루 한 번 KST 04:00 에 다시 받는다.
 */
@Component
class MarketBoard internal constructor(
    private val feed: UpbitMarketFeed,
    private val store: MarketBoardStore,
    private val properties: MarketBoardProperties,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    // 봇 연결의 피드 빈을 같이 쓰지 않는다 — 진단 수와 로그가 섞인다.
    @Autowired
    constructor(upbitWebClient: WebClient, objectMapper: ObjectMapper, store: MarketBoardStore, properties: MarketBoardProperties) :
        this(UpbitMarketFeed.labeled(upbitWebClient, objectMapper, "board"), store, properties, Clock.systemUTC(), defaultScope())

    private val log = LoggerFactory.getLogger(javaClass)

    // 바꾸는 쪽은 lock 안에서만 쓴다.
    @Volatile
    private var markets: List<String> = emptyList()
    private var connection: Job? = null
    private val lock = Mutex()

    @Volatile
    private var lastDataAt: Instant = Instant.EPOCH

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val REFRESH_AT: LocalTime = LocalTime.of(4, 0)
        private val RETRY_FIRST: Duration = Duration.ofMinutes(1)
        private val RETRY_MAX: Duration = Duration.ofMinutes(30)

        // 업비트는 120초 무송수신이면 닫는다. 연결은 PING 으로 살아 있는데 시세도 UP 도 없으면 멈춘 것으로 보고 다시 연다.
        private val STALE_AFTER: Duration = Duration.ofSeconds(120)
        internal val STALE_CHECK_INTERVAL: Duration = Duration.ofSeconds(30)
        private val RESTART_BACKOFF: Duration = Duration.ofSeconds(1)

        private fun defaultScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> LoggerFactory.getLogger(MarketBoard::class.java).error("Market board coroutine failed: {}", e.message, e) },
        )
    }

    @PostConstruct
    fun start() {
        if (!properties.enabled) {
            log.info("Market board off (MARKETDATA_BOARD_ENABLED=false)")
            return
        }
        scope.launch {
            connect(loadCatalog())
            launch { watchStale() }
            while (isActive) {
                delay(untilNextRefresh(clock.instant()).toKotlinDuration())
                refresh()
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    /** 목록을 한 번 다시 받아 바뀌었으면 다시 연결한다. 실패나 빈 목록은 지금 목록을 그대로 둔다. */
    internal suspend fun refresh() {
        val catalog = fetchCatalog() ?: return
        if (catalog != markets) connect(catalog)
    }

    internal fun untilNextRefresh(now: Instant): Duration {
        val local = now.atZone(KST)
        val today = local.toLocalDate().atTime(REFRESH_AT).atZone(KST)
        val next = if (today.isAfter(local)) today else today.plusDays(1)
        return Duration.between(now, next.toInstant())
    }

    // 첫 목록 없이는 연결할 것이 없다. 하루를 기다리지 않게 짧게 시작해 늘린다.
    private suspend fun loadCatalog(): List<String> {
        var wait = RETRY_FIRST
        while (true) {
            fetchCatalog()?.let { return it }
            log.warn("Market board catalog unavailable; retrying in {}", wait)
            delay(wait.toKotlinDuration())
            wait = minOf(wait.multipliedBy(2), RETRY_MAX)
        }
    }

    private suspend fun fetchCatalog(): List<String>? = try {
        feed.getKrwMarkets().sorted().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Market board catalog fetch failed: {}", e.message)
        null
    }

    private suspend fun connect(catalog: List<String>) = lock.withLock { connectLocked(catalog) }

    // 잠금 안에서 다시 본다 — 기다리는 사이 갱신이 새 목록으로 연결했으면 옛 목록으로 되돌리지 않는다.
    private suspend fun reconnectIfStale() = lock.withLock {
        if (Duration.between(lastDataAt, clock.instant()) <= STALE_AFTER) return@withLock
        log.warn("Market board received nothing for over {}; reconnecting", STALE_AFTER)
        connectLocked(markets)
    }

    private suspend fun connectLocked(catalog: List<String>) {
        connection?.cancelAndJoin()
        markets = catalog
        store.retain(catalog)
        lastDataAt = clock.instant()
        log.info("Market board subscribing tickers for {} KRW markets", catalog.size)
        connection = scope.launch { collect(catalog) }
    }

    // 피드는 끊기면 스스로 다시 붙는다. flow 가 끝나거나 던지면 잠깐 쉬고 다시 연다.
    private suspend fun collect(catalog: List<String>) {
        while (currentCoroutineContext().isActive) {
            try {
                feed.marketFlow(catalog, false, ::onTicker, ::markAlive).collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Market board feed failed: {}", e.message)
            }
            delay(RESTART_BACKOFF.toKotlinDuration())
        }
    }

    private fun onTicker(ticker: NormalizedTicker) {
        markAlive()
        store.update(ticker)
    }

    private fun markAlive() {
        lastDataAt = clock.instant()
    }

    private suspend fun watchStale() {
        while (true) {
            delay(STALE_CHECK_INTERVAL.toKotlinDuration())
            reconnectIfStale()
        }
    }
}
