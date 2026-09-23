package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.domain.Ohlc
import com.trading.common.strategy.CombinedStrategy
import com.trading.common.strategy.Indicators
import com.trading.common.strategy.TradingStrategy

/**
 * 리서치 전용 `combined` 변형 — MA5>MA20·RSI14∈[30,70] 을 지표별로 어느 window 로 계산할지 고른다(plan `2026-09-23-partial-bar-signals`).
 * 돌파선은 항상 부분봉 포함 window(당일 시가)다. 이름·minCandles·청산은 `combined` 그대로 — 하네스가 이름으로 전략을 찾는다.
 *
 * - [Mode.PARTIAL]: 현행(라이브·계기 공통) — window 맨 앞의 진행 중 당일 부분봉 포함.
 * - [Mode.COMPLETED]: `window.drop(1)` — 전날까지 완결 봉만. (마켓, 거래일) 안에서 상수라 [observed] 에 남겨 사전계산 게이트와 대조한다.
 * - [Mode.FLIPPED]: 판정 null — 부분봉 조건을 [flip](지표, 마켓, 거래일) 로 뒤집는다(XOR, 이동한 전이일 마스크).
 * - [Mode.SUPPLIED]: 진단 null — 조건 값을 [flip] 이 그대로 준다(이동한 완결 조건 값, 수준 이동).
 *
 * 거래일 = window 맨 앞 봉의 kst 날짜([LiveSemanticsArm] 부분봉 `${day}T09:00:00`, [DateGatedStrategy] 와 같은 규약).
 */
class CompletedBarCombined(
    val ma: Mode,
    val rsi: Mode,
    private val flip: (Indicator, String, String) -> Boolean = { _, _, _ -> false },
) : TradingStrategy {
    enum class Mode { PARTIAL, COMPLETED, FLIPPED, SUPPLIED }
    enum class Indicator { MA, RSI }

    private val inner = CombinedStrategy()
    override val name: String get() = inner.name
    override val minCandles: Int get() = inner.minCandles

    /** COMPLETED 지표의 (마켓, 거래일) 값. 같은 날 다른 값이 나오면 [inconsistent] 를 센다 — 완결 봉 조건이 날짜 단위 게이트라는 전제의 검증. */
    val observed = HashMap<Triple<String, String, Indicator>, Boolean>()
    var inconsistent = 0
        private set

    /** (마켓, 거래일, 지표) 첫 평가 시 (부분 조건, 완결 조건) — 불일치율 진단(보고 전용, 사전고정 9). */
    val firstEvaluation = LinkedHashMap<Triple<String, String, Indicator>, Pair<Boolean, Boolean>>()

    override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean {
        if (candles.size < 21) return false
        val target = Indicators.calculateTargetPrice(candles, config.kValue)
        if (target <= 0 || currentPrice <= target) return false
        val head = candles.first()
        val market = head.market
        val day = head.candleDateTimeKst.substring(0, 10)
        return decide(Indicator.MA, ma, candles, market, day) && decide(Indicator.RSI, rsi, candles, market, day)
    }

    private fun decide(indicator: Indicator, mode: Mode, candles: List<Candle>, market: String, day: String): Boolean {
        val key = Triple(market, day, indicator)
        return when (mode) {
            Mode.PARTIAL -> condition(indicator, candles)
            Mode.FLIPPED -> condition(indicator, candles) xor flip(indicator, market, day)
            Mode.SUPPLIED -> flip(indicator, market, day)
            Mode.COMPLETED -> {
                val completed = condition(indicator, candles.drop(1))
                val prior = observed.putIfAbsent(key, completed)
                if (prior != null && prior != completed) inconsistent++
                if (key !in firstEvaluation) firstEvaluation[key] = condition(indicator, candles) to completed
                completed
            }
        }
    }

    override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean =
        inner.shouldSell(candles, currentPrice, config)

    companion object {
        /** `combined` 의 두 필터 조건 — 판정·사전계산이 같은 식을 쓴다. */
        fun condition(indicator: Indicator, newestFirst: List<Ohlc>): Boolean = when (indicator) {
            Indicator.MA -> Indicators.isMaUptrend(newestFirst, 5, 20)
            Indicator.RSI -> Indicators.calculateRsi(newestFirst, 14) in 30.0..70.0
        }
    }
}

/**
 * 완결 봉 조건의 사전계산과 null 마스크(plan `# Acceptance` 3). 완결 조건 C(m,d) 는 일봉 인덱스 d−(warmup−1)..d−1 만의 함수라
 * (마켓, 거래일) 게이트다. 평가 가능 거래일 = 인덱스 warmup..끝(10창 fixture 는 50..199 = 150일).
 */
object CompletedBarGate {
    /** null 이동 일정 k_s = 17 + 3(s−1), s=1..20 → 17..74. 모두 ≤ 75 라 150일 원형에서 거리 = k(≥ 15, 사전고정 7(g)). */
    val SHIFTS: List<Int> = (0 until 20).map { 17 + 3 * it }

    class MarketGate(val days: List<String>, val values: Map<CompletedBarCombined.Indicator, BooleanArray>) {
        init { require(days.distinct().size == days.size) { "평가 가능 거래일 중복 — 날짜 키 조회가 갈린다" } }
        private val indexOf = days.withIndex().associate { it.value to it.index }
        fun index(day: String): Int? = indexOf[day]
    }

    fun build(dailyChronological: List<Candle>, warmup: Int = BacktestEngine.MIN_CANDLES): MarketGate {
        val range = warmup until dailyChronological.size
        val days = range.map { dailyChronological[it].candleDateTimeKst.substring(0, 10) }
        val values = CompletedBarCombined.Indicator.values().associateWith { ind ->
            BooleanArray(days.size) { i ->
                val dayIndex = warmup + i
                CompletedBarCombined.condition(ind, dailyChronological.subList(dayIndex - (warmup - 1), dayIndex).asReversed())
            }
        }
        return MarketGate(days, values)
    }

    /** D[i] = [C(i) ≠ C(i+1)] — 그날 종가까지 넣으면 완결 조건이 뒤집히는 날. 마지막 평가일은 다음 평가일이 없어 전이를 정의하지 않고 false. */
    fun transitions(values: BooleanArray): BooleanArray =
        BooleanArray(values.size) { i -> i + 1 < values.size && values[i] != values[i + 1] }

    /** out[i] = mask[(i + k) mod n] — 같은 마켓의 거래일 목록 안 순환 이동. */
    fun shifted(mask: BooleanArray, k: Int): BooleanArray {
        val n = mask.size
        if (n == 0) return mask
        return BooleanArray(n) { i -> mask[(i + k).mod(n)] }
    }
}
