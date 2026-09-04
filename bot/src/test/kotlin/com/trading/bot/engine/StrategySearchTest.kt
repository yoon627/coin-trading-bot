package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.strategy.CombinedStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage A 스윕 하네스의 계약 — 게이트·그리드·신호 캐시·null 대조군이 **결과를 보기 전에** 고정돼 있는지 지킨다.
 *
 * 판정 기준의 단일 소스는 plan `2026-09-03-strategy-search-yearly`(사전고정 커밋 391118e)이고, 이 파일은 그 기준의 경계를
 * 실행 가능한 형태로 못박는다. 여기 상수를 바꾸는 것은 사전고정을 바꾸는 것이므로 plan 개정과 같은 커밋이어야 한다.
 */
class StrategySearchTest {

    // ---------------------------------------------------------------- grid

    @Test
    fun `arm axis drops values that cannot fire for the given trailing stop`() {
        // ExitGates: pnl>0 ∧ drop≥trail 이면 peakPnl > trail/(1−trail/100) 이 강제된다 —
        // 그 하한 이하의 arm 은 서로 완전히 같은 동작이라 그리드에 두면 다중비교 분모와 plateau 가 동시에 무너진다.
        assertEquals(listOf(0.0, 2.0, 3.0, 5.0), StrategySearchGrid.armValuesFor(1.5))
        assertEquals(listOf(0.0, 3.0, 5.0), StrategySearchGrid.armValuesFor(2.0))
        assertEquals(listOf(0.0, 5.0), StrategySearchGrid.armValuesFor(3.0))
        assertEquals(listOf(0.0), StrategySearchGrid.armValuesFor(5.0))
        assertEquals(listOf(0.0), StrategySearchGrid.armValuesFor(StrategySearchGrid.TRAILING_OFF))
    }

    @Test
    fun `kValue only varies for the two strategies that read it`() {
        assertEquals(listOf(0.3, 0.5, 0.7), StrategySearchGrid.kValuesFor("volatility_breakout"))
        assertEquals(listOf(0.3, 0.5, 0.7), StrategySearchGrid.kValuesFor("combined"))
        assertEquals(listOf(0.5), StrategySearchGrid.kValuesFor("rsi_bounce"))
    }

    @Test
    fun `every generated point is valid and the baseline is one of them`() {
        val grid = StrategySearchGrid.stageA()
        assertTrue(grid.points.isNotEmpty())
        assertTrue(grid.points.all { it.trailingArmPct in StrategySearchGrid.armValuesFor(it.trailingStopPct) }, "arm 조건")
        assertTrue(grid.points.all { it.kValue in StrategySearchGrid.kValuesFor(it.strategy) }, "kValue 조건")
        assertEquals(grid.points.size, grid.points.distinct().size, "중복 좌표 없음")
        assertTrue(grid.points.contains(StrategySearchGrid.baselinePoint()), "라이브 baseline 이 그리드 안에 있어야 대조가 된다")
    }

    @Test
    fun `neighbours are one step on exactly one axis and stay inside the grid`() {
        val grid = StrategySearchGrid.stageA()
        val point = StrategySearchGrid.baselinePoint()
        val neighbours = grid.neighbours(point)
        assertTrue(neighbours.isNotEmpty())
        assertFalse(neighbours.contains(point), "자기 자신은 이웃이 아니다")
        assertTrue(neighbours.all { it in grid.points }, "이웃도 유효 좌표")
        assertTrue(neighbours.all { StrategySearchGrid.axisDistance(point, it) == 1 }, "한 축 한 스텝")
    }

    // ---------------------------------------------------------------- gates

    private fun deltas(vararg v: Double) = v.toList()

    @Test
    fun `G1 needs both the effect size and the market count`() {
        assertTrue(StrategySearchGates.g1(deltas(2.0, 2.0, 2.0, 2.0, 2.0, 2.0, -1.0, -1.0)), "중앙값 2.0·양수 6 = 경계 통과")
        assertFalse(StrategySearchGates.g1(deltas(1.99, 1.99, 1.99, 1.99, 1.99, 1.99, -1.0, -1.0)), "중앙값 미달")
        assertFalse(StrategySearchGates.g1(deltas(9.0, 9.0, 9.0, 9.0, 9.0, -1.0, -1.0, -1.0)), "양수 마켓 5 = 미달")
    }

    @Test
    fun `G2 only asks for direction consistency`() {
        assertTrue(StrategySearchGates.g2(deltas(1.0, 1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0)), "양수 5 = 경계 통과")
        assertFalse(StrategySearchGates.g2(deltas(1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0, -1.0)), "양수 4 = 미달")
        // 8마켓에서 양수 ≥5 면 4·5번째 순서통계량이 모두 양수라 중앙값도 반드시 양수다 — 즉 G2 의 중앙값 조건은
        // 마켓 수 조건에 흡수된다. 두 조건을 함께 둔 것은 의도를 남기기 위함이고, 실제로 거르는 것은 마켓 수다.
        assertTrue(StrategySearchGates.g2(deltas(-5.0, -5.0, -5.0, 1.0, 1.0, 1.0, 1.0, 1.0)), "양수 5 면 중앙값 조건은 자동 충족")
    }

