package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy

/**
 * 해상도 사다리(240 → 15 → 5분봉) 측정의 공통 로딩 — [ExitResolutionLadderTest] 의 창·frame 규약을 그대로 쓴다.
 * 10창 = [BacktestFixtures.EXPANSION_2020_2023] + [BacktestFixtures.TIME_INDEPENDENT], 240분봉은 추적 fixture, 그 외는 [IntradayCache].
 * 부분 캐시로는 판정하지 않는다 — 캐시가 하나라도 없으면 실패(skip 아님).
 */
internal object LadderWindows {

    class Window(val label: String, val dir: String, val daily: Map<String, List<Candle>>, val intraday: Map<String, List<Candle>>) {
        /** 워밍업 이후 일봉 날짜(마켓 합집합, 정렬). frame 의 키 공간. */
        val tradingDays: List<String> = daily.values.flatMap { newestFirst ->
            val ch = newestFirst.reversed()
            (BacktestEngine.MIN_CANDLES until ch.size).map { ch[it].candleDateTimeKst.substring(0, 10) }
        }.toSortedSet().toList()

        /** (마켓, 거래일) → 그날 첫 존재 봉 시가. 봉이 없는 마켓-일은 키가 없다. */
        val dayOpen: Map<Pair<String, String>, Double>
        val zeroBarMarketDays: Int
        val maxBarsPerDay: Int
        init {
            val opens = HashMap<Pair<String, String>, Double>(); var zero = 0; var maxBars = 0
            for ((market, newestFirst) in daily) {
                val ch = newestFirst.reversed()
                val byDay = intraday.getValue(market).sortedBy { it.candleDateTimeUtc }.groupBy { it.candleDateTimeUtc.substring(0, 10) }
                for (i in BacktestEngine.MIN_CANDLES until ch.size) {
                    val d = ch[i].candleDateTimeKst.substring(0, 10)
                    val bars = byDay[d]
                    if (bars == null) { zero++; continue }
                    maxBars = maxOf(maxBars, bars.size)
                    opens[market to d] = bars.first().openingPrice
                }
            }
            dayOpen = opens; zeroBarMarketDays = zero; maxBarsPerDay = maxBars
        }

        fun key(date: String) = "$dir/$date"
    }

    class Level(val unit: Int, val windows: List<Window>)

    fun regimes(): List<BacktestFixtures.Regime> = BacktestFixtures.EXPANSION_2020_2023 + BacktestFixtures.TIME_INDEPENDENT

    fun load(units: List<Int>, regimes: List<BacktestFixtures.Regime> = regimes()): List<Level> {
        require(units.first() == 240) { "frame 은 240분봉 기준이라 목록은 240 으로 시작해야 한다" }
        for (unit in units.filter { it != 240 }) for (r in regimes) {
            require(IntradayCache.available(unit, r.dir, BacktestFixtures.markets(r))) {
                "${unit}분봉 캐시 부재: ${r.dir} — 부분 캐시로는 판정하지 않는다(BACKTEST_CACHE_DIR 확인)"
            }
        }
        val levels = units.map { unit ->
            Level(unit, regimes.map { r ->
                val daily = BacktestFixtures.loadAll(r)
                val intraday = if (unit == 240) IntradayFixtures.loadAll(r.dir, daily.keys) else IntradayCache.loadAll(unit, r.dir, daily.keys)
                Window(r.label, r.dir, daily, intraday)
            })
        }
        // 데이터 무결성 — [ExitResolutionLadderTest] 의 7d·7e 와 같은 단정. 캐시 파일이 있어도 절단·중복이면 rung 이 조용히 다른 계기가 된다.
        val base240 = levels.first()
        for (w in base240.windows) {
            require(w.tradingDays.size == EXPECTED_DAYS_PER_WINDOW) { "${w.label}: 240분 frame 일수 ${w.tradingDays.size} ≠ $EXPECTED_DAYS_PER_WINDOW" }
            require(w.zeroBarMarketDays == 0) { "${w.label}: 240분 결측 마켓-일 ${w.zeroBarMarketDays}" }
        }
        for (level in levels) for (w in level.windows) {
            require(w.maxBarsPerDay <= 24 * 60 / level.unit) { "${level.unit}m ${w.label}: 봉/일 ${w.maxBarsPerDay} > ${24 * 60 / level.unit}" }
        }
        for (level in levels.drop(1)) for ((w240, w) in base240.windows.zip(level.windows)) {
            for ((k, o) in w.dayOpen) {
                val o240 = w240.dayOpen[k] ?: continue
                require(kotlin.math.abs(o - o240) <= 1e-9 * maxOf(1.0, o240)) { "${level.unit}m ${w.label} $k: 첫 봉 시가 $o ≠ 240분 $o240" }
            }
        }
        return levels
    }

    const val EXPECTED_DAYS_PER_WINDOW = 150

    /** 240분봉 공통 frame — 모든 rung 의 기여가 이 위에 정렬된다(진입일은 해상도와 무관한 거래일 라벨). */
    fun frame(base240: Level): PairedMaxTBootstrap.Frame = PairedMaxTBootstrap.Frame(base240.windows.map { w -> w.tradingDays.map { w.key(it) } })

    /** 한 rung 의 전 창 실행 — 창 dir → 전 마켓 병합 Trade. */
    suspend fun run(
        level: Level,
        strategy: TradingStrategy,
        config: BacktestConfig,
        props: TradingProperties,
        pessimisticTrailing: Boolean = false,
        entryBarStopOnClose: Boolean = false,
        keepWinnersUntilDays: Int = 0,
    ): Map<String, List<LiveSemanticsArm.Trade>> {
        val byWindow = LinkedHashMap<String, List<LiveSemanticsArm.Trade>>()
        for (w in level.windows) {
            val out = ArrayList<LiveSemanticsArm.Trade>()
            for ((market, newestFirst) in w.daily) {
                out += LiveSemanticsArm.run(
                    market, strategy, newestFirst.reversed(), w.intraday.getValue(market).reversed(), config, props,
                    entryBarStopOnClose = entryBarStopOnClose, keepWinnersUntilDays = keepWinnersUntilDays, pessimisticTrailing = pessimisticTrailing,
                )
            }
            byWindow[w.dir] = out
        }
        return byWindow
    }

    /** 진입일 단위 기여(셀 − 기준), frame 정렬. [filter] 로 창 부분집합(예: 단순보유 음수 창)만. */
    fun contributions(
        frame: PairedMaxTBootstrap.Frame,
        level: Level,
        cell: Map<String, List<LiveSemanticsArm.Trade>>,
        base: Map<String, List<LiveSemanticsArm.Trade>>,
        filter: (Window) -> Boolean = { true },
    ): DoubleArray {
        val byKey = HashMap<String, Double>()
        for (w in level.windows.filter(filter)) {
            for (t in cell.getValue(w.dir)) byKey.merge(w.key(t.entryDate), t.netPnlPct, Double::plus)
            for (t in base.getValue(w.dir)) byKey.merge(w.key(t.entryDate), -t.netPnlPct, Double::plus)
        }
        return frame.align(byKey)
    }

    data class EntryKey(val market: String, val entryDate: String, val entryPrice: Double)
    fun keyOf(t: LiveSemanticsArm.Trade) = EntryKey(t.market, t.entryDate, t.entryPrice)
}
