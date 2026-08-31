package com.trading.bot.engine

import com.trading.common.domain.Candle
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 시점 중립 유니버스 selector 의 결정성 검증 (#112 A1·A2).
 *
 * 네트워크를 타지 않는다 — 수집 스냅샷을 직접 만들어 넣는다. 이 분리가 없으면 `/v1/market/all` 이
 * 시간에 따라 바뀌어 같은 명령이 같은 답을 내지 못한다.
 */
class PointInTimeUniverseTest {

    private val ASOF: LocalDate = LocalDate.parse("2023-11-23")

    /** t0 직전부터 하루씩 거슬러 올라가는 연속 일봉(최신순) — 실제 API 응답과 같은 순서·간격. */
    private fun candles(
        market: String,
        count: Int,
        tradePrice: Double,
        stepDays: Long = 1,
    ): List<Candle> =
        (0 until count).map { i ->
            val d = ASOF.minusDays(1L + i * stepDays)
            Candle(
                market = market,
                candleDateTimeKst = "${d}T09:00:00",
                candleAccTradePrice = tradePrice,
                tradePrice = 1000.0,
            )
        }

    /** 거래대금이 큰 순서대로 이름을 붙인 후보 n개. m01 이 가장 크다. */
    private fun snapshotOf(
        count: Int,
        history: Int = PointInTimeUniverse.MIN_HISTORY_DAYS,
        extra: Map<String, List<Candle>> = emptyMap(),
        missing: List<String> = emptyList(),
    ): PointInTimeUniverse.Snapshot {
        val generated = (1..count).associate { i ->
            val market = "KRW-M%02d".format(i)
            market to candles(market, history, (count - i + 1) * 1_000_000_000.0)
        }
        val all = generated + extra
        return PointInTimeUniverse.Snapshot(
            asOf = ASOF.toString(),
            candidates = all.keys.toList() + missing,
            candles = all,
            missing = missing,
        )
    }

    @Test
    fun `ranks by mean trade price and takes the top 8`() {
        val result = PointInTimeUniverse.select(snapshotOf(count = 12))

        assertFalse(result.incomplete)
        assertEquals(
            listOf("KRW-M01", "KRW-M02", "KRW-M03", "KRW-M04", "KRW-M05", "KRW-M06", "KRW-M07", "KRW-M08"),
            result.universe,
        )
        assertEquals(PointInTimeUniverse.UNIVERSE_SIZE, result.universe.size)
    }

    @Test
    fun `refuses to judge when any candidate lookup is missing`() {
        // 누락을 거래대금 0 으로 흡수하면 다른 마켓이 부당하게 상위로 올라간다 — 조용한 랭킹 오염.
        val result = PointInTimeUniverse.select(snapshotOf(count = 12, missing = listOf("KRW-GONE")))

        assertTrue(result.incomplete, "누락이 있으면 incomplete 여야 한다")
        assertTrue(result.universe.isEmpty(), "incomplete 면 유니버스를 내지 않는다: ${result.universe}")
    }

    @Test
    fun `excludes stablecoins even when they top the ranking`() {
        val usdt = "KRW-USDT"
        val result = PointInTimeUniverse.select(
            snapshotOf(
                count = 12,
                // 어떤 후보보다도 큰 거래대금 — 규칙이 없으면 1위가 된다.
                extra = mapOf(usdt to candles(usdt, PointInTimeUniverse.MIN_HISTORY_DAYS, 99_000_000_000.0)),
            ),
        )

        assertFalse(result.universe.contains(usdt), "스테이블코인은 제외: ${result.universe}")
        assertEquals(PointInTimeUniverse.EXCLUDED_STABLECOIN, result.excluded[usdt])
    }

    @Test
    fun `excludes markets without a full history window`() {
        // t0 직전 30봉이 완비되지 않은 종목 — 상장 당일 거래대금 스파이크가 상위를 오염시키는 것을 막는다.
        val fresh = "KRW-NEW"
        val result = PointInTimeUniverse.select(
            snapshotOf(
                count = 12,
                extra = mapOf(
                    fresh to candles(fresh, PointInTimeUniverse.MIN_HISTORY_DAYS - 1, 99_000_000_000.0),
                ),
            ),
        )

        assertFalse(result.universe.contains(fresh), "이력 부족 종목은 제외: ${result.universe}")
        assertEquals(PointInTimeUniverse.EXCLUDED_SHORT_HISTORY, result.excluded[fresh])
    }

    @Test
    fun `excludes markets whose window has date gaps`() {
        // 봉 **수**만 세면 거래 공백이 있는 종목이 통과한다 — 30봉이 30일이 아니라 60일을 덮으면
        // "t0 직전 30일 평균"이 아니라 "관측된 30 거래일 평균"이 되어 랭킹 기준이 종목마다 달라진다.
        val gapped = "KRW-GAP"
        val result = PointInTimeUniverse.select(
            snapshotOf(
                count = 12,
                extra = mapOf(
                    // 봉 수는 30개로 완비지만 이틀에 하나씩이라 60일을 덮는다.
                    gapped to candles(gapped, PointInTimeUniverse.MIN_HISTORY_DAYS, 99_000_000_000.0, stepDays = 2),
                ),
            ),
        )

        assertFalse(result.universe.contains(gapped), "창이 늘어진 종목은 제외: ${result.universe}")
        assertEquals(PointInTimeUniverse.EXCLUDED_GAPPED_WINDOW, result.excluded[gapped])
    }

    @Test
    fun `refuses to judge when a window has unparseable dates`() {
        // 날짜를 못 읽으면 창이 진짜 30일인지 확인할 수 없다. 조용히 통과시키면 창 검사가 무력화되고,
        // 조용히 제외하면 top-8 에 들어야 할 마켓이 빠져 랭킹이 오염된다 — 둘 다 P0-4 와 같은 부류다.
        val broken = "KRW-BROKEN"
        val result = PointInTimeUniverse.select(
            snapshotOf(
                count = 12,
                extra = mapOf(
                    broken to List(PointInTimeUniverse.MIN_HISTORY_DAYS) {
                        Candle(market = broken, candleAccTradePrice = 99_000_000_000.0, tradePrice = 1000.0)
                    },
                ),
            ),
        )

        assertTrue(result.incomplete, "날짜를 못 읽는 창이 있으면 판정하지 않는다")
        assertTrue(result.universe.isEmpty(), "incomplete 면 유니버스를 내지 않는다: ${result.universe}")
    }

    @Test
    fun `refuses to judge when a middle candle has an unparseable date`() {
        // 양끝만 검증하면 중간 봉의 날짜가 깨져도 그 봉의 거래대금이 평균에 섞인 채 감사가 "정상 완료"된다.
        val broken = "KRW-MID"
        val window = candles(broken, PointInTimeUniverse.MIN_HISTORY_DAYS, 99_000_000_000.0)
            .toMutableList()
        window[10] = window[10].copy(candleDateTimeKst = "")

        val result = PointInTimeUniverse.select(snapshotOf(count = 12, extra = mapOf(broken to window)))

        assertTrue(result.incomplete, "중간 봉 날짜가 불량이면 판정하지 않는다")
        assertTrue(result.universe.isEmpty(), "incomplete 면 유니버스를 내지 않는다: ${result.universe}")
    }

    @Test
    fun `distinguishes not-listed from short history`() {
        // 빈 응답(t0 시점 미상장)과 이력 부족은 다른 사유다 — 뭉치면 3단 감쇠 ①②의 의미가 흐려진다.
        val notListed = "KRW-NONE"
        val result = PointInTimeUniverse.select(
            snapshotOf(count = 12, extra = mapOf(notListed to emptyList())),
        )

        assertEquals(PointInTimeUniverse.EXCLUDED_NOT_LISTED, result.excluded[notListed])
    }

    @Test
    fun `breaks ties deterministically by market code`() {
        // 같은 거래대금이면 순서가 흔들리면 안 된다 — 재현성이 깨진다.
        val tied = (1..10).associate { i ->
            val market = "KRW-T%02d".format(i)
            market to candles(market, PointInTimeUniverse.MIN_HISTORY_DAYS, 5_000_000_000.0)
        }
        val snapshot = PointInTimeUniverse.Snapshot(
            asOf = ASOF.toString(),
            candidates = tied.keys.shuffled(),
            candles = tied,
        )

        val first = PointInTimeUniverse.select(snapshot).universe
        val second = PointInTimeUniverse.select(snapshot).universe

        assertEquals(first, second, "같은 입력은 같은 결과를 내야 한다")
        assertEquals(
            listOf("KRW-T01", "KRW-T02", "KRW-T03", "KRW-T04", "KRW-T05", "KRW-T06", "KRW-T07", "KRW-T08"),
            first,
            "동점은 마켓 코드 오름차순으로 가른다",
        )
    }

    @Test
    fun `averages the window rather than summing it`() {
        // 봉 수가 다른 종목을 합계로 비교하면 이력이 긴 쪽이 유리해진다. 여기선 이력이 같으므로
        // 평균과 합계가 같은 순서를 내지만, 평균값 자체를 보고에 쓰므로 값을 고정한다.
        val result = PointInTimeUniverse.select(snapshotOf(count = 9))

        assertFalse(result.ranked.isEmpty(), "랭킹이 비어 있다 — 선정이 돌지 않았다")
        val top = result.ranked.first()
        assertEquals("KRW-M01", top.market)
        assertEquals(9_000_000_000.0, top.avgTradePrice, 1e-6)
    }
}