    @Test
    fun `G4 tolerates a small regime loss but not a large one`() {
        assertTrue(StrategySearchGates.g4(deltas(-1.0, -1.0, -1.0, -1.0)), "−1.0%p 경계 통과")
        assertFalse(StrategySearchGates.g4(deltas(-1.01, -1.01, -1.01, -1.01)))
    }

    @Test
    fun `G5 rejects thin samples and markets that never traded`() {
        assertTrue(StrategySearchGates.g5(trades = 8, zeroTradeMarkets = 1))
        assertFalse(StrategySearchGates.g5(trades = 7, zeroTradeMarkets = 0), "거래수 미달")
        assertFalse(StrategySearchGates.g5(trades = 40, zeroTradeMarkets = 2), "0거래 마켓 초과")
    }

    @Test
    fun `G6 uses the median delta as the main criterion and the worst market as a cap`() {
        assertTrue(StrategySearchGates.g6(mddDeltas = deltas(2.0, 2.0, 2.0, 2.0), worst = 15.0, baselineWorst = 10.0), "중앙 +2.0%p·최악 1.5배 = 경계")
        assertFalse(StrategySearchGates.g6(mddDeltas = deltas(2.01, 2.01, 2.01, 2.01), worst = 11.0, baselineWorst = 10.0), "중앙 초과")
        assertFalse(StrategySearchGates.g6(mddDeltas = deltas(0.0, 0.0, 0.0, 0.0), worst = 15.1, baselineWorst = 10.0), "최악 초과")
    }

    @Test
    fun `plateau needs most of the surviving neighbours to pass, and an isolated peak fails`() {
        val neighbours = (1..10).map { StrategySearchGrid.baselinePoint().copy(maxLossPct = it.toDouble()) }
        assertTrue(StrategySearchGates.plateau(neighbours, passing = neighbours.take(7).toSet()), "7/10 = 경계 통과")
        assertFalse(StrategySearchGates.plateau(neighbours, passing = neighbours.take(6).toSet()), "6/10 = 미달")
        assertFalse(StrategySearchGates.plateau(neighbours, passing = emptySet()), "고립된 peak")
        assertFalse(StrategySearchGates.plateau(emptyList(), passing = emptySet()), "이웃이 없으면 plateau 를 주장할 수 없다")
    }

    // ------------------------------------------------------- signal caching

    @Test
    fun `cached signals are keyed by market — two markets sharing a date must not collide`() = runTest {
        // yearly fixture 8종은 같은 365개 날짜를 공유한다. 키에 마켓이 없으면 BTC 신호가 ETH 에 그대로 재생된다.
        val cache = SignalCache()
        val btc = YearlyFixtures.load("KRW-BTC").reversed()
        val eth = YearlyFixtures.load("KRW-ETH").reversed()
        val plain = CombinedStrategy()
        val props = TradingProperties()

        var differed = false
        for (i in 60 until 200) {
            val wBtc = btc.subList(i - 49, i + 1).reversed()
            val wEth = eth.subList(i - 49, i + 1).reversed()
            assertEquals(wBtc.first().candleDateTimeKst, wEth.first().candleDateTimeKst, "같은 날짜여야 충돌 재현이 된다")
            val cachedBtc = cache.decorate(plain, "KRW-BTC").shouldBuy(wBtc, wBtc.first().tradePrice, props)
            val cachedEth = cache.decorate(plain, "KRW-ETH").shouldBuy(wEth, wEth.first().tradePrice, props)
            assertEquals(plain.shouldBuy(wBtc, wBtc.first().tradePrice, props), cachedBtc, "BTC 신호 보존")
            assertEquals(plain.shouldBuy(wEth, wEth.first().tradePrice, props), cachedEth, "ETH 신호 보존")
            if (cachedBtc != cachedEth) differed = true
        }
        assertTrue(differed, "두 마켓이 실제로 다른 신호를 내는 봉이 있어야 이 테스트가 충돌을 잡는다")
    }

    @Test
    fun `caching a strategy does not change a single trade`() = runTest {
        val plain = CombinedStrategy()
        val candles = YearlyFixtures.load("KRW-SOL")
        val configs = listOf(
            BacktestConfig(reentryMode = ReentryMode.LIVE_SAME_BAR),
            BacktestConfig(takeProfitPct = 3.0, maxLossPct = 7.0, maxHoldDays = 5, reentryMode = ReentryMode.LIVE_SAME_BAR),
            BacktestConfig(takeProfitPct = StrategySearchGrid.TAKE_PROFIT_OFF, trailingStopPct = StrategySearchGrid.TRAILING_OFF, maxHoldDays = 365),
        )
        val cache = SignalCache()
        val cached = cache.decorate(plain, "KRW-SOL")
        for (config in configs) {
            val a = BacktestEngine(listOf(plain), TradingProperties()).run(plain.name, candles, "KRW-SOL", config)
            val b = BacktestEngine(listOf(cached), TradingProperties()).run(cached.name, candles, "KRW-SOL", config)
            assertEquals(a?.trades, b?.trades, "config=$config 에서 trade 단위 동일")
        }
    }

