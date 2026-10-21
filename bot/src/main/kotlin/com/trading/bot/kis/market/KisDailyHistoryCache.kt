package com.trading.bot.kis.market

import com.trading.bot.kis.client.KisApi
import com.trading.bot.kis.client.KisDailyCandle
import com.trading.bot.kis.client.KisLane
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 종목별 확정 일봉(어제까지)을 KST 하루 동안 재사용한다. 진행 중인 오늘 봉은 담지 않으므로 [KisDailyCandles.merge] 가 시세로 붙인다.
 * 실패는 캐시에 남기지 않고 그대로 던진다.
 * 시세 자료라 계좌·키와 무관해 [KisApi] 는 호출마다 받는다 — 키가 바뀌어 옛 [KisApi] 가 무효가 돼도 캐시는 그대로 쓴다.
 */
class KisDailyHistoryCache(private val clock: Clock) {
    private class Entry(val day: LocalDate, val fetchedAt: Instant, val requested: Int, val candles: List<KisDailyCandle>)

    private val entries = ConcurrentHashMap<String, Entry>()

    // 같은 종목을 여러 엔진이 동시에 처음 요청해도 KIS 호출은 한 번만 나가게 한다.
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 최신이 [0] 이고 최대 [count] 개다. */
    suspend fun history(api: KisApi, code: String, count: Int, lane: KisLane): List<KisDailyCandle> {
        require(count >= 1) { "count 는 1 이상이어야 한다: $count" }
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, KrxTradingCalendar.KST)
        fresh(code, count, today, now)?.let { return it.take(count) }
        return locks.computeIfAbsent(code) { Mutex() }.withLock {
            fresh(code, count, today, now)?.let { return it.take(count) }
            val candles = fetch(api, code, count, today, lane)
            entries[code] = Entry(today, now, count, candles)
            candles.take(count)
        }
    }

    private fun fresh(code: String, count: Int, today: LocalDate, now: Instant): List<KisDailyCandle>? =
        entries[code]?.takeIf { it.day == today && !it.shortExpired(now) && it.covers(count) }?.candles

    // 받은 행이 요청보다 적으면 상장 기간이 짧아 더 받을 봉이 없다. 이를 miss 로 보면 신규 상장 종목은 매번 다시 부른다.
    private fun Entry.covers(count: Int): Boolean = requested >= count || candles.size < requested

    // 요청보다 적게 온 응답은 일시적으로 비거나 덜 온 것일 수도 있어 하루 내내 믿지 않고 SHORT_RETRY 뒤 다시 받는다.
    private fun Entry.shortExpired(now: Instant): Boolean =
        candles.size < requested && Duration.between(fetchedAt, now) >= SHORT_RETRY

    // 휴장일을 넉넉히 덮도록 요청 개수의 두 배에 10일을 더한 달력 기간을 받는다.
    private suspend fun fetch(api: KisApi, code: String, count: Int, today: LocalDate, lane: KisLane): List<KisDailyCandle> {
        val from = today.minusDays(count * 2L + 10)
        val rows = mutableListOf<KisDailyCandle>()
        var to = today.minusDays(1)
        while (!to.isBefore(from)) {
            val chunkFrom = maxOf(from, to.minusDays(CHUNK_DAYS - 1))
            rows += api.dailyCandles(code, chunkFrom, to, lane = lane)
            to = chunkFrom.minusDays(1)
        }
        return rows.distinctBy { it.date }.sortedByDescending { it.date }
    }

    companion object {
        // 140일(20주)에는 평일이 100일을 넘지 않아 KIS 의 호출당 100행 한도에 걸리지 않는다.
        const val CHUNK_DAYS = 140L

        val SHORT_RETRY: Duration = Duration.ofMinutes(10)
    }
}
