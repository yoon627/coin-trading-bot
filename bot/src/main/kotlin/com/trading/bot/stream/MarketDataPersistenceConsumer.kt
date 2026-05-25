package com.trading.bot.stream

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.persistence.MarketCandleRepository
import com.trading.bot.persistence.MarketTickerRepository
import com.trading.bot.persistence.entity.MarketCandleEntity
import com.trading.bot.persistence.entity.MarketTickerEntity
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import com.trading.common.event.Topics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class MarketDataPersistenceConsumer(
    private val marketTickerRepository: MarketTickerRepository,
    private val marketCandleRepository: MarketCandleRepository,
    private val candleAggregator: CandleAggregator,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tickerSaveCount = AtomicLong(0)

    companion object {
        private const val TICKER_SAVE_INTERVAL = 10L
    }

    @KafkaListener(topics = [Topics.MARKET_TICKER], groupId = "market-data-persistence")
    fun onTicker(record: ConsumerRecord<String, String>) {
        try {
            val ticker = objectMapper.readValue(record.value(), NormalizedTicker::class.java)

            // Save every Nth ticker to avoid flooding the DB
            if (tickerSaveCount.incrementAndGet() % TICKER_SAVE_INTERVAL == 0L) {
                val entity = MarketTickerEntity(
                    exchange = ticker.exchange.name,
                    market = ticker.market,
                    price = ticker.price,
                    bidPrice = ticker.bidPrice,
                    askPrice = ticker.askPrice,
                    volume24h = ticker.volume24h,
                    quoteVolume24h = ticker.quoteVolume24h,
                    changeRate24h = ticker.changeRate24h,
                    highPrice24h = ticker.highPrice24h,
                    lowPrice24h = ticker.lowPrice24h,
                    recordedAt = ticker.timestamp,
                )
                marketTickerRepository.save(entity).subscribe()
            }
        } catch (e: Exception) {
            log.warn("Failed to persist ticker: {}", e.message)
        }
    }

    @KafkaListener(topics = [Topics.MARKET_CANDLE], groupId = "market-data-persistence")
    fun onCandle(record: ConsumerRecord<String, String>) {
        try {
            val candle = objectMapper.readValue(record.value(), NormalizedCandle::class.java)

            // Persist the candle
            val entity = MarketCandleEntity(
                exchange = candle.exchange.name,
                market = candle.market,
                intervalMinutes = candle.interval.minutes,
                openPrice = candle.openPrice,
                highPrice = candle.highPrice,
                lowPrice = candle.lowPrice,
                closePrice = candle.closePrice,
                volume = candle.volume,
                quoteVolume = candle.quoteVolume,
                openTime = candle.openTime,
                closeTime = candle.closeTime,
            )
            marketCandleRepository.save(entity).subscribe()

            // Trigger aggregation for higher timeframes
            candleAggregator.onMinuteCandle(candle)
        } catch (e: Exception) {
            log.warn("Failed to persist candle: {}", e.message)
        }
    }
}
