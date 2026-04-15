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
        @RequestParam(defaultValue = "0") offset: Int,
    ): Map<String, Any> {
        val userId = currentUserId()
        val sanitizedLimit = requestValidators.sanitizeTradeLimit(limit)
        val sanitizedOffset = offset.coerceAtLeast(0)
        val records = tradeRecordRepository.findByUserId(userId, sanitizedLimit, sanitizedOffset)
        val total = tradeRecordRepository.countByUserId(userId)
        return mapOf("total" to total, "limit" to sanitizedLimit, "offset" to sanitizedOffset, "records" to records)
    }
}
