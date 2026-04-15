package com.trading.bot.strategy.scalp

import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker

/**
 * 스캘핑(단타) 전략 인터페이스.
 * 호가창 + 틱 데이터 기반으로 초단기 매매 시그널을 생성한다.
 */
interface ScalpStrategy {
    val name: String

    fun shouldEntry(
        ticker: NormalizedTicker,
        orderBook: NormalizedOrderBook,
        recentTickers: List<NormalizedTicker>,
    ): ScalpSignal

    fun shouldExit(
        ticker: NormalizedTicker,
        orderBook: NormalizedOrderBook,
        entryPrice: Double,
        holdDurationMs: Long,
    ): Boolean
}

data class ScalpSignal(
    val action: ScalpAction,
    val confidence: Double = 0.0,
    val reason: String = "",
)

enum class ScalpAction {
    BUY, SELL, HOLD,
}
