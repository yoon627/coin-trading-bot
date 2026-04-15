package com.trading.bot.strategy.scalp

import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker
import org.springframework.stereotype.Component

/**
 * 스프레드 스캘핑 전략.
 * 호가 스프레드가 넓고 매수벽이 두꺼울 때 진입하여 스프레드 차익을 노린다.
 */
@Component
class SpreadScalp : ScalpStrategy {

    override val name: String = "spread_scalp"

    companion object {
        private const val MIN_SPREAD_PERCENT = 0.05
        private const val MIN_BID_IMBALANCE = 0.6
        private const val EXIT_PROFIT_PERCENT = 0.03
        private const val EXIT_LOSS_PERCENT = 0.02
        private const val MAX_HOLD_DURATION_MS = 60_000L
    }

    override fun shouldEntry(
        ticker: NormalizedTicker,
        orderBook: NormalizedOrderBook,
        recentTickers: List<NormalizedTicker>,
    ): ScalpSignal {
        if (orderBook.spreadPercent < MIN_SPREAD_PERCENT) {
            return ScalpSignal(ScalpAction.HOLD, reason = "spread too narrow")
        }

        if (orderBook.imbalanceRatio < MIN_BID_IMBALANCE) {
            return ScalpSignal(ScalpAction.HOLD, reason = "bid depth insufficient")
        }

        val priceRising = isPriceRising(recentTickers)
        if (!priceRising) {
            return ScalpSignal(ScalpAction.HOLD, reason = "price not rising")
        }

        return ScalpSignal(
            action = ScalpAction.BUY,
            confidence = orderBook.imbalanceRatio,
            reason = "spread=${String.format("%.4f", orderBook.spreadPercent)}%, imbalance=${String.format("%.2f", orderBook.imbalanceRatio)}",
        )
    }

    override fun shouldExit(
        ticker: NormalizedTicker,
        orderBook: NormalizedOrderBook,
        entryPrice: Double,
        holdDurationMs: Long,
    ): Boolean {
        val pnlPercent = (ticker.price - entryPrice) / entryPrice * 100.0

        if (pnlPercent >= EXIT_PROFIT_PERCENT) return true
        if (pnlPercent <= -EXIT_LOSS_PERCENT) return true
        if (holdDurationMs >= MAX_HOLD_DURATION_MS) return true

        return false
    }

    private fun isPriceRising(tickers: List<NormalizedTicker>): Boolean {
        if (tickers.size < 3) return false
        val recent = tickers.take(3)
        return recent[0].price > recent[1].price && recent[1].price > recent[2].price
    }
}
