package com.trading.bot.marketdata

import com.trading.bot.config.MarketDataWatchdogProperties
import com.trading.bot.stream.CandleAggregator
import com.trading.common.config.PriceEventsProperties
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Upbit ticker·체결(WS)과 분봉(REST 폴링)을 받아 MarketDataStore 에 넣는다.
 * 분봉은 CandleAggregator 로도 보내 엔진이 읽는 D1 등 상위 봉을 만든다.
 * 받는 마켓은 돌고 있는 봇들의 활성 종목([MarketSubscription])이다 — 바뀌면 모아서 새 목록으로 다시 연결한다.
 */
@Component
class MarketDataIngestionService(
    private val upbitMarketFeed: UpbitMarketFeed,
    private val marketDataStore: MarketDataStore,
    private val candleAggregator: CandleAggregator,
    private val watchdogProperties: MarketDataWatchdogProperties,
    private val priceEventsProperties: PriceEventsProperties,
    private val clock: Clock = Clock.systemUTC(),
    // 기동 때 여러 봇이 잇달아 복원되는 변경을 한 번의 재연결로 모은다.
    private val subscriptionDebounceMs: Long = 2_000,
    // 누구의 시작·정지든 모든 사용자의 피드를 다시 연결하므로 재연결 사이를 띄운다(업비트 연결 한도 초당 5).
    private val resubscribeMinIntervalMs: Long = 15_000,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> log.error("Market data ingestion coroutine failed: {}", e.message, e) },
    )

    // 지금 연결이 구독하는 마켓. 바꾸는 쪽은 restartMutex 안에서만 쓴다.
    @Volatile
    private var markets: List<String> = emptyList()

    // 구독이 바뀌면 분봉 수집이 기다리지 않고 새 마켓을 시드하게 깨운다.
    private val marketsChanged = Channel<Unit>(Channel.CONFLATED)
    private var lastResubscribeAt = 0L

    // 워치독이 무수신을 판정하는 기준(시세·UP 응답). 시작·재시작 때 now 로 맞춰 연속 재시작을 막는다.
    @Volatile
    private var lastTickerAt = 0L

    // 재시작이 되돌리지 않는 실제 수신 시각. 장애 길이를 재고 알림을 장애 한 번에 한 번만 낸다.
    @Volatile
    private var lastDataAt = 0L
    private val outageAlerted = AtomicBoolean(false)

    @Volatile
    private var tickerJob: Job? = null
    private val shuttingDown = AtomicBoolean(false)

    // 연속 tick 이 동시에 재시작해 WS 연결이 여러 개 생기지 않게 한다.
    private val restartMutex = Mutex()

    // seed 가 실패한 마켓과 다음 시도 시각·간격. 수집 코루틴만 읽고 쓴다.
    private data class SeedRetry(val nextAt: Instant, val backoff: Duration)

    private val seedRetries = mutableMapOf<String, SeedRetry>()

    // 마켓별 전일 일봉 대조 상태. [pending] 은 store 와 달랐던 업비트 값으로, 다음 읽기가 같아야 바꾼다.
    // 기동 seed 는 대조로 치지 않는다 — 경계 직후 seed 는 미확정 봉일 수 있다. 수집 코루틴만 읽고 쓴다.
    private data class Reconcile(val day: Instant, val since: Instant, val done: Boolean = false, val pending: NormalizedCandle? = null)

    private val reconciles = mutableMapOf<String, Reconcile>()
    private var overdueAlertedFor: Instant? = null

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

        // 업비트가 전일 봉을 언제 확정하는지 문서에 없어 경계 직후는 건너뛰고, 다를 때는 한 번 더 읽어 확인한다.
        private val RECONCILE_AFTER: Duration = Duration.ofMinutes(2)

        // 처음 시도하고 이만큼 지나도 못 끝낸 마켓이 있으면 그날 대조가 막힌 것으로 보고 한 번 알린다(기동이 늦어도 같은 기준).
        private val RECONCILE_OVERDUE: Duration = Duration.ofMinutes(30)

        // 가격은 체결가라 정확히 같다. 거래량은 Double 누적이라 상대 오차를 둔다.
        private const val VOLUME_TOLERANCE = 1e-6
        private const val RECONCILE_FETCH_COUNT = 2

        private val SEED_RETRY_FIRST: Duration = Duration.ofMinutes(1)
        private val SEED_RETRY_MAX: Duration = Duration.ofMinutes(30)

        // 이 시간 넘게 시세를 못 받으면 ERROR(Discord) 한 번. 재시작으로 금방 풀리는 끊김은 알리지 않는다.
        internal const val FEED_OUTAGE_ALERT_MS = 300_000L

        // 운영에서 체결이 들어오는지 로그로 확인하는 주기.
        private const val TRADE_INTAKE_LOG_INTERVAL_MS = 300_000L
    }

    // 봇 복원은 이보다 늦다(ApplicationReadyEvent). 그때까지 구독할 마켓이 없어 연결하지 않는다.
    @PostConstruct
    fun start() {
        log.info("Starting in-process market data ingestion; markets follow running bots (trades={})", priceEventsProperties.enabled)
        scope.launch { marketDataStore.subscription.markets.debounce(subscriptionDebounceMs.milliseconds).collect { applySubscription() } }
        scope.launch { collectCandlesPeriodically() }
        if (priceEventsProperties.enabled) scope.launch { logTradeIntakePeriodically() }
    }

    /**
     * 구독 집합이 지금 연결과 다르면 새 목록으로 다시 연결한다. 워치독 재시작과 같은 잠금 안에서 그 순간의 집합을 읽어,
     * 둘이 겹쳐도 마지막은 새 집합이고 연결은 하나다. 같은 연결에 새 구독을 보내지 않는다 — 지금 피드는 연결 뒤 보내는 길이 없다.
     */
    internal suspend fun applySubscription() = restartMutex.withLock {
        if (shuttingDown.get() || marketDataStore.subscription.markets.value.toList() == markets) return@withLock
        // 같은 집합이면 기다리지 않는다 — 기다리는 동안 잠금을 쥐어 워치독 재시작이 밀린다.
        val wait = lastResubscribeAt + resubscribeMinIntervalMs - System.currentTimeMillis()
        if (markets.isNotEmpty() && wait > 0) delay(wait.milliseconds)
        val wanted = marketDataStore.subscription.markets.value.toList()
        if (wanted == markets || shuttingDown.get()) return@withLock
        tickerJob?.cancelAndJoin()
        tickerJob = null
        val wasIdle = markets.isEmpty()
        markets = wanted
        val now = System.currentTimeMillis()
        lastResubscribeAt = now
        marketsChanged.trySend(Unit)
        if (wanted.isEmpty()) {
            log.info("No running bot markets; market data ingestion idle")
            return@withLock
        }
        lastTickerAt = now
        // 쉬는 동안 멈춘 시각으로 곧바로 [FEED DOWN] 을 내거나, 앞선 장애의 알림 상태 때문에 새 장애를 못 알리지 않게 한다.
        if (wasIdle) {
            lastDataAt = now
            outageAlerted.set(false)
        }
        log.info("Subscribing market data for {} markets (trades={}): {}", wanted.size, priceEventsProperties.enabled, wanted)
        tickerJob = scope.launch { runTickerCollection(wanted) }
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
                upbitMarketFeed.marketFlow(markets, priceEventsProperties.enabled, ::ingestTicker, ::markAlive).collect { trade -> ingestTrade(trade) }
                log.warn("Ticker flow completed unexpectedly; restarting in {}ms", watchdogProperties.restartBackoffMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(
                    "Ticker collection error; restarting in {}ms: {}",
                    watchdogProperties.restartBackoffMs, e.message, e,
                )
            }
            delay(watchdogProperties.restartBackoffMs.milliseconds)
        }
    }

    // 시드·지우기·재시도·대조 상태는 이 코루틴만 쓴다. 구독이 바뀌면 주기를 기다리지 않고 깨어 새 마켓부터 시드한다.
    private suspend fun collectCandlesPeriodically() {
        var collected = emptyList<String>()
        while (true) {
            collected = syncCandleMarkets(collected, markets)
            if (collected.isNotEmpty()) {
                collectCandlesRound(collected)
                // 뒤 단계가 실패해도 분봉 수집 루프는 이어 간다.
                guarded("D1 seed retry") { retryFailedSeeds() }
                guarded("D1 reconcile") { reconcilePreviousDay(collected) }
            }
            withTimeoutOrNull(CANDLE_COLLECT_INTERVAL_MS.milliseconds) { marketsChanged.receive() }
        }
    }

    /**
     * 빠진 마켓은 저장소·집계기·재시도·대조 상태에서 지우고 새로 들어온 마켓(빠졌다 돌아온 마켓 포함)은 시드한다. 지우지 않으면
     * 돌아온 마켓의 멈춘 오늘 일봉을 엔진이 '오늘' 로 받아 매수 판단에 쓴다.
     */
    internal suspend fun syncCandleMarkets(previous: List<String>, current: List<String>): List<String> {
        (previous - current.toSet()).forEach { market ->
            seedRetries.remove(market)
            reconciles.remove(market)
            marketDataStore.forget(Exchange.UPBIT, market)
            candleAggregator.forget(Exchange.UPBIT, market)
        }
        seedDailyCandles(current.filter { it !in previous })
        return current
    }

    private suspend fun guarded(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 계속 실패하면 대조가 꺼진 채로 지나가므로 Discord 로 간다. 같은 오류 반복은 알림 한도가 묶는다.
            log.error("{} failed: {}", step, e.message, e)
        }
    }

    internal suspend fun collectCandlesRound(markets: List<String>) {
        for ((index, market) in markets.withIndex()) {
            if (index > 0) delay(CANDLE_REQUEST_SPACING_MS.milliseconds)
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
        ingest(withRateLimitRetry(market) { upbitMarketFeed.getCandles(market, CandleInterval.M1, M1_FETCH_COUNT) })
    }

    // 연결 리셋·파싱 오류는 바로 다시 해도 결과가 같아 429 만 한 번 재시도한다.
    private suspend fun <T> withRateLimitRetry(market: String, fetch: suspend () -> T): T = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isRateLimited(e)) throw e
        log.debug("Rate limited on {}; retrying in {}ms", market, RATE_LIMIT_RETRY_DELAY_MS)
        delay(RATE_LIMIT_RETRY_DELAY_MS.milliseconds)
        fetch()
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
    fun checkTickerHealth() = checkTickerHealth(System.currentTimeMillis())

    // 워치독을 꺼도 재시작만 멈추고 장애 알림은 낸다.
    internal fun checkTickerHealth(nowMs: Long) {
        if (shuttingDown.get()) return
        val m = markets
        if (m.isEmpty()) return
        if (watchdogProperties.enabled && watchdogProperties.isStale(nowMs, lastTickerAt)) {
            log.warn("No ticker or keepalive for >{}ms; restarting collection", watchdogProperties.staleMs)
            restartTickerCollection()
        }
        val since = lastDataAt
        if (since > 0 && nowMs - since > FEED_OUTAGE_ALERT_MS && outageAlerted.compareAndSet(false, true)) {
            // 읽은 뒤 시세가 들어왔으면 그 수신은 회복으로 처리되지 않았으므로 알림도 내지 않는다.
            if (lastDataAt != since) {
                outageAlerted.set(false)
                return
            }
            log.error(
                "[FEED DOWN] No Upbit ticker or keepalive for {}s across {} markets — 엔진은 낡은 시세를 REST 로 대체하고, 수집은 계속 다시 띄운다.",
                (nowMs - since) / 1000, m.size,
            )
        }
    }

    // 같은 마켓으로 새 연결을 연다. 목록은 잠금 안에서 읽는다 — 집합 변경 재시작이 먼저 끝났으면 그 목록이다.
    private fun restartTickerCollection() {
        scope.launch {
            restartMutex.withLock {
                if (shuttingDown.get()) return@withLock
                // mutex 를 기다리는 사이 tick 이 들어왔으면 재시작하지 않는다.
                if (!watchdogProperties.isStale(System.currentTimeMillis(), lastTickerAt)) return@withLock
                val current = markets
                if (current.isEmpty()) return@withLock
                // 이전 연결이 완전히 닫힌 뒤 새로 연다.
                tickerJob?.cancelAndJoin()
                if (shuttingDown.get()) return@withLock
                lastTickerAt = System.currentTimeMillis()
                tickerJob = scope.launch { runTickerCollection(current) }
            }
        }
    }

    internal fun ingestTicker(ticker: NormalizedTicker) {
        markAlive()
        try {
            marketDataStore.updateTicker(ticker)
        } catch (e: Exception) {
            log.warn("store.updateTicker failed for {}: {}", ticker.market, e.message)
        }
    }

    /** 시세나 PING 에 대한 UP 응답을 받았다. UP 은 거래가 없는 마켓만 구독해도 오므로 연결이 살아 있다는 뜻이다. */
    internal fun markAlive() {
        val now = System.currentTimeMillis()
        lastTickerAt = now
        lastDataAt = now
        if (outageAlerted.compareAndSet(true, false)) log.info("[FEED RECOVERED] Upbit ticker feed is receiving again")
    }

    // 워치독은 시세와 UP 응답으로 판단한다 — 체결은 거래가 적은 마켓에서 오래 없을 수 있다.
    private fun ingestTrade(trade: NormalizedTrade) {
        try {
            marketDataStore.publishTrade(trade)
        } catch (e: Exception) {
            log.warn("store.publishTrade failed for {}: {}", trade.market, e.message)
        }
    }

    private suspend fun logTradeIntakePeriodically() {
        while (true) {
            delay(TRADE_INTAKE_LOG_INTERVAL_MS.milliseconds)
            markets.takeIf { it.isNotEmpty() }?.let { logTradeIntake(it) }
        }
    }

    internal fun logTradeIntake(markets: List<String>) {
        val intake = marketDataStore.drainTradeIntake()
        val dropped = upbitMarketFeed.drainDropped()
        val parseFailed = upbitMarketFeed.drainParseFailures()
        val perMarket = markets.joinToString(", ") { market ->
            val counts = intake[market]
            "$market received=${counts?.received ?: 0} duplicates=${counts?.duplicates ?: 0} " +
                "feedDropped=${dropped[market] ?: 0} parseFailed=${parseFailed[market] ?: 0}"
        }
        log.info("[trade intake] last {}s: {}", TRADE_INTAKE_LOG_INTERVAL_MS / 1000, perMarket)
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

    // 구독에 들어온 마켓의 과거 일봉을 store 에 채운다. D1 은 분봉 집계라 하루에 하나씩만 쌓이기 때문이다.
    // 분봉 수집과 같은 코루틴에서 돌아 store 에 쓰는 쪽이 하나로 유지된다.
    // 집계기는 분봉 전용이라 D1 은 store 에 바로 넣는다.
    internal suspend fun seedDailyCandles(markets: List<String>) {
        for ((index, market) in markets.withIndex()) {
            if (index > 0) delay(CANDLE_REQUEST_SPACING_MS.milliseconds)
            if (!seedMarket(market)) {
                seedRetries[market] = SeedRetry(clock.instant().plus(SEED_RETRY_FIRST), SEED_RETRY_FIRST)
            }
        }
    }

    /** 기동 seed 가 실패한 마켓을 간격을 두 배씩(최대 30분) 늘려 다시 seed 한다. 실패 WARN 은 처음 한 번만 남는다. */
    internal suspend fun retryFailedSeeds() {
        val due = seedRetries.filterValues { !clock.instant().isBefore(it.nextAt) }
        for ((index, entry) in due.entries.withIndex()) {
            if (index > 0) delay(CANDLE_REQUEST_SPACING_MS.milliseconds)
            val (market, retry) = entry
            if (seedMarket(market, quiet = true)) {
                seedRetries.remove(market)
            } else {
                val backoff = minOf(retry.backoff.multipliedBy(2), SEED_RETRY_MAX)
                seedRetries[market] = SeedRetry(clock.instant().plus(backoff), backoff)
            }
        }
    }

    /**
     * 분봉으로 만든 전일 D1 을 업비트 일봉과 날마다 한 번 대조한다. 같으면 끝, 다르면 다음 라운드에 다시 받아 같은 값일 때만
     * store 와 집계기를 업비트 값으로 바꾼다(가격이 달랐으면 ERROR). store 에 전일 봉이 없을 때도 같은 값을 두 번 읽은 뒤 채운다.
     * 집계기를 prime 하지 않으면 늦게 온 전일 분봉이 집계값으로 store 를 되덮는다.
     */
    internal suspend fun reconcilePreviousDay(markets: List<String>) {
        val now = clock.instant()
        val today = now.truncatedTo(ChronoUnit.DAYS)
        if (now.isBefore(today.plus(RECONCILE_AFTER))) return
        val target = today.minus(1, ChronoUnit.DAYS)
        val replaced = mutableListOf<String>()
        var requested = false
        for (market in markets) {
            val state = reconciles[market]?.takeIf { it.day == target } ?: run {
                reconciles[market]?.takeIf { !it.done }?.let { log.warn("{} 전일 일봉({}) 대조를 끝내지 못한 채 날짜가 바뀌었다", market, it.day.atZone(ZoneOffset.UTC).toLocalDate()) }
                Reconcile(target, since = now).also { reconciles[market] = it }
            }
            if (state.done) continue
            if (requested) delay(CANDLE_REQUEST_SPACING_MS.milliseconds)
            requested = true
            val upbit = try {
                upbitMarketFeed.getCandles(market, CandleInterval.D1, RECONCILE_FETCH_COUNT).firstOrNull { it.openTime == target }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("D1 reconcile fetch failed for {}: {}", market, e.message)
                continue
            }
            reconciles[market] = reconcileOne(market, state, upbit, now, replaced)
        }
        if (replaced.isNotEmpty()) {
            // 원인은 집계 결함·수집 공백·경계 직후 seed 중 무엇인지 여기서 알 수 없다.
            log.error("[DAILY CANDLE] store 의 전일 일봉이 업비트와 달라 업비트 값으로 바꿨다: {} — 마켓별 값은 WARN", replaced)
        }
        val unfinished = markets.filter {
            reconciles[it]?.let { s -> s.day == target && !s.done && !now.isBefore(s.since.plus(RECONCILE_OVERDUE)) } == true
        }
        if (unfinished.isNotEmpty() && overdueAlertedFor != target) {
            overdueAlertedFor = target
            log.error("[DAILY CANDLE] {}분 넘게 전일 일봉 대조를 끝내지 못했다: {} — 업비트 일봉 조회를 확인하라", RECONCILE_OVERDUE.toMinutes(), unfinished)
        }
    }

    private fun reconcileOne(market: String, state: Reconcile, upbit: NormalizedCandle?, now: Instant, replaced: MutableList<String>): Reconcile {
        // 그날 거래가 없으면 업비트에도 봉이 없다.
        upbit ?: return state.copy(done = true)
        val stored = marketDataStore.getCandles(Exchange.UPBIT, market, CandleInterval.D1, RECONCILE_FETCH_COUNT + 1)
            .firstOrNull { it.openTime == state.day }
        if (stored != null && samePrices(stored, upbit) && sameVolume(stored, upbit)) return state.copy(done = true)
        // 경계 직후 업비트 봉이 미확정일 수 있어 비었을 때도 같은 값을 두 번 읽은 뒤에만 쓴다.
        if (state.pending == null || !samePrices(state.pending, upbit) || !sameVolume(state.pending, upbit)) {
            return state.copy(pending = upbit)
        }
        replaceDaily(market, upbit, now)
        if (stored == null) {
            log.warn("{} 전일 일봉이 store 에 없어 업비트 값으로 채웠다", market)
        } else if (!samePrices(stored, upbit)) {
            replaced += market
            log.warn(
                "{} 전일 일봉 store(O {} H {} L {} C {}) ≠ 업비트(O {} H {} L {} C {})",
                market, stored.openPrice, stored.highPrice, stored.lowPrice, stored.closePrice,
                upbit.openPrice, upbit.highPrice, upbit.lowPrice, upbit.closePrice,
            )
        } else {
            log.info("{} 전일 일봉 거래량만 달라 업비트 값으로 바꿨다: {} → {}", market, stored.volume, upbit.volume)
        }
        return state.copy(done = true, pending = null)
    }

    private fun replaceDaily(market: String, upbit: NormalizedCandle, now: Instant) {
        marketDataStore.addCandle(upbit)
        try {
            candleAggregator.prime(upbit, now)
        } catch (e: IllegalArgumentException) {
            log.error("Failed to prime the reconciled D1 for {}: {}", market, e.message)
        }
    }

    private fun samePrices(a: NormalizedCandle, b: NormalizedCandle) =
        a.openPrice == b.openPrice && a.highPrice == b.highPrice && a.lowPrice == b.lowPrice && a.closePrice == b.closePrice

    private fun sameVolume(a: NormalizedCandle, b: NormalizedCandle) =
        abs(a.volume - b.volume) <= VOLUME_TOLERANCE * maxOf(abs(a.volume), abs(b.volume))

    private suspend fun seedMarket(market: String, quiet: Boolean = false): Boolean {
        // 첫 분봉 라운드가 어제 분봉을 실어 오면 어제 일봉이 부분봉으로 덮이므로 지금 이전 period 는 집계하지 않는다.
        candleAggregator.startFrom(Exchange.UPBIT, market, clock.instant())
        return try {
            // 봇이 뜰 때 엔진들의 REST 일봉 조회와 IP 한도를 나눠 429 가 난다. 1분 재시도까지 기다리지 않고 한 번 더 받는다.
            val candles = withRateLimitRetry(market) { upbitMarketFeed.getCandles(market, CandleInterval.D1, SEED_DAILY_CANDLE_COUNT) }
            val fetchedAt = clock.instant()
            candles.forEach { marketDataStore.addCandle(it) }
            // 오늘(UTC) 봉은 이후 분봉이 이어서 집계하므로 집계기에 등록해야 첫 분봉이 seed 를 덮지 않는다.
            val today = fetchedAt.truncatedTo(ChronoUnit.DAYS)
            val primed = candles.firstOrNull { it.openTime == today }?.let { primeTodayAggregate(market, it, fetchedAt) } ?: false
            log.info("Seeded {} D1 candles into store for {} (primed today={})", candles.size, market, primed)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (quiet) log.debug("D1 seed retry failed for {}: {}", market, e.message) else log.warn("D1 seed failed for {}: {} — retrying with backoff", market, e.message)
            false
        }
    }
}