    @Test
    fun `the cache decorator delegates identity so the engine can find it`() {
        val plain = CombinedStrategy()
        val cached = SignalCache().decorate(plain, "KRW-BTC")
        assertEquals(plain.name, cached.name)
        assertEquals(plain.minCandles, cached.minCandles)
    }

    // ------------------------------------------------------ null 대조군

    @Test
    fun `random entry is a pure function of seed, market and bar`() = runTest {
        val props = TradingProperties()
        val candles = YearlyFixtures.load("KRW-BTC").reversed()
        val window = candles.subList(100, 150).reversed()
        val a = RandomEntryStrategy(seed = 7, market = "KRW-BTC", entryRate = 0.3)
        val b = RandomEntryStrategy(seed = 7, market = "KRW-BTC", entryRate = 0.3)
        val other = RandomEntryStrategy(seed = 8, market = "KRW-BTC", entryRate = 0.3)
        val otherMarket = RandomEntryStrategy(seed = 7, market = "KRW-ETH", entryRate = 0.3)

        // 호출 순서를 뒤섞어도 같은 봉이면 같은 답이어야 한다 — 상태 있는 RNG 면 여기서 깨진다.
        val first = a.shouldBuy(window, window.first().tradePrice, props)
        repeat(5) { a.shouldBuy(candles.subList(50, 100).reversed(), 1.0, props) }
        assertEquals(first, a.shouldBuy(window, window.first().tradePrice, props), "같은 입력 → 같은 결과")
        assertEquals(first, b.shouldBuy(window, window.first().tradePrice, props), "인스턴스 무관")

        var seedDiffers = false
        var marketDiffers = false
        for (i in 60 until 300) {
            val w = candles.subList(i - 49, i + 1).reversed()
            if (a.shouldBuy(w, w.first().tradePrice, props) != other.shouldBuy(w, w.first().tradePrice, props)) seedDiffers = true
            if (a.shouldBuy(w, w.first().tradePrice, props) != otherMarket.shouldBuy(w, w.first().tradePrice, props)) marketDiffers = true
        }
        assertTrue(seedDiffers, "seed 가 다르면 다른 진입")
        assertTrue(marketDiffers, "마켓이 다르면 다른 진입")
    }

    @Test
    fun `random entry rate is close to the requested probability`() = runTest {
        val props = TradingProperties()
        val candles = YearlyFixtures.load("KRW-BTC").reversed()
        val strategy = RandomEntryStrategy(seed = 1, market = "KRW-BTC", entryRate = 0.25)
        var hits = 0
        var n = 0
        for (i in 50 until candles.size) {
            val w = candles.subList(i - 49, i + 1).reversed()
            if (strategy.shouldBuy(w, w.first().tradePrice, props)) hits++
            n++
        }
        assertEquals(0.25, hits.toDouble() / n, 0.06, "표본 ${n}봉에서 요청 확률 근방")
    }

    // ---------------------------------------------------------------- report

    @Test
    fun `a report with no surviving candidate still states the denominator and where they died`() {
        val report = StrategySearchReport.render(
            title = "테스트",
            nominalConfigs = 120,
            uniqueBehaviours = 74,
            eliminations = linkedMapOf("G1" to 70, "G2" to 3, "G3" to 1, "G5" to 0, "G6" to 0),
            survivors = emptyList(),
            nullSummary = null,
            metadata = mapOf("fixture" to "yearly"),
        )
        assertTrue(report.contains("통과 0 / 74"), "0건도 분모와 함께 보고해야 한다\n$report")
        assertTrue(report.contains("G1") && report.contains("70"), "게이트별 탈락 수")
        assertTrue(report.contains("명목 120"), "명목 config 수도 함께")
    }

    @Test
    fun `identical trade behaviour collapses to one unique candidate`() {
        val trade = BacktestTrade(1, 2, 100.0, 105.0, 4.9, 1, "TAKE_PROFIT")
        val a = mapOf("KRW-BTC" to listOf(trade))
        val b = mapOf("KRW-BTC" to listOf(trade.copy()))
        val c = mapOf("KRW-BTC" to listOf(trade.copy(sellPrice = 106.0)))
        assertEquals(StrategySearch.fingerprintOf(a), StrategySearch.fingerprintOf(b))
        assertNotEquals(StrategySearch.fingerprintOf(a), StrategySearch.fingerprintOf(c))
    }
}
