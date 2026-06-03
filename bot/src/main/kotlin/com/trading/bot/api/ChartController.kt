package com.trading.bot.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.strategy.Indicators
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/chart")
class ChartController(
    private val marketDataStore: MarketDataStore,
    private val marketCandleRepository: MarketCandleRepository,
    private val requestValidators: RequestValidators,
) {
    companion object {
        private const val MAX_COMPARE_MARKETS = 20
        // 거래소별 market 포맷이 다르므로(KRW-BTC vs BTCUSDT) 엄격 정규화 대신 경량 sanity 검증.
        private val CHART_MARKET_REGEX = Regex("^[A-Za-z0-9/_-]{1,40}$")
        private val ALLOWED_INDICATORS = setOf("rsi", "macd", "bb", "ma", "ema")
    }

    @GetMapping("/candles")
    suspend fun getCandles(
        @RequestParam(defaultValue = "upbit") exchange: String,
        @RequestParam market: String,
        @RequestParam interval: String,
        @RequestParam(defaultValue = "100") count: Int,
    ): List<CandleResponse> {
        val ex = parseExchange(exchange)
        val ci = parseInterval(interval)
        val safeMarket = sanitizeMarket(market)
        val safeCount = requestValidators.sanitizeTradeLimit(count) // 1..500 클램프

        // Try in-memory first
        val memoryCandles = marketDataStore.getCandles(ex, safeMarket, ci, safeCount)
        if (memoryCandles.size >= safeCount) {
            return memoryCandles.map { it.toResponse() }
        }

        // Fallback to DB
        val dbCandles = marketCandleRepository
            .findRecent(ex.name, safeMarket, ci.minutes, safeCount)
            .collectList()
            .awaitSingle()
        return dbCandles.map { it.toResponse() }
    }

    @GetMapping("/indicators")
    suspend fun getIndicators(
        @RequestParam(defaultValue = "upbit") exchange: String,
        @RequestParam market: String,
        @RequestParam interval: String,
        @RequestParam(defaultValue = "rsi,macd,bb") indicators: String,
        @RequestParam(defaultValue = "50") count: Int,
    ): IndicatorResponse {
        val ex = parseExchange(exchange)
        val ci = parseInterval(interval)
        val safeMarket = sanitizeMarket(market)
        val safeCount = requestValidators.sanitizeTradeLimit(count)
        val candles = marketDataStore.getCandles(ex, safeMarket, ci, safeCount)

        val result = mutableMapOf<String, Any?>()
        val requestedIndicators = indicators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val unknown = requestedIndicators.filterNot { it in ALLOWED_INDICATORS }
        if (unknown.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown indicators: ${unknown.joinToString(",")}")
        }

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

        return IndicatorResponse(exchange = ex.name, market = safeMarket, interval = interval, indicators = result)
    }

    @GetMapping("/tickers")
    suspend fun getTickers(@RequestParam(required = false) exchange: String?): Map<String, TickerResponse> {
        val tickers = if (exchange != null) {
            val ex = parseExchange(exchange)
            marketDataStore.getTickersByExchange(ex).associateBy { "${it.exchange}:${it.market}" }
        } else {
            marketDataStore.getAllTickers()
        }
        return tickers.mapValues { (_, v) -> v.toResponse() }
    }

    @GetMapping("/compare")
    suspend fun compareMarkets(@RequestParam markets: String): List<TickerResponse> {
        // markets format: "UPBIT:BTC/KRW,BINANCE:BTC/USDT" — 개수 상한으로 남용 방지
        return markets.split(",").take(MAX_COMPARE_MARKETS).mapNotNull { spec ->
            val parts = spec.trim().split(":")
            if (parts.size != 2) return@mapNotNull null
            val ex = parseExchange(parts[0])
            val market = sanitizeMarket(parts[1])
            marketDataStore.getLatestTicker(ex, market)?.toResponse()
        }
    }

    private fun sanitizeMarket(market: String): String {
        val trimmed = market.trim()
        if (!CHART_MARKET_REGEX.matches(trimmed)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid market: $market")
        }
        return trimmed
    }

    private fun parseExchange(value: String): Exchange =
        try { Exchange.valueOf(value.uppercase()) }
        catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid exchange: $value")
        }

    private fun parseInterval(value: String): CandleInterval =
        try { CandleInterval.fromLabel(value) }
        catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "Invalid interval: $value")
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
