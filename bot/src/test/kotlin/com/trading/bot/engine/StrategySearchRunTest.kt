package com.trading.bot.engine

import com.trading.common.domain.Candle
import com.trading.common.strategy.CombinedStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 스윕 실행 드라이버 — 무거운 실행은 env 게이트 뒤에 두고, 게이트 없는 스모크 1건이 파이프라인을 CI 에서 지킨다.
 *
 * - 그리드 크기 확인:  `./gradlew :bot:test --tests "*StrategySearchRunTest*"`
 * - 런타임 벤치마크:   `RUN_SEARCH_BENCH=true ./gradlew :bot:test --tests "*StrategySearchRunTest*" --rerun-tasks`
 *   (**합성 캔들만 쓴다** — fixture 성과 수치를 사전고정 커밋 전에 보면 그리드·전략 선택이 결과에 오염된다)
 */
class StrategySearchRunTest {

    private val search = StrategySearch()

    /** 사전고정 seed 목록(plan Decisions 2). 91 = 13(전략×kValue 조합 수)의 배수라 max-statistic 환산이 정수배가 된다. */
    private val NULL_SEEDS = (1..91).toList()

    @Test
    fun `the pre-registered grid is conditional, not cartesian`() {
        val grid = StrategySearchGrid.stageA()
        val cartesian = StrategySearchGrid.STRATEGIES.sumOf { s ->
            StrategySearchGrid.kValuesFor(s).size
        } * StrategySearchGrid.TAKE_PROFITS.size * StrategySearchGrid.STOP_LOSSES.size *
            StrategySearchGrid.TRAILING_STOPS.size * StrategySearchGrid.ARM_CANDIDATES.size *
            StrategySearchGrid.HOLD_DAYS.size * StrategySearchGrid.MARKET_FILTERS_STAGE_A.size
        println("[grid] conditional=${grid.points.size} cartesian=$cartesian coarse=${StrategySearchGrid.coarse().points.size}")
        assertTrue(grid.points.size < cartesian, "arm alias 를 걷어내면 Cartesian 보다 작아야 한다")
    }

    @Test
    fun `smoke — two markets, a tiny grid, end to end with a rendered report`() = runTest {
        val fixtures = listOf("KRW-BTC", "KRW-ETH").associateWith { YearlyFixtures.load(it) }
        val baseline = StrategySearchGrid.baselinePoint()
        val points = listOf(baseline, baseline.copy(maxHoldDays = 3), baseline.copy(takeProfitPct = 3.0))

        val select = search.measure(fixtures, StrategySearch.SELECT, points)
        assertEquals(points.toSet(), select.keys)
        val baselineMetrics = select.getValue(baseline)
        assertTrue(baselineMetrics.trades > 0, "baseline 이 거래를 낸다")
        assertEquals(fixtures.keys, baselineMetrics.returnByMarket.keys)
        assertTrue(baselineMetrics.returnByMarket.values.all { it.isFinite() })
        assertTrue(baselineMetrics.mddByMarket.values.all { it >= 0.0 })

        val deltas = StrategySearchGates.pairedDeltas(
            select.getValue(points[1]).returnByMarket,
            baselineMetrics.returnByMarket,
        )
        assertEquals(2, deltas.size, "마켓별 짝짓기")

        val report = StrategySearchReport.render(
            title = "스모크",
            nominalConfigs = points.size,
            uniqueBehaviours = points.map { select.getValue(it).fingerprint }.distinct().size,
            eliminations = linkedMapOf("G1" to points.size),
            survivors = emptyList(),
            nullSummary = null,
            metadata = mapOf("fixture" to "yearly(2마켓)"),
        )
        assertTrue(report.contains("통과 0 /"), "0건도 분모와 함께")
    }

