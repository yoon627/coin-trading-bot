package com.trading.collector.exchange

import com.trading.common.domain.AssetType
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedOrderBook
import com.trading.common.domain.NormalizedTicker
import kotlinx.coroutines.flow.Flow

interface ExchangeClient {
    val exchange: Exchange
    val assetType: AssetType
    fun tickerFlow(markets: List<String>): Flow<NormalizedTicker>
    suspend fun getCandles(market: String, interval: CandleInterval, count: Int): List<NormalizedCandle>
    suspend fun getOrderBook(market: String, depth: Int = 10): NormalizedOrderBook? = null
}
