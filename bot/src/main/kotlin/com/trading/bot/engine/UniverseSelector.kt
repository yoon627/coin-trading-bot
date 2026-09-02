package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.MarketInfo
import com.trading.bot.domain.Ticker
import com.trading.common.config.UniverseProperties
import com.trading.common.domain.PeggedAssets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/** 엔진이 보는 유니버스 공급자. 자동 선정이 꺼진 엔진은 [NONE] 을 받는다(null 분기 대신 no-op). */
fun interface UniverseSource {
    /** @return 거래대금 순 알트 목록. 조회 실패면 **null** — 불완전한 순위로 판정하지 않는다(호출부가 직전 목록 유지). */
    suspend fun select(exclude: Set<String>, count: Int): List<String>?

    companion object {
        val NONE = UniverseSource { _, _ -> null }
    }
}

/**
 * Upbit 24h 거래대금 상위로 알트 스윙 유니버스를 고른다. 싱글톤 — 유저 엔진 수만큼 같은 공개 조회를 반복하지 않게
 * 짧은 TTL 스냅샷을 공유한다. 인증 없는 공용 클라이언트를 쓰므로 사용자 키 장애와 결합되지 않는다.
 *
 * 순수 선정([rank])과 조회를 분리해 테스트는 네트워크 없이 규칙만 본다.
 */
@Service
class UniverseSelector(
    @Qualifier("publicUpbitClient") private val client: UpbitClient,
    private val properties: UniverseProperties,
    private val clock: Clock = Clock.systemUTC(),
) : UniverseSource {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Snapshot(val at: Instant, val ranked: List<String>)

    @Volatile
    private var snapshot: Snapshot? = null
    private val refresh = Mutex()

    override suspend fun select(exclude: Set<String>, count: Int): List<String>? {
        val ranked = try {
            rankedMarkets()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Universe fetch failed — keeping previous universe: {}", e.message)
            return null
        }
        return ranked.filter { it !in exclude }.take(count)
    }

    private suspend fun rankedMarkets(): List<String> = refresh.withLock {
        val now = clock.instant()
        snapshot?.takeIf { Duration.between(it.at, now) < SNAPSHOT_TTL }?.let { return it.ranked }
        val markets = client.getMarkets().filter { it.market.startsWith(KRW_PREFIX) }
        // /v1/ticker 는 markets 를 콤마로 받는다 — 300종 가까운 목록은 나눠 보낸다.
        val tickers = markets.map { it.market }.chunked(TICKER_BATCH).flatMap { client.getTicker(it.joinToString(",")) }
        val ranked = rank(markets, tickers)
        snapshot = Snapshot(now, ranked)
        log.info("Universe snapshot refreshed: {} KRW markets ranked (auto={}, altCount={})", ranked.size, properties.auto, properties.altCount)
        ranked
    }

    companion object {
        private const val KRW_PREFIX = "KRW-"
        private const val TICKER_BATCH = 100
        private val SNAPSHOT_TTL: Duration = Duration.ofMinutes(1)

        /** 투자유의·페그 자산·비KRW 제외, 시세 응답에 없는 마켓 제외, 24h 거래대금 내림차순. */
        fun rank(markets: List<MarketInfo>, tickers: List<Ticker>): List<String> {
            val valueByMarket = tickers.associate { it.market to it.accTradePrice24h }
            return markets
                .filter { it.market.startsWith(KRW_PREFIX) && !it.warning && it.market !in PeggedAssets.MARKETS }
                .mapNotNull { m -> valueByMarket[m.market]?.let { m.market to it } }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
}
