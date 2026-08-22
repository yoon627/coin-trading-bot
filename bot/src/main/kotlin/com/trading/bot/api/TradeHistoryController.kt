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

    /**
     * 매수→매도를 짝지어 돌려준다. flat 한 [getTrades] 만으로는 어느 매도가 어느 매수의 청산인지 알 수 없다.
     */
    @GetMapping("/roundtrips")
    suspend fun getRoundTrips(
        @RequestParam(defaultValue = "100") limit: Int,
    ): Map<String, Any> {
        val userId = currentUserId()
        val sanitizedLimit = requestValidators.sanitizeTradeLimit(limit)
        // 상한 초과 여부는 한 건 더 받아 봐야 안다 — 정확히 상한만큼이면 잘린 것이 없다.
        val fetched = tradeRecordRepository.findRecentAscending(userId, MAX_SOURCE_RECORDS + 1)
        val truncated = fetched.size > MAX_SOURCE_RECORDS
        // 오름차순이라 뒤쪽이 최신 — 넘칠 때 버리는 쪽은 가장 오래된 한 건이다.
        val records = if (truncated) fetched.takeLast(MAX_SOURCE_RECORDS) else fetched
        val roundTrips = assembleRoundTrips(records)
        return mapOf(
            "total" to roundTrips.size,
            "limit" to sanitizedLimit,
            "truncated" to truncated,
            // Map 키는 Jackson SNAKE_CASE 전략을 타지 않으므로(POJO 프로퍼티만 해당) 직접 맞춘다.
            "round_trips" to roundTrips.take(sanitizedLimit),
        )
    }

    internal companion object {
        /** 조립을 위해 메모리에 올리는 원본 레코드 상한. 넘으면 오래된 쪽이 잘린다(응답의 truncated). */
        const val MAX_SOURCE_RECORDS = 5000
    }
}
