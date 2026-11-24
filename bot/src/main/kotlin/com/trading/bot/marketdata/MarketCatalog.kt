package com.trading.bot.marketdata

import com.trading.common.domain.MarketPair
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** 업비트 KRW 마켓 하나. [market] 은 내부 표기(`BTC/KRW`)다. */
data class MarketListing(val market: String, val koreanName: String, val englishName: String)

/**
 * 감시 코인 검색·검증용 업비트 KRW 마켓 목록. 처음 필요할 때 받아 하루 동안 쓴다. 시세판([MarketBoard])과 따로 두는 것은
 * 시세판이 꺼져 있어도 검색·검증이 되고 이름이 필요해서다. 받지 못하면 받아 둔 목록을 쓰고, 1분 동안은 다시 묻지 않는다.
 */
@Component
class MarketCatalog internal constructor(
    private val feed: UpbitMarketFeed,
    private val clock: Clock,
) {
    @Autowired
    constructor(feed: UpbitMarketFeed) : this(feed, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Mutex()

    @Volatile private var cached: List<MarketListing>? = null

    @Volatile private var fetchedAt: Instant = Instant.MIN

    @Volatile private var failedAt: Instant = Instant.MIN

    /** 목록이 아직 한 번도 없으면 null. 다른 요청이 다시 받는 중이면 기다리지 않고 받아 둔 목록을 준다. */
    suspend fun listings(): List<MarketListing>? {
        if (fresh()) return cached
        val stale = cached
        if (stale != null && !lock.tryLock()) return stale
        if (stale == null) lock.lock()
        try {
            if (!fresh() && retryDue()) refresh()
        } finally {
            lock.unlock()
        }
        return cached
    }

    /**
     * 봇 감시 목록([markets], 업비트 표기 `KRW-BTC`) 중 목록에 없는 것. 목록이 없으면 null — 그때는 형식 검사만 한다.
     * 받은 지 [RECHECK_UNKNOWN] 이 지난 목록에 없는 마켓이 있으면 한 번 다시 받아 본다 — 그사이 상장된 코인을 하루 동안 거절하지 않게.
     */
    suspend fun unknown(markets: List<String>): List<String>? {
        listings() ?: return null
        if (missing(markets).isNotEmpty()) {
            lock.withLock { if (!clock.instant().isBefore(fetchedAt.plus(RECHECK_UNKNOWN)) && retryDue()) refresh() }
        }
        return missing(markets)
    }

    internal fun refreshing(): Boolean = lock.isLocked

    private fun missing(markets: List<String>): List<String> {
        val known = cached.orEmpty().mapTo(HashSet()) { MarketPair.toUpbitFormat(it.market) }
        return markets.filterNot { it in known }
    }

    private fun fresh() = cached != null && clock.instant().isBefore(fetchedAt.plus(TTL))

    private fun retryDue() = !clock.instant().isBefore(failedAt.plus(RETRY_AFTER_FAILURE))

    private suspend fun refresh() {
        try {
            val listings = feed.getKrwMarketListings()
            if (listings.isEmpty()) {
                failedAt = clock.instant()
                log.warn("Market catalog fetch returned no KRW markets")
                return
            }
            cached = listings.sortedBy { it.market }
            fetchedAt = clock.instant()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt = clock.instant()
            log.warn("Market catalog fetch failed: {}", e.message)
        }
    }

    private companion object {
        val TTL: Duration = Duration.ofDays(1)
        val RETRY_AFTER_FAILURE: Duration = Duration.ofMinutes(1)
        val RECHECK_UNKNOWN: Duration = Duration.ofMinutes(5)
    }
}
