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
 * | 신호 시점 | 일봉 종가 | 10초마다(당일 부분봉 포함 window) | 일중봉마다(240·15·5분 — 직전 봉까지 누적한 부분봉) |
 * | 체결가 | **다음 날 09:00 시가** | 돌파하는 그 순간의 현재가 | `max(target, 봉 시가)` — `combined` 는 현재가 ≤ 돌파선을 거부하므로 실제로는 **돌파선 위에서 여는 첫 봉의 시가**(돌파 봉 자체에서는 진입하지 않는다) |
 * | 하루 1회 | 구조가 보장 | `boughtToday`(09:00 에 해제) | `boughtToday`(09:00 에 해제) |
 *
 * 이 차이가 왜 중요한가: 백테는 **종가가 돌파선 위에서 마감한 날만** 골라 그 다음 날 09:00 에 산다.
 * 라이브는 장중에 넘는 순간 사고 종가가 되밀려도 이미 보유 중이다 — **거래 모집단 자체가 다르다**.
 *
 * **look-ahead 없음**: 전략에 넘기는 당일 부분봉은 **직전 일중봉까지만** 누적한다. 그 봉의 고·저·종가를
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
        /** 진입한 바로 그 일중봉에서 청산됐는가 — 진입 봉 손절(체결이 봉 시가라 저가는 체결 이후, `entryBarStopOnClose` 참조)의 노출을 세는 데 쓴다. */
        val exitOnEntryBar: Boolean = false,
        /**
         * 청산 봉의 시가·저가(END 는 NaN). 임계선 체결이 시가 체결·봉 저가와 얼마나 다른지(무슬리피지 편향 크기)를 재는 진단용.
         * `entryBarStopOnClose` 로 판정 기준을 바꿔도 여기엔 **원본 봉의 저가**가 남는다 — 판정과 진단을 섞지 않기 위해서다.
         */
        val exitBarOpen: Double = Double.NaN,
        val exitBarLow: Double = Double.NaN,
        /** `keepWinnersUntilDays` 로 보유상한 봉을 **넘겨서 유지된** 포지션인가 — 사유·날짜 재구성(한도봉 갭 TP/SL 과 혼동)이 아니라 플래그로 센다. */
        val keptPastLimit: Boolean = false,
    )

    /**
     * @param dailyChronological 시간순 일봉(워밍업·완결 봉 공급용). 앞 [warmup] 개는 신호에 쓰이고 거래는 그 뒤부터.
     * @param intradayChronological 시간순 일중봉(240·15·5분 — 단위는 호출 측이 정한다). `candle_date_time_utc` 필수.
     * @param entryBarStopOnClose 진입 봉의 손절만 봉 저가 대신 **종가**로 판정한다(발동 시 체결가는 손절선). 체결이 그 봉의 **시가**라(위 표)
     *   봉 저가는 체결 이후다 — 기본(false)의 진입 봉 손절은 유령이 아니라 실재하고, true 는 그것을 지우는 **한쪽** 처리다(브래킷이 아니라 손절 과소 쪽 상한).
     *   라이브는 돌파 tick(≈돌파선)에 사므로 이 팔의 체결가가 더 높고 같은 저가에서 손실률이 크게 잡힌다(좁은 손절에 불리한 항).
     *   진입 봉은 `armPeak = 체결가` 라 트레일링이 걸리지 않고 익절은 봉 고가(돌파 이후)로 보므로 다른 게이트는 영향받지 않는다.
     * @param keepWinnersUntilDays 0 이면 현행(보유상한 봉에서 무조건 청산). 양수면 **트레일링 손절선이 진입가 위로 잠긴**
     *   (`peak × (1 − trail/100) > 진입가`) 포지션만 보유상한을 넘겨 트레일링·익절·손절에 맡기고, 진입 후 이 일수에 도달하면 강제 청산한다.
     *   잠기지 않은 포지션(손실·미미한 이익)은 현행대로 09:00 시가에 청산 — "손실만 정리, 이익은 트레일링 유지" 정책.
     *   "잠김"(gross)은 `ExitGates.isTrailingStopTriggered` 의 `pnlPct > 0` 전제와 동치 — "트레일링이 발동할 수 있는 포지션만 유지" 다.
     * @param pessimisticTrailing 트레일링 판정의 고점에 **이 봉의 고가까지** 넣는다(봉 안에서 고점이 저점보다 먼저 왔다고 가정). 기본(false)은
     *   직전 봉까지의 고점이라 트레일링을 **덜** 걸어 넓은 익절·보유 연장에 유리하고, true 는 **더** 걸어 그 반대다 — 둘이 트레일링 해상도 편향의 브래킷.
     *   진입 봉에는 적용하지 않는다(체결 이전 고가를 고점으로 쓰면 유령 이익이 된다). 같은 봉에서 익절선을 지났으면(고가 ≥ 익절선) **익절이 먼저**다 —
     *   고가로 가는 길에 익절선을 지나므로 그 고가에서 파생한 트레일링선은 아직 없다(그 순서를 무시하면 불가능한 경로의 청산이 생긴다).
     */
    suspend fun run(
        market: String,
        strategy: TradingStrategy,
        dailyChronological: List<Candle>,
        intradayChronological: List<Candle>,
        config: BacktestConfig,
        props: TradingProperties,
        warmup: Int = BacktestEngine.MIN_CANDLES,
        entryBarStopOnClose: Boolean = false,
        keepWinnersUntilDays: Int = 0,
        pessimisticTrailing: Boolean = false,
    ): List<Trade> {
        require(warmup >= 1) { "warmup 은 1 이상 — 창은 부분봉 1개 + 완결 봉 warmup-1 개다" }
        val signalProps = props.copy(kValue = config.kValue)
        val feePct = config.feeRate * 2 * 100
        val holdLimit = ExitGates.effectiveMaxHoldDays(config.maxHoldDays)
        // 거래일 = UTC 날짜(= KST 09:00 경계). 일봉 fixture 의 kst 날짜와 같은 라벨이 된다.
        val byDay = intradayChronological.groupBy { it.candleDateTimeUtc.substring(0, 10) }
        val days = dailyChronological.map { it.candleDateTimeKst.substring(0, 10) }

        val trades = ArrayList<Trade>()
        var position = false
        var entryPrice = 0.0
        var entryDayIndex = -1
        var entryDate = ""
        var peak = 0.0
        var keptPast = false

        for (dayIndex in warmup until days.size) {
            val day = days[dayIndex]
            val bars = byDay[day] ?: continue
            // 완결 일봉은 어제까지(뷰 — 복사하지 않는다). 오늘은 부분봉으로 별도 공급한다. dayIndex ≥ warmup 이라 항상 충분하다.
            // 전제: 일봉 KST 날짜가 유일하다(픽스처 실측) — 중복이 있으면 날짜 키 조회와 위치 조회가 갈린다.
            val completed = dailyChronological.subList(0, dayIndex)
            // 창에 들어가는 완결 봉은 최근 warmup-1 개로 고정.
            val recentCompleted = completed.subList(completed.size - (warmup - 1), completed.size)
            var boughtToday = false

            val dayOpen = bars.first().openingPrice
            var pHigh = dayOpen
            var pLow = dayOpen
            var pClose = dayOpen
            var pVolume = 0.0

            // 이 봉 **시작 시점**의 부분봉 — 직전 봉까지의 누적이다(look-ahead 방지). 부분봉이 봉마다 바뀌므로
            // 창의 내용도 봉마다 다르다 — 캐시할 수 없고, 만드는 비용만 O(warmup) 으로 고정한다. newest-first.
            fun partialWindow(): List<Candle> {
                val window = ArrayList<Candle>(warmup)
                window += Candle(
                    market = market,
                    candleDateTimeKst = "${day}T09:00:00",
                    openingPrice = dayOpen, highPrice = pHigh, lowPrice = pLow,
                    tradePrice = pClose, candleAccTradeVolume = pVolume,
                )
                for (i in recentCompleted.indices.reversed()) window += recentCompleted[i]
                return window
            }
            // 돌파선 = 당일시가 + 전일 레인지 × k — 하루 안에서 상수라 봉마다 window 를 만들지 않는다(5분봉에서 20배 가까이 절약).
            val target = com.trading.common.strategy.Indicators.calculateTargetPrice(partialWindow(), config.kValue)

            for (bar in bars) {
                if (position) {
                    val daysHeld = dayIndex - entryDayIndex
                    // 잠긴 이익 = 트레일링 손절선이 진입가 위 → 어떤 청산도 이익이다. 그런 포지션만 보유상한을 넘긴다.
                    val lockedProfit = peak * (1 - config.trailingStopPct / 100.0) > entryPrice
                    val keepWinner = keepWinnersUntilDays > 0 && daysHeld < keepWinnersUntilDays && lockedProfit
                    if (keepWinner && daysHeld >= holdLimit) keptPast = true
                    val atHoldLimit = daysHeld >= holdLimit && !keepWinner
                    val peakBefore = peak
                    peak = IntrabarExitModel.updatedPeak(peak, bar, atHoldLimit)
                    // 기본은 직전 봉까지의 고점(낙관 — 이 봉 안의 신고점은 다음 봉에나 본다). 비관 브래킷은 이 봉 고가까지 넣은 고점으로 판정한다.
                    val armPeak = if (pessimisticTrailing) peak else peakBefore
                    // 비관 = 고가-우선 경로. 그 길에 익절선이 있으면 이 봉 고가에서 파생한 트레일링선보다 익절이 먼저다.
                    val takeProfitFirst = pessimisticTrailing && !atHoldLimit &&
                        ((bar.highPrice - entryPrice) / entryPrice) * 100.0 >= config.takeProfitPct
                    val decision = if (takeProfitFirst) ExitDecision("TAKE_PROFIT", entryPrice * (1 + config.takeProfitPct / 100.0))
                    else IntrabarExitModel.evaluate(bar, entryPrice, armPeak, atHoldLimit, config, chartExitSignal = false)
                    if (decision != null) {
                        trades += Trade(
                            market, entryDate, day, entryPrice, decision.sellPrice,
                            (decision.sellPrice - entryPrice) / entryPrice * 100.0 - feePct, decision.reason,
                            exitBarOpen = bar.openingPrice, exitBarLow = bar.lowPrice, keptPastLimit = keptPast,
                        )
                        position = false
                        keptPast = false
                    }
                }

                // 라이브는 청산과 같은 tick 에서 곧바로 매수를 평가한다(09:00 리셋 직후 재매수가 그래서 가능하다).
                if (!position && !boughtToday) {
                    // 돌파를 이 봉 안에서 실제로 했는가. 라이브는 그 순간의 현재가에 사므로 하한이 target 이다(`combined` 는 시가 ≤ target 이면 거부해 시가 체결만 남는다).
                    if (target > 0 && bar.highPrice > target) {
                        val fill = max(target, bar.openingPrice)
                        if (strategy.shouldBuy(partialWindow(), fill, signalProps)) {
                            position = true
                            boughtToday = true
                            entryPrice = fill
                            entryDayIndex = dayIndex
                            entryDate = day
                            peak = fill
                            keptPast = false
                            // 진입 봉의 intrabar 게이트도 받는다 — 빼면 진입 당일만 손절·익절 보호가 없어 편향된다.
                            val armPeak = peak
                            peak = IntrabarExitModel.updatedPeak(peak, bar, false)
                            val entryBar = if (entryBarStopOnClose) bar.copy(lowPrice = bar.tradePrice) else bar
                            IntrabarExitModel.evaluate(entryBar, entryPrice, armPeak, false, config, chartExitSignal = false)?.let { d ->
                                trades += Trade(
                                    market, entryDate, day, entryPrice, d.sellPrice,
                                    (d.sellPrice - entryPrice) / entryPrice * 100.0 - feePct, d.reason,
                                    exitOnEntryBar = true, exitBarOpen = bar.openingPrice, exitBarLow = bar.lowPrice,
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
                (last.tradePrice - entryPrice) / entryPrice * 100.0 - feePct, "END", keptPastLimit = keptPast,
            )
        }
        return trades
    }
}
