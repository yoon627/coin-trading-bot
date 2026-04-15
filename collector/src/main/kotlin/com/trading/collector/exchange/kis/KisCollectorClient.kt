package com.trading.collector.exchange.kis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.collector.exchange.ExchangeClient
import com.trading.common.domain.AssetType
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(prefix = "collector.kis", name = ["enabled"], havingValue = "true")
class KisCollectorClient(
    private val kisWebClient: WebClient,
    private val kisAuthProvider: KisAuthProvider,
    private val objectMapper: ObjectMapper,
) : ExchangeClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val exchange: Exchange = Exchange.KIS
    override val assetType: AssetType = AssetType.STOCK

    companion object {
        private const val TR_ID_US_PRICE = "HHDFS00000300"
        private const val TR_ID_US_DAILY_PRICE = "HHDFS76240000"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val RATE_LIMIT_DELAY_MS = 200L
    }

    /**
     * KIS는 주식용 WebSocket이 있지만 해외주식 실시간은 별도 신청이 필요.
     * 여기서는 REST 폴링으로 구현하고, 추후 WebSocket으로 교체 가능.
     */
    override fun tickerFlow(markets: List<String>): Flow<NormalizedTicker> = flow {
        log.info("KIS ticker polling started for {} markets", markets.size)
        while (true) {
            for (market in markets) {
                try {
                    val ticker = fetchCurrentPrice(market)
                    if (ticker != null) emit(ticker)
                    delay(RATE_LIMIT_DELAY_MS)
                } catch (e: Exception) {
                    log.warn("KIS price fetch failed for {}: {}", market, e.message)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun getCandles(market: String, interval: CandleInterval, count: Int): List<NormalizedCandle> {
        val ticker = MarketPair.toKisFormat(market)
        val exchangeCode = resolveExchangeCode(ticker)
        val token = kisAuthProvider.getAccessToken()

        val endDate = LocalDate.now(ZoneId.of("America/New_York"))
        val startDate = endDate.minusDays((count * 2).toLong())

        val response = kisWebClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/uapi/overseas-price/v1/quotations/dailyprice")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", exchangeCode)
                    .queryParam("SYMB", ticker)
                    .queryParam("GUBN", "0")
                    .queryParam("BYMD", endDate.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .queryParam("MODP", "1")
                    .build()
            }
            .header("authorization", "Bearer $token")
            .header("appkey", kisAuthProvider.getAppKey())
            .header("appsecret", kisAuthProvider.getAppSecret())
            .header("tr_id", TR_ID_US_DAILY_PRICE)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        val root = objectMapper.readTree(response)
        val output2 = root["output2"] ?: return emptyList()

        return output2.take(count).mapNotNull { node ->
            parseDailyCandle(node, market, interval)
        }
    }

    private suspend fun fetchCurrentPrice(market: String): NormalizedTicker? {
        val ticker = MarketPair.toKisFormat(market)
        val exchangeCode = resolveExchangeCode(ticker)
        val token = kisAuthProvider.getAccessToken()

        val response = kisWebClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/uapi/overseas-price/v1/quotations/price")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", exchangeCode)
                    .queryParam("SYMB", ticker)
                    .build()
            }
            .header("authorization", "Bearer $token")
            .header("appkey", kisAuthProvider.getAppKey())
            .header("appsecret", kisAuthProvider.getAppSecret())
            .header("tr_id", TR_ID_US_PRICE)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        val root = objectMapper.readTree(response)
        val output = root["output"] ?: return null
        val rtCode = root["rt_cd"]?.asText()
        if (rtCode != "0") {
            log.warn("KIS API error for {}: {}", ticker, root["msg1"]?.asText())
            return null
        }

        val price = output["last"]?.asDouble() ?: return null
        if (price <= 0) return null

        return NormalizedTicker(
            exchange = Exchange.KIS,
            market = market,
            price = price,
            bidPrice = output["pbid"]?.asDouble() ?: 0.0,
            askPrice = output["pask"]?.asDouble() ?: 0.0,
            volume24h = output["tvol"]?.asDouble() ?: 0.0,
            quoteVolume24h = output["tamt"]?.asDouble() ?: 0.0,
            changeRate24h = output["rate"]?.asDouble()?.let { it / 100.0 } ?: 0.0,
            highPrice24h = output["high"]?.asDouble() ?: 0.0,
            lowPrice24h = output["low"]?.asDouble() ?: 0.0,
            timestamp = Instant.now(),
        )
    }

    private fun parseDailyCandle(node: JsonNode, market: String, interval: CandleInterval): NormalizedCandle? {
        val dateStr = node["xymd"]?.asText() ?: return null
        val open = node["open"]?.asDouble() ?: return null
        val high = node["high"]?.asDouble() ?: return null
        val low = node["low"]?.asDouble() ?: return null
        val close = node["clos"]?.asDouble() ?: return null
        val volume = node["tvol"]?.asDouble() ?: 0.0

        if (open <= 0 || close <= 0) return null

        val date = LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE)
        val openTime = date.atStartOfDay(ZoneId.of("America/New_York")).toInstant()

        return NormalizedCandle(
            exchange = Exchange.KIS,
            market = market,
            openPrice = open,
            highPrice = high,
            lowPrice = low,
            closePrice = close,
            volume = volume,
            interval = interval,
            openTime = openTime,
            closeTime = openTime.plusSeconds(interval.minutes * 60L),
        )
    }

    private fun resolveExchangeCode(ticker: String): String {
        return US_STOCK_EXCHANGES[ticker.uppercase()] ?: "NAS"
    }
}

/**
 * 주요 미국주식 거래소 코드 매핑.
 * NAS=나스닥, NYS=뉴욕, AMS=아멕스
 */
private val US_STOCK_EXCHANGES = mapOf(
    // NASDAQ
    "AAPL" to "NAS", "MSFT" to "NAS", "GOOGL" to "NAS", "AMZN" to "NAS",
    "META" to "NAS", "TSLA" to "NAS", "NVDA" to "NAS", "NFLX" to "NAS",
    "AMD" to "NAS", "INTC" to "NAS", "AVGO" to "NAS", "QCOM" to "NAS",
    "COST" to "NAS", "PYPL" to "NAS", "ADBE" to "NAS", "CSCO" to "NAS",
    "PEP" to "NAS", "TMUS" to "NAS", "CMCSA" to "NAS", "ABNB" to "NAS",
    "COIN" to "NAS", "MSTR" to "NAS", "MU" to "NAS", "MARA" to "NAS",
    // NYSE
    "JPM" to "NYS", "V" to "NYS", "JNJ" to "NYS", "WMT" to "NYS",
    "PG" to "NYS", "MA" to "NYS", "HD" to "NYS", "BAC" to "NYS",
    "DIS" to "NYS", "KO" to "NYS", "NKE" to "NYS", "MCD" to "NYS",
    "GS" to "NYS", "BA" to "NYS", "IBM" to "NYS", "CVX" to "NYS",
    "XOM" to "NYS", "T" to "NYS", "VZ" to "NYS", "PFE" to "NYS",
    "PLTR" to "NYS", "SQ" to "NYS", "SOFI" to "NYS", "RIOT" to "NYS",
    // AMEX
    "SPY" to "AMS", "QQQ" to "AMS", "IWM" to "AMS",
)
