package com.trading.bot.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.persistence.entity.TradeExecutionEntity
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class TradeEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TOPIC_TRADE_EXECUTION = "trade.execution"
        private const val TOPIC_NOTIFICATION = "notification"
    }

    fun publishTradeExecution(execution: TradeExecutionEntity) {
        try {
            val key = "${execution.userId}:${execution.exchange}:${execution.market}"
            val value = objectMapper.writeValueAsString(execution)
            kafkaTemplate.send(TOPIC_TRADE_EXECUTION, key, value)
        } catch (e: Exception) {
            log.error("Failed to publish trade execution: {}", e.message)
        }
    }

    fun publishNotification(userId: Long, notification: Map<String, Any>) {
        try {
            val key = userId.toString()
            val value = objectMapper.writeValueAsString(notification)
            kafkaTemplate.send(TOPIC_NOTIFICATION, key, value)
        } catch (e: Exception) {
            log.error("Failed to publish notification: {}", e.message)
        }
    }
}
