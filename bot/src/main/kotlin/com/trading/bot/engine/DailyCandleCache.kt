package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.common.domain.Candle
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
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
    private class Entry(val fetchedAtMs: Long, val candles: List<Candle>)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(ticker: String, count: Int): List<Candle> {
        val now = clock.millis()
        entries[ticker]?.takeIf { now - it.fetchedAtMs < TTL_MS && it.candles.size >= count }?.let { return it.candles }
        val candles = client.getDayCandles(ticker, count)
        entries[ticker] = Entry(now, candles)
        return candles
    }

    companion object {
        const val TTL_MS = 60_000L
    }
}
