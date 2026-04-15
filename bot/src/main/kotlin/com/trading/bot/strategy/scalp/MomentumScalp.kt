package com.trading.bot.strategy.scalp

import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker
import org.springframework.stereotype.Component

/**
 * 모멘텀 스캘핑 전략.
 * 단기 가격 급등 + 거래량 폭증 감지 시 추세에 편승한다.
 */
@Component
class MomentumScalp : ScalpStrategy {

    override val name: String = "momentum_scalp"

    companion object {
        private const val MIN_PRICE_CHANGE_PERCENT = 0.3
        private const val MIN_VOLUME_SURGE_RATIO = 2.0
        private const val EXIT_PROFIT_PERCENT = 0.05
        private const val EXIT_LOSS_PERCENT = 0.03
        private const val MAX_HOLD_DURATION_MS = 120_000L
        private const val LOOKBACK_SIZE = 10
    }

    override fun shouldEntry(
        ticker: NormalizedTicker,
        orderBook: NormalizedOrderBook,
        recentTickers: List<NormalizedTicker>,
    ): ScalpSignal {
        if (recentTickers.size < LOOKBACK_SIZE) {
            return ScalpSignal(ScalpAction.HOLD, reason = "insufficient data")
        }

        val recent = recentTickers.take(LOOKBACK_SIZE)
        val priceChange = (recent.first().price - recent.last().price) / recent.last().price * 100
        if (priceChange < MIN_PRICE_CHANGE_PERCENT) {
            return ScalpSignal(ScalpAction.HOLD, reason = "momentum too weak")
        }

        val avgVolume = recent.drop(3).map { it.volume24h }.average()
        val currentVolume = recent.take(3).map { it.volume24h }.average()
        val volumeRatio = if (avgVolume > 0) currentVolume / avgVolume else 0.0
        if (volumeRatio < MIN_VOLUME_SURGE_RATIO) {
            return ScalpSignal(ScalpAction.HOLD, reason = "no volume surge")
        }

        if (orderBook.imbalanceRatio < 0.5) {
            return ScalpSignal(ScalpAction.HOLD, reason = "ask pressure dominant")
        }

        return ScalpSignal(
            action = ScalpAction.BUY,
            confidence = minOf(priceChange / 1.0, 1.0),
            reason = "momentum=${String.format("%.2f", priceChange)}%, vol_surge=${String.format("%.1f", volumeRatio)}x",
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
}
