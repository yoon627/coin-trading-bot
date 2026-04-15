package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.trading.bot.kafka.MarketDataStore
import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.indicator.Indicators
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chart")
class ChartController(
    private val marketDataStore: MarketDataStore,
    private val marketCandleRepository: MarketCandleRepository,
) {

    @GetMapping("/candles")
    suspend fun getCandles(
        @RequestParam exchange: String,
        @RequestParam market: String,
        @RequestParam interval: String,
        @RequestParam(defaultValue = "100") count: Int,
    ): List<CandleResponse> {
        val ex = Exchange.valueOf(exchange.uppercase())
        val ci = CandleInterval.fromLabel(interval)

        // Try in-memory first
        val memoryCandles = marketDataStore.getCandles(ex, market, ci, count)
        if (memoryCandles.size >= count) {
            return memoryCandles.map { it.toResponse() }
        }

        // Fallback to DB
        val dbCandles = marketCandleRepository
            .findRecent(ex.name, market, ci.minutes, count)
            .collectList()
            .awaitSingle()
        return dbCandles.map { it.toResponse() }
    }

    @GetMapping("/indicators")
    suspend fun getIndicators(
        @RequestParam exchange: String,
        @RequestParam market: String,
        @RequestParam interval: String,
        @RequestParam(defaultValue = "rsi,macd,bb") indicators: String,
        @RequestParam(defaultValue = "50") count: Int,
    ): IndicatorResponse {
        val ex = Exchange.valueOf(exchange.uppercase())
        val ci = CandleInterval.fromLabel(interval)
        val candles = marketDataStore.getCandles(ex, market, ci, count)

        val result = mutableMapOf<String, Any?>()
        val requestedIndicators = indicators.split(",").map { it.trim() }

        if ("rsi" in requestedIndicators) {
            result["rsi"] = Indicators.calculateRsi(candles)
        }
        if ("macd" in requestedIndicators) {
            result["macd"] = Indicators.calculateMacd(candles)
        }
        if ("bb" in requestedIndicators) {
            result["bollinger_bands"] = Indicators.calculateBollingerBands(candles)
        }
        if ("ma" in requestedIndicators) {
            result["ma5"] = Indicators.calculateMa(candles, 5)
            result["ma20"] = Indicators.calculateMa(candles, 20)
        }
        if ("ema" in requestedIndicators) {
            result["ema12"] = Indicators.calculateEma(candles, 12)
            result["ema26"] = Indicators.calculateEma(candles, 26)
        }

        return IndicatorResponse(exchange = ex.name, market = market, interval = interval, indicators = result)
    }

    @GetMapping("/tickers")
    suspend fun getTickers(@RequestParam(required = false) exchange: String?): Map<String, TickerResponse> {
        val tickers = if (exchange != null) {
            val ex = Exchange.valueOf(exchange.uppercase())
            marketDataStore.getTickersByExchange(ex).associateBy { "${it.exchange}:${it.market}" }
        } else {
            marketDataStore.getAllTickers()
        }
        return tickers.mapValues { (_, v) -> v.toResponse() }
    }

    @GetMapping("/compare")
    suspend fun compareMarkets(@RequestParam markets: String): List<TickerResponse> {
        // markets format: "UPBIT:BTC/KRW,BINANCE:BTC/USDT"
        return markets.split(",").mapNotNull { spec ->
            val parts = spec.trim().split(":")
            if (parts.size != 2) return@mapNotNull null
            val ex = Exchange.valueOf(parts[0].uppercase())
            val market = parts[1]
            marketDataStore.getLatestTicker(ex, market)?.toResponse()
        }
    }

    data class CandleResponse(
        val exchange: String,
        val market: String,
        val interval: String,
        @JsonProperty("open_price") val openPrice: Double,
        @JsonProperty("high_price") val highPrice: Double,
        @JsonProperty("low_price") val lowPrice: Double,
        @JsonProperty("close_price") val closePrice: Double,
        val volume: Double,
        @JsonProperty("open_time") val openTime: String,
        @JsonProperty("close_time") val closeTime: String,
    )

    data class TickerResponse(
        val exchange: String,
        val market: String,
        val price: Double,
        @JsonProperty("volume_24h") val volume24h: Double,
        @JsonProperty("change_rate_24h") val changeRate24h: Double,
        @JsonProperty("high_price_24h") val highPrice24h: Double,
        @JsonProperty("low_price_24h") val lowPrice24h: Double,
        val timestamp: String,
    )

    data class IndicatorResponse(
        val exchange: String,
        val market: String,
        val interval: String,
        val indicators: Map<String, Any?>,
    )

    private fun NormalizedCandle.toResponse() = CandleResponse(
        exchange = exchange.name,
        market = market,
        interval = interval.label,
        openPrice = openPrice,
        highPrice = highPrice,
        lowPrice = lowPrice,
        closePrice = closePrice,
        volume = volume,
        openTime = openTime.toString(),
        closeTime = closeTime.toString(),
    )

    private fun MarketCandleEntity.toResponse() = CandleResponse(
        exchange = exchange,
        market = market,
        interval = CandleInterval.fromMinutes(intervalMinutes).label,
        openPrice = openPrice,
        highPrice = highPrice,
        lowPrice = lowPrice,
        closePrice = closePrice,
        volume = volume,
        openTime = openTime.toString(),
        closeTime = closeTime.toString(),
    )

    private fun NormalizedTicker.toResponse() = TickerResponse(
        exchange = exchange.name,
        market = market,
        price = price,
        volume24h = volume24h,
        changeRate24h = changeRate24h,
        highPrice24h = highPrice24h,
        lowPrice24h = lowPrice24h,
        timestamp = timestamp.toString(),
    )
}
