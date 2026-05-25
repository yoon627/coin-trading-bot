package com.trading.collector.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.collector.config.CollectorProperties
import com.trading.collector.config.ExchangeConfig
import com.trading.collector.exchange.ExchangeClient
import com.trading.common.domain.AssetType
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.MarketPair
import com.trading.common.event.Topics
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
@EnableConfigurationProperties(CollectorProperties::class)
class MarketDataCollectorService(
    private val exchangeClients: List<ExchangeClient>,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val collectorProperties: CollectorProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val CANDLE_COLLECT_INTERVAL_MS = 60_000L
    }

    @PostConstruct
    fun startCollecting() {
        for (client in exchangeClients) {
            val config = getConfigFor(client.exchange) ?: continue
            if (!config.enabled) continue

            val markets = config.marketList().map { MarketPair.normalize(client.exchange, it) }
            if (markets.isEmpty()) continue

            log.info("Starting collector for {} with markets: {}", client.exchange, markets)

            scope.launch { collectTickers(client, markets) }
            scope.launch { collectCandlesPeriodically(client, markets) }
        }
    }

    @PreDestroy
    fun stopCollecting() {
        log.info("Stopping all collectors")
        scope.cancel()
    }

    private suspend fun collectTickers(client: ExchangeClient, markets: List<String>) {
        log.info("[{}] Starting ticker collection for {} markets", client.exchange, markets.size)
        try {
            client.tickerFlow(markets).collect { ticker ->
                val key = "${ticker.exchange}:${ticker.market}"
                val value = objectMapper.writeValueAsString(ticker)
                kafkaTemplate.send(Topics.MARKET_TICKER, key, value)
            }
        } catch (e: Exception) {
            log.error("[{}] Ticker collection failed: {}", client.exchange, e.message)
        }
    }

    private suspend fun collectCandlesPeriodically(client: ExchangeClient, markets: List<String>) {
        log.info("[{}] Starting periodic candle collection", client.exchange)
        while (true) {
            for (market in markets) {
                try {
                    val candleInterval = if (client.assetType == AssetType.STOCK) CandleInterval.D1 else CandleInterval.M1
                    val candles = client.getCandles(market, candleInterval, 1)
                    for (candle in candles) {
                        val key = "${candle.exchange}:${candle.market}:${candle.interval.label}"
                        val value = objectMapper.writeValueAsString(candle)
                        kafkaTemplate.send(Topics.MARKET_CANDLE, key, value)
                    }
                } catch (e: Exception) {
                    log.warn("[{}] Candle collection failed for {}: {}", client.exchange, market, e.message)
                }
            }
            delay(CANDLE_COLLECT_INTERVAL_MS)
        }
    }

    private fun getConfigFor(exchange: Exchange): ExchangeConfig? = when (exchange) {
        Exchange.UPBIT -> collectorProperties.upbit
        Exchange.BINANCE -> collectorProperties.binance
        Exchange.KIS -> ExchangeConfig(
            enabled = collectorProperties.kis.enabled,
            markets = collectorProperties.kis.markets,
        )
        else -> null
    }
}
