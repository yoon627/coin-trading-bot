package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.persistence.TradeRecordRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trades")
class TradeHistoryController(
    private val tradeRecordRepository: TradeRecordRepository,
    private val requestValidators: RequestValidators,
) {
    @GetMapping
    suspend fun getTrades(
        @RequestParam(defaultValue = "100") limit: Int,
    ): Map<String, Any> {
        val userId = currentUserId()
        val records = tradeRecordRepository.findByUserId(userId, requestValidators.sanitizeTradeLimit(limit))
        val total = tradeRecordRepository.countByUserId(userId)
        return mapOf("total" to total, "records" to records)
    }
}