    @Test
    fun `random entry control runs through the engine like any other strategy`() = runTest {
        val fixtures = mapOf("KRW-BTC" to YearlyFixtures.load("KRW-BTC"))
        val point = StrategySearchGrid.baselinePoint().copy(strategy = RandomEntryStrategy.NAME)
        val metrics = search.measure(
            fixtures, StrategySearch.SELECT, listOf(point),
            StrategySearch.Options(strategyFor = { market -> RandomEntryStrategy(seed = 1, market = market, entryRate = 0.2) }),
        )
        assertTrue(metrics.getValue(point).trades > 0, "무작위 진입도 거래를 낸다")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_SEARCH_BENCH", matches = "true")
    fun `benchmark on synthetic candles — no fixture performance figures are produced`() = runTest {
        val synthetic = mapOf("SYN-A" to syntheticCandles("SYN-A", 365, seed = 11))
        val grid = StrategySearchGrid.stageA()
        val sample = grid.points.filter { it.strategy == StrategySearchGrid.BASELINE_STRATEGY }.take(200)
        val warm = search.measure(synthetic, StrategySearch.SELECT, sample.take(20))
        assertEquals(20, warm.size)

        val ms = measureTimeMillis { search.measure(synthetic, StrategySearch.SELECT, sample) }
        val perRun = ms.toDouble() / sample.size
        val projected = perRun * grid.points.size * 8 * 2 / 1000.0
        println("[bench] ${sample.size} runs in ${ms}ms = %.3f ms/run → 전체 그리드 ${grid.points.size} × 8마켓 × 2창 ≈ %.0f초".format(perRun, projected))
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STRATEGY_SEARCH", matches = "true")
    fun `stage A full run writes the report`() = runBlocking {
        val yearly = YearlyFixtures.loadAll()
        val bull = BacktestFixtures.loadAll(BacktestFixtures.Regime.BULL)
        val bear = BacktestFixtures.loadAll(BacktestFixtures.Regime.BEAR)
        val stage = StrategySearchStageA()

        val entryRate = search.signalRate(CombinedStrategy(), yearly, StrategySearch.SELECT)
        println("[stageA] baseline 원시 신호 발생률 = %.4f".format(entryRate))
        val nullSummary = if (System.getenv("RUN_SEARCH_NULL") == "true") {
            stage.runNull(yearly, NULL_SEEDS, entryRate, ::println)
        } else {
            null
        }

        val outcome = stage.run(
            yearly, bull, bear, nullSummary,
            metadata = linkedMapOf(
                "code" to (System.getenv("GIT_SHA") ?: "미기재"),
                "fixture" to "yearly 8마켓×365봉 / bull·bear 8마켓×200봉",
                "fixture sha256" to fixtureDigest(),
                "grid" to "사전고정 Stage A (plan 391118e), 좌표 ${StrategySearchGrid.stageA().points.size}",
                "null seeds" to if (nullSummary == null) "미실행" else "${NULL_SEEDS.first()}..${NULL_SEEDS.last()}",
                "JVM" to System.getProperty("java.version"),
                "명령" to "RUN_STRATEGY_SEARCH=true RUN_SEARCH_NULL=true ./gradlew :bot:test --tests \"*StrategySearchRunTest*\" --rerun-tasks",
            ),
            log = ::println,
        )

        val out = Path.of("build/reports/strategy-search-yearly.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, outcome.report)
        println("[stageA] 리포트 기록: ${out.toAbsolutePath()}")

        assertTrue(outcome.report.contains("통과 ${outcome.survivors.size} / ${outcome.uniqueBehaviours}"), "분모와 함께 보고")
        assertTrue(outcome.eliminations.keys.containsAll(listOf("G1", "G2", "G3", "G5", "G6", "G7")), "게이트별 탈락 집계")
        assertTrue(outcome.uniqueBehaviours <= outcome.nominalConfigs)
    }

    /** yearly fixture 8파일의 내용 해시 — 리포트 재현 메타데이터. */
    private fun fixtureDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (market in YearlyFixtures.MARKETS) {
            digest.update(javaClass.getResourceAsStream("/backtest/yearly/$market.json")!!.use { it.readBytes() })
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 결정적 합성 시계열 — 벤치마크 전용. 성과를 해석하지 않는다(fixture 가 아니다). */
    private fun syntheticCandles(market: String, bars: Int, seed: Int): List<Candle> {
        var price = 1000.0
        val out = ArrayList<Candle>(bars)
        for (i in 0 until bars) {
            val r = RandomEntryStrategy.unitInterval(seed, market, "bar-$i") - 0.5
            val open = price
            price *= (1 + r * 0.08)
            val high = maxOf(open, price) * 1.01
            val low = minOf(open, price) * 0.99
            out += Candle(
                market = market,
                candleDateTimeKst = "2025-01-01T%05d".format(i),
                openingPrice = open, highPrice = high, lowPrice = low, tradePrice = price,
                candleAccTradeVolume = 1000.0 + i, candleAccTradePrice = price * 1000,
            )
        }
        return out.reversed() // 로더 규약과 같은 최신순
    }
}
