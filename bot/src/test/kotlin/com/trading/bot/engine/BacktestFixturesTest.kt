package com.trading.bot.engine

import com.trading.bot.engine.BacktestFixtures.Regime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * fixture 자체의 무결성을 고정한다.
 *
 * 특히 **정렬 방향**이 중요하다 — 재수집이 오름차순으로 이뤄지면 [BacktestFixtures.slice] 가 예외 없이
 * 정반대 구간을 잘라내고, 백테는 조용히 잘못된 기간의 수치를 낸다.
 */
class BacktestFixturesTest {

    @Test
    fun `every market fixture has the expected shape`() {
        for (regime in Regime.entries) for (market in BacktestFixtures.markets(regime)) {
            val candles = BacktestFixtures.load(regime, market)

            assertEquals(200, candles.size, "$market: 200봉이 아니다")
            assertTrue(candles.all { it.market == market }, "$market: 다른 마켓 캔들이 섞였다")
            assertTrue(
                candles.all { it.openingPrice > 0 && it.highPrice > 0 && it.lowPrice > 0 && it.tradePrice > 0 },
                "$market: 0 이하 가격이 있다",
            )
            assertTrue(
                candles.all { it.highPrice >= maxOf(it.openingPrice, it.tradePrice) },
                "$market: 고가가 시/종가보다 낮다",
            )
            assertTrue(
                candles.all { it.lowPrice <= minOf(it.openingPrice, it.tradePrice) },
                "$market: 저가가 시/종가보다 높다",
            )
        }
    }

    @Test
    fun `fixtures are ordered newest first`() {
        for (regime in Regime.entries) for (market in BacktestFixtures.markets(regime)) {
            val dates = BacktestFixtures.load(regime, market).map { it.candleDateTimeKst }
            assertEquals(
                dates.sortedDescending(), dates,
                "$market: 최신순이 아니다 — slice 가 반대 구간을 자르게 된다",
            )
        }
    }

    @Test
    fun `bull regime covers only the markets listed back then`() {
        // 현재 거래대금 상위 8개 중 4개는 2023-11 에 미상장이었다 — 유니버스 선정의 생존편향 증거이자,
        // paired 비교를 4마켓으로 제한해야 하는 이유다.
        // 기대값을 구현 상수(PAIRED_MARKETS)로 쓰면 그 상수가 바뀔 때 테스트도 같이 바뀌어 통과한다.
        // 실제 fixture 에 있어야 하는 목록을 여기 독립적으로 적는다.
        assertEquals(
            listOf("KRW-XRP", "KRW-BTC", "KRW-MMT", "KRW-ETH", "KRW-WLD", "KRW-RVN", "KRW-ONDO", "KRW-DOGE"),
            BacktestFixtures.markets(Regime.BEAR),
        )
        assertEquals(
            listOf("KRW-XRP", "KRW-BTC", "KRW-ETH", "KRW-DOGE"),
            BacktestFixtures.markets(Regime.BULL),
            "BULL 은 2023-11 당시 상장돼 있던 4개여야 한다",
        )
        assertEquals(
            listOf("KRW-XRP", "KRW-BTC", "KRW-ETH", "KRW-DOGE"),
            BacktestFixtures.PAIRED_MARKETS,
            "paired 마켓은 두 국면 교집합이어야 한다",
        )
    }

    @Test
    fun `slice keeps chronological order and picks the intended window`() {
        val candles = BacktestFixtures.load(Regime.BEAR, "KRW-BTC")
        val chronological = candles.reversed()

        val head = BacktestFixtures.slice(candles, 0, 9)
        assertEquals(10, head.size)
        // 반환도 최신순이므로 마지막 원소가 시간순 첫 봉이어야 한다.
        assertEquals(chronological[0].candleDateTimeKst, head.last().candleDateTimeKst)
        assertEquals(chronological[9].candleDateTimeKst, head.first().candleDateTimeKst)

        assertEquals(130, BacktestFixtures.inSample(candles).size)
        assertEquals(120, BacktestFixtures.outOfSample(candles).size)
        assertEquals(
            chronological[80].candleDateTimeKst,
            BacktestFixtures.outOfSample(candles).last().candleDateTimeKst,
            "out-of-sample 이 원본 80번째 봉에서 시작하지 않는다",
        )
    }
}
