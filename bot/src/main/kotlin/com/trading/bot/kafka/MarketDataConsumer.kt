package com.trading.bot.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.common.domain.NormalizedCandle
import com.trading.common.domain.NormalizedTicker
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class MarketDataConsumer(
    private val marketDataStore: MarketDataStore,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["market.ticker"], groupId = "trading-bot")
    fun onTicker(record: ConsumerRecord<String, String>) {
        try {
            val ticker = objectMapper.readValue(record.value(), NormalizedTicker::class.java)
            marketDataStore.updateTicker(ticker)
        } catch (e: Exception) {
            log.warn("Failed to parse ticker message: {}", e.message)
        }
    }

    @KafkaListener(topics = ["market.candle"], groupId = "trading-bot")
    fun onCandle(record: ConsumerRecord<String, String>) {
        try {
            val candle = objectMapper.readValue(record.value(), NormalizedCandle::class.java)
            marketDataStore.addCandle(candle)
        } catch (e: Exception) {
            log.warn("Failed to parse candle message: {}", e.message)
        }
    }
}
