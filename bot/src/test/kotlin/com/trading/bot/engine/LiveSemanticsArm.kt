package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.ExitGates
import com.trading.common.strategy.TradingStrategy
import kotlin.math.max
import kotlin.math.min

/**
 * **진입까지** 라이브 의미론으로 도는 시뮬레이터. [BacktestEngine] 과 다른 것은 진입 한 축뿐이다.
 *
 * | | 백테(`BacktestEngine`) | 라이브(`TradingEngine.runSwing`) | 이 팔 |
 * |---|---|---|---|
 * | 신호 시점 | 일봉 종가 | 10초마다(당일 부분봉 포함 window) | 240분봉마다(직전 봉까지 누적한 부분봉) |
 * | 체결가 | **다음 날 09:00 시가** | 돌파하는 그 순간의 현재가 | `max(target, 봉 시가)` |
 * | 하루 1회 | 구조가 보장 | `boughtToday`(09:00 에 해제) | `boughtToday`(09:00 에 해제) |
 *
 * 이 차이가 왜 중요한가: 백테는 **종가가 돌파선 위에서 마감한 날만** 골라 그 다음 날 09:00 에 산다.
 * 라이브는 장중에 넘는 순간 사고 종가가 되밀려도 이미 보유 중이다 — **거래 모집단 자체가 다르다**.
 *
 * **look-ahead 없음**: 전략에 넘기는 당일 부분봉은 **직전 240분봉까지만** 누적한다. 그 봉의 고·저·종가를
 * 쓰면 체결 시점 이후 정보가 MA·RSI 에 들어간다. 당일 시가는 봉 시작 시점에 이미 알려져 있어
 * `calculateTargetPrice` 입력으로 안전하다. 그 결과 이 팔은 라이브보다 **약간 보수적**이다.
 *
 * 청산 판정은 [IntrabarExitModel] 이 소유한다 — 백테·replay 와 같은 게이트식이라야 팔끼리 비교가 성립한다.
 */
internal object LiveSemanticsArm {

    /** 한 거래의 결과. 인덱스가 아니라 **거래일**로 식별한다 — 팔마다 봉 격자가 달라 인덱스는 비교 단위가 못 된다. */
    data class Trade(
        val market: String,
        val entryDate: String,
        val exitDate: String,
        val entryPrice: Double,
        val exitPrice: Double,
        val netPnlPct: Double,
        val reason: String,
    )

    /**
     * @param dailyChronological 시간순 일봉(워밍업·완결 봉 공급용). 앞 [warmup] 개는 신호에 쓰이고 거래는 그 뒤부터.
     * @param intradayChronological 시간순 240분봉. `candle_date_time_utc` 필수.
     */
    suspend fun run(
        market: String,
        strategy: TradingStrategy,
        dailyChronological: List<Candle>,
        intradayChronological: List<Candle>,
        config: BacktestConfig,
        props: TradingProperties,
        warmup: Int = BacktestEngine.MIN_CANDLES,
    ): List<Trade> {
        val signalProps = props.copy(kValue = config.kValue)
        val feePct = config.feeRate * 2 * 100
        val holdLimit = ExitGates.effectiveMaxHoldDays(config.maxHoldDays)
        // 거래일 = UTC 날짜(= KST 09:00 경계). 일봉 fixture 의 kst 날짜와 같은 라벨이 된다.
        val byDay = intradayChronological.groupBy { it.candleDateTimeUtc.substring(0, 10) }
        val dailyByDate = dailyChronological.associateBy { it.candleDateTimeKst.substring(0, 10) }
        val days = dailyChronological.map { it.candleDateTimeKst.substring(0, 10) }

        val trades = ArrayList<Trade>()
        var position = false
        var entryPrice = 0.0
        var entryDayIndex = -1
        var entryDate = ""
        var peak = 0.0

        for (dayIndex in warmup until days.size) {
            val day = days[dayIndex]
            val bars = byDay[day] ?: continue
            // 완결 일봉은 어제까지. 오늘은 부분봉으로 별도 공급한다.
            val completed = (0 until dayIndex).mapNotNull { dailyByDate[days[it]] }
            if (completed.size < warmup) continue
            var boughtToday = false

            val dayOpen = bars.first().openingPrice
            var pHigh = dayOpen
            var pLow = dayOpen
            var pClose = dayOpen
            var pVolume = 0.0

            for (bar in bars) {
                // 이 봉 **시작 시점**의 부분봉 — 직전 봉까지의 누적이다(look-ahead 방지).
                val partial = Candle(
                    market = market,
                    candleDateTimeKst = "${day}T09:00:00",
                    openingPrice = dayOpen, highPrice = pHigh, lowPrice = pLow,
                    tradePrice = pClose, candleAccTradeVolume = pVolume,
                )
                val window = (completed + partial).takeLast(warmup).reversed()

                if (position) {
                    val atHoldLimit = dayIndex - entryDayIndex >= holdLimit
                    val armPeak = peak
                    peak = IntrabarExitModel.updatedPeak(peak, bar, atHoldLimit)
                    val decision = IntrabarExitModel.evaluate(bar, entryPrice, armPeak, atHoldLimit, config, chartExitSignal = false)
                    if (decision != null) {
                        trades += Trade(
                            market, entryDate, day, entryPrice, decision.sellPrice,
                            (decision.sellPrice - entryPrice) / entryPrice * 100.0 - feePct, decision.reason,
                        )
                        position = false
                    }
                }

                // 라이브는 청산과 같은 tick 에서 곧바로 매수를 평가한다(09:00 리셋 직후 재매수가 그래서 가능하다).
                if (!position && !boughtToday) {
                    val target = com.trading.common.strategy.Indicators.calculateTargetPrice(window, config.kValue)
                    // 돌파를 이 봉 안에서 실제로 했는가. 라이브는 그 순간의 현재가에 사므로 하한이 target 이다.
                    if (target > 0 && bar.highPrice > target) {
                        val fill = max(target, bar.openingPrice)
                        if (strategy.shouldBuy(window, fill, signalProps)) {
                            position = true
                            boughtToday = true
                            entryPrice = fill
                            entryDayIndex = dayIndex
                            entryDate = day
                            peak = fill
                            // 진입 봉의 intrabar 게이트도 받는다 — 빼면 진입 당일만 손절·익절 보호가 없어 편향된다.
                            val armPeak = peak
                            peak = IntrabarExitModel.updatedPeak(peak, bar, false)
                            IntrabarExitModel.evaluate(bar, entryPrice, armPeak, false, config, chartExitSignal = false)?.let { d ->
                                trades += Trade(
                                    market, entryDate, day, entryPrice, d.sellPrice,
                                    (d.sellPrice - entryPrice) / entryPrice * 100.0 - feePct, d.reason,
                                )
                                position = false
                            }
                        }
                    }
                }

                pHigh = max(pHigh, bar.highPrice)
                pLow = min(pLow, bar.lowPrice)
                pClose = bar.tradePrice
                pVolume += bar.candleAccTradeVolume
            }
        }
        // 구간 끝 강제 청산 — 엔진의 closeOpenPosition 과 같은 규약.
        if (position) {
            val last = intradayChronological.last()
            trades += Trade(
                market, entryDate, last.candleDateTimeUtc.substring(0, 10), entryPrice, last.tradePrice,
                (last.tradePrice - entryPrice) / entryPrice * 100.0 - feePct, "END",
            )
        }
        return trades
    }
}
