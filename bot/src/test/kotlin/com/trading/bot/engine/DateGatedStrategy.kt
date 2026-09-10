package com.trading.bot.engine

import com.trading.common.config.TradingProperties
import com.trading.common.domain.Candle
import com.trading.common.strategy.TradingStrategy
import java.time.LocalDate

/**
 * 리서치 전용 데코레이터 — 안의 전략이 사겠다고 할 때 그 거래일이 [allow] 를 통과해야만 산다. 청산·이름·minCandles 는 그대로 위임한다
 * (엔진·하네스가 이름으로 전략을 찾으므로 이름이 같아야 한다 — `StrategySearch.Options.strategyFor` 와 같은 규약).
 *
 * 거래일은 window 맨 앞 봉의 kst 날짜다 — [LiveSemanticsArm] 의 `partialWindow()` 가 그날 부분봉을 `${day}T09:00:00` 로 맨 앞에 둔다.
 * [blockedDays] 는 안의 전략이 사려 했는데 게이트가 막은 (market, day) 집합 — 같은 날 여러 봉에서 막혀도 하루로 센다(차단율 진단, 사전고정 8c).
 */
class DateGatedStrategy(
    private val inner: TradingStrategy,
    private val allow: (LocalDate) -> Boolean,
) : TradingStrategy {
    override val name: String get() = inner.name
    override val minCandles: Int get() = inner.minCandles

    val blockedDays = LinkedHashSet<Pair<String, String>>()
    val allowedDays = LinkedHashSet<Pair<String, String>>()

    override suspend fun shouldBuy(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean {
        if (!inner.shouldBuy(candles, currentPrice, config)) return false
        val head = candles.first()
        val day = head.candleDateTimeKst.substring(0, 10)
        val ok = allow(LocalDate.parse(day))
        (if (ok) allowedDays else blockedDays) += head.market to day
        return ok
    }

    override suspend fun shouldSell(candles: List<Candle>, currentPrice: Double, config: TradingProperties): Boolean =
        inner.shouldSell(candles, currentPrice, config)
}
