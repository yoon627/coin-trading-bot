package com.trading.bot.api

import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.entity.TradeRecordEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trades")
class TradeHistoryController(
    private val tradeRecordRepository: TradeRecordRepository,
) {

    @GetMapping
    suspend fun getTrades(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(required = false) ticker: String?,
    ): Map<String, Any> {
        val records: List<TradeRecordEntity> = if (ticker != null) {
            tradeRecordRepository.findByTicker(ticker, limit)
        } else {
            tradeRecordRepository.findAll(limit, offset)
        }

        val total = tradeRecordRepository.count()

        return mapOf(
            "total" to total,
            "records" to records,
        )
    }
}
