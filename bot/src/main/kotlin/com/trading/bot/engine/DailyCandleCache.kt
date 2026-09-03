package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.common.domain.Candle
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * watchlist 밖 티커의 D1 REST 폴백 캐시. Upbit 레이트리밋은 프로세스(IP) 단위인데 엔진은 사용자당 하나라,
 * 엔진 필드로 두면 사용자 수만큼 같은 캔들을 다시 받는다 — [UniverseSelector] 와 같은 이유로 싱글톤이다.
 * TTL 은 ingestion 의 캔들 주기(60초)와 같아 신선도는 store 경로와 동일하다.
 */
@Service
class DailyCandleCache(
    @Qualifier("publicUpbitClient") private val client: UpbitClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Entry(val fetchedAtMs: Long, val requested: Int, val candles: List<Candle>)

    private val entries = ConcurrentHashMap<String, Entry>()

    // 티커별 miss 를 하나로 합친다 — 여러 엔진이 TTL 만료 직후 같은 티커를 동시에 치면 전부 REST 로 나간다.
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(ticker: String, count: Int): List<Candle> {
        fresh(ticker, count)?.let { return it }
        return locks.computeIfAbsent(ticker) { Mutex() }.withLock {
            fresh(ticker, count)?.let { return it }
            val candles = client.getDayCandles(ticker, count)
            entries[ticker] = Entry(clock.millis(), count, candles)
            candles
        }
    }

    private fun fresh(ticker: String, count: Int): List<Candle>? =
        entries[ticker]?.takeIf { clock.millis() - it.fetchedAtMs < TTL_MS && it.covers(count) }?.candles

    // 요청 개수 이상을 받아뒀거나, 거래소가 요청보다 적게 준 경우(상장 60일 미만 — 그 이상은 존재하지 않는다).
    // 후자를 miss 로 보면 신규 상장 종목이 매 tick REST 를 다시 쳐 TTL 이 무효가 된다.
    private fun Entry.covers(count: Int): Boolean = requested >= count || candles.size < requested

    companion object {
        const val TTL_MS = 60_000L
    }
}
