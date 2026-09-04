package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy

/**
 * 사전고정 그리드를 fixture 위에서 실행해 좌표별 지표를 내는 스윕 러너.
 *
 * 지표 계산은 [SwingMetrics] 를 쓴다 — [YearlyStrategyComparison] 과 같은 함수라야 두 리포트를 나란히 놓을 수 있다.
 * 판정은 [StrategySearchGates], 좌표계는 [StrategySearchGrid] 가 소유하고 이 클래스는 **실행과 집계만** 한다.
 *
 * 5만 좌표 × 8마켓의 거래 목록을 전부 들고 있으면 힙이 감당하지 못하므로, 기본적으로 **집계와 64bit 거래 지문만** 남기고
 * 거래 목록은 통과 후보처럼 필요한 소수에서만 [Options.keepTrades] 로 보관한다.
 */
internal class StrategySearch(
    private val props: TradingProperties = TradingProperties(),
    // 기존 9종 + Stage D 신규 10종. 좌표가 전략 이름으로 조회하므로 양쪽 다 들고 있어야 한다.
    private val strategies: List<TradingStrategy> = YearlyStrategyComparison.ALL_STRATEGIES + StrategySearchGrid.STAGE_D,
) {

    /** 입력 구간(시간순 인덱스). 거래 구간은 앞 [BacktestEngine.MIN_CANDLES] 봉을 워밍업으로 뗀 나머지다. */
    data class Segment(val label: String, val inputRange: IntRange)

    data class Options(
        val feeRate: Double = SweepPoint.DEFAULT_FEE_RATE,
        val reentryMode: ReentryMode = ReentryMode.LIVE_SAME_BAR,
        /** 마켓 이름을 받아 그 마켓용 전략을 돌려준다(null 대조군 주입 지점). */
        val strategyFor: ((String) -> TradingStrategy)? = null,
    )

    data class Metrics(
        val returnByMarket: Map<String, Double>,
        val mddByMarket: Map<String, Double>,
        val trades: Int,
        val zeroTradeMarkets: Int,
        val worstMdd: Double,
        val exposure: Double,
        val wins: Int,
        val sumWinPct: Double,
        val sumLossPct: Double,
        val priceGateTrades: Int,
        /** 거래 행동의 64bit 지문 — 서로 다른 좌표가 같은 거래를 내면 하나로 접는다(다중비교 분모). */
        val fingerprint: Long,
    ) {
        val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades * 100.0

        /**
         * trade 분포에서 실측한 손익분기 승률(%). 이론식 `SL/(TP+SL)` 은 손익이 두 값뿐이고 비용이 0 일 때만
         * 성립하는데, 실제로는 수수료가 빠지고 TRAILING/TIME/END 청산가가 섞인다.
         */
        val breakEvenWinRate: Double
            get() {
                val losses = trades - wins
                if (wins == 0 || losses == 0) return Double.NaN
                val avgWin = sumWinPct / wins
                val avgLoss = -sumLossPct / losses
                return avgLoss / (avgWin + avgLoss) * 100.0
            }

        /** 가격 게이트(TP/SL)로만 끝난 거래 비율 — 위 손익분기식이 얼마나 적용되는지 독자가 판단할 근거. */
        val priceGateShare: Double get() = if (trades == 0) 0.0 else priceGateTrades.toDouble() / trades
    }

    /** 좌표별 지표. 마켓 루프를 바깥에 둬 마켓당 신호 캐시 1개만 살아 있게 한다(메모리·키 충돌 양쪽 이유). */
    suspend fun measure(
        fixtures: Map<String, List<Candle>>,
        segment: Segment,
        points: List<SweepPoint>,
        options: Options = Options(),
    ): Map<SweepPoint, Metrics> = measureResolved(fixtures, segment, points, options) { it.toConfig(options.reentryMode, options.feeRate) }

    /**
     * 좌표계로 표현할 수 없는 config(Stage B 의 ATR·부분익절 등)를 직접 넘기는 경로. 좌표는 식별자로만 쓰인다.
     */
    suspend fun measureWithConfigs(
        fixtures: Map<String, List<Candle>>,
        segment: Segment,
        configs: Map<SweepPoint, BacktestConfig>,
        strategyFor: ((String) -> TradingStrategy)? = null,
    ): Map<SweepPoint, Metrics> =
        // feeRate·reentryMode 는 넘어온 config 가 이미 들고 있다 — Options 로 또 받으면 그쪽이 조용히 무시돼
        // "수수료를 올렸는데 반영 안 된 표" 가 나온다.
        measureResolved(fixtures, segment, configs.keys.toList(), Options(strategyFor = strategyFor)) { configs.getValue(it) }

    private suspend fun measureResolved(
        fixtures: Map<String, List<Candle>>,
        segment: Segment,
        points: List<SweepPoint>,
        options: Options,
        configFor: (SweepPoint) -> BacktestConfig,
    ): Map<SweepPoint, Metrics> {
        val acc = points.associateWith { Accumulator() }

        for ((market, newestFirst) in fixtures) {
            val chronological = newestFirst.reversed()
            require(segment.inputRange.last < chronological.size) {
                "$market: ${chronological.size}봉인데 구간 ${segment.inputRange} 을 요구했다"
            }
            val input = chronological.subList(segment.inputRange.first, segment.inputRange.last + 1)
            val cache = SignalCache()
            val engines = points.map { it.strategy }.distinct().associateWith { name ->
                val base = options.strategyFor?.invoke(market) ?: strategies.first { it.name == name }
                // 주입 전략은 이름까지 맞아야 한다 — 엔진이 `strategies.find { it.name == name }` 로 찾으므로
                // 어긋나면 run() 이 null 을 돌려주고 측정이 "결과 없음" 으로 죽는다.
                require(base.name == name) { "주입 전략 '${base.name}' 이 좌표 전략 '$name' 과 다르다" }
                BacktestEngine(listOf(cache.decorate(base, market)), props)
            }
            for (point in points) {
                val measurement = SwingMetrics.measure(
                    engine = engines.getValue(point.strategy),
                    strategyName = point.strategy,
                    market = market,
                    input = input,
                    warmup = BacktestEngine.MIN_CANDLES,
                    config = configFor(point),
                )
                acc.getValue(point).add(market, measurement)
            }
        }
        return acc.mapValues { it.value.build() }
    }

    /**
     * 전략의 **원시 신호 발생률** — flat 여부와 무관하게 전 봉에서 평가한다. null 대조군의 진입 확률로 쓴다.
     * 실현 진입 수는 exit 설정에 따라 달라지므로 이 매칭은 근사이며 리포트에 그렇게 적는다.
     */
    suspend fun signalRate(
        strategy: TradingStrategy,
        fixtures: Map<String, List<Candle>>,
        segment: Segment,
        kValue: Double = 0.5,
    ): Double {
        val signalProps = props.copy(kValue = kValue)
        var hits = 0
        var total = 0
        for ((_, newestFirst) in fixtures) {
            val input = newestFirst.reversed().subList(segment.inputRange.first, segment.inputRange.last + 1)
            for (i in BacktestEngine.MIN_CANDLES until input.size) {
                val window = input.subList(i - (BacktestEngine.MIN_CANDLES - 1), i + 1).reversed()
                if (strategy.shouldBuy(window, window.first().tradePrice, signalProps)) hits++
                total++
            }
        }
        return if (total == 0) 0.0 else hits.toDouble() / total
    }

    private class Accumulator {
        private val returns = LinkedHashMap<String, Double>()
        private val mdds = LinkedHashMap<String, Double>()
        private val exposures = ArrayList<Double>()
        private var trades = 0
        private var zeroTradeMarkets = 0
        private var wins = 0
        private var sumWin = 0.0
        private var sumLoss = 0.0
        private var priceGate = 0
        private var hash = FNV_OFFSET

        fun add(market: String, m: SwingMetrics.Measurement) {
            returns[market] = m.netReturnPct
            mdds[market] = m.mddPct
            exposures += m.exposure
            trades += m.trades.size
            if (m.trades.isEmpty()) zeroTradeMarkets++
            for (t in m.trades) {
                if (t.pnlPercent > 0) { wins++; sumWin += t.pnlPercent } else sumLoss += t.pnlPercent
                if (t.reason == "TAKE_PROFIT" || t.reason == "STOP_LOSS") priceGate++
                mix(market, t)
            }
        }

        fun build() = Metrics(
            returnByMarket = returns.toMap(),
            mddByMarket = mdds.toMap(),
            trades = trades,
            zeroTradeMarkets = zeroTradeMarkets,
            worstMdd = mdds.values.maxOrNull() ?: 0.0,
            exposure = if (exposures.isEmpty()) 0.0 else exposures.average(),
            wins = wins,
            sumWinPct = sumWin,
            sumLossPct = sumLoss,
            priceGateTrades = priceGate,
            fingerprint = hash,
        )

        private fun mix(market: String, t: BacktestTrade) {
            hash = mixTrade(hash, market, t)
        }
    }

    companion object {
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        /**
         * 거래 **행동**의 64bit 지문 — 서로 다른 좌표가 같은 거래를 내면 하나로 접어 다중비교 분모(고유 행동 수)를 만든다.
         * 가격까지 섞는 쪽이 보수적이다(덜 접히므로 분모가 커진다).
         */
        fun fingerprintOf(tradesByMarket: Map<String, List<BacktestTrade>>): Long {
            var h = FNV_OFFSET
            for (market in tradesByMarket.keys) for (t in tradesByMarket.getValue(market)) h = mixTrade(h, market, t)
            return h
        }

        private fun mixTrade(hash: Long, market: String, t: BacktestTrade): Long {
            var h = hash
            for (v in longArrayOf(
                market.hashCode().toLong(), t.buyIndex.toLong(), t.sellIndex.toLong(), t.reason.hashCode().toLong(),
                Math.round(t.buyPrice * 1e6), Math.round(t.sellPrice * 1e6), Math.round(t.pnlPercent * 1e6),
            )) h = (h xor v) * FNV_PRIME
            return h
        }

        /** yearly fixture(365봉)의 사전고정 창 — [YearlyStrategyComparison.Window] 와 같은 경계를 쓴다. */
        val SELECT = Segment("선택", 0..242)
        val VALIDATE = Segment("검증", 193..364)

        /** 국면 fixture(200봉) — 거래봉 150. */
        val REGIME = Segment("국면", 0..199)
    }
}
