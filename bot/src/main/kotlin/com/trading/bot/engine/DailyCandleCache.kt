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
 * watchlist 밖 티커의 일봉 REST 캐시. Upbit 레이트리밋은 IP 단위이고 엔진은 사용자마다 하나라서
 * 엔진마다 두지 않고 싱글톤으로 둔다. TTL 은 캔들 수집 주기(60초)에 맞췄다.
 */
@Service
class DailyCandleCache(
    @Qualifier("publicUpbitClient") private val client: UpbitClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Entry(val fetchedAtMs: Long, val requested: Int, val candles: List<Candle>)

    private val entries = ConcurrentHashMap<String, Entry>()

    // TTL 만료 직후 여러 엔진이 같은 티커를 동시에 요청해도 REST 호출은 한 번만 나가게 한다.
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

    // 거래소가 요청보다 적게 줬다면 상장 기간이 짧아 더 받을 캔들이 없다는 뜻이다.
    // 이를 miss 로 보면 신규 상장 종목은 매 tick REST 를 다시 호출한다.
    private fun Entry.covers(count: Int): Boolean = requested >= count || candles.size < requested

    companion object {
        const val TTL_MS = 60_000L
    }
}
