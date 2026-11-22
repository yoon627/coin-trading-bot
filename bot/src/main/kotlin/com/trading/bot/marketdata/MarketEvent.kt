package com.trading.bot.marketdata

import com.trading.common.domain.NormalizedTicker
import com.trading.common.domain.NormalizedTrade

/** 업비트 WebSocket 한 연결에서 오는 메시지. */
sealed interface MarketEvent {
    data class Ticker(val ticker: NormalizedTicker) : MarketEvent

    data class Trade(val trade: NormalizedTrade) : MarketEvent
}
