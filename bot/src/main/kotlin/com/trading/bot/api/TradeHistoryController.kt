package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.persistence.OrderRowRepository
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.common.domain.Exchange
import java.time.ZoneId
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trades")
class TradeHistoryController(
    private val orderRows: OrderRowRepository,
    private val requestValidators: RequestValidators,
    /** 체결 시각을 화면의 LocalDateTime 으로 바꿀 시간대. 옛 trade_records 를 쓴 JVM 시간대와 같아야 시각이 그대로다. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** orders 의 체결 줄을 화면 줄로 바꾼다. 값 칼럼이 비어 0 으로 보이는 줄은 화면에 그대로 내되 WARN 으로 남긴다. */
    private fun historyRows(userId: Long, exchange: Exchange, rows: List<OrderRowEntity>): List<TradeHistoryRow> {
        val history = rows.map { it.toHistoryRow(zone) }
        val incomplete = history.filter { it.incomplete }
        if (incomplete.isNotEmpty()) {
            log.warn(
                "[TRADE HISTORY] user {} 의 {} 체결 줄 {}건이 화면 값이나 체결 시각이 비어 0·생성 시각으로 보인다(orders id {}) — " +
                    "그 orders 줄의 값 칼럼·체결 시각을 고친다",
                userId, exchange, incomplete.size, incomplete.take(SHOWN_IDS).map { it.id },
            )
        }
        return history
    }

    /** 한 거래소의 기록만 돌려주고 total 도 그 거래소 기준이다. */
    @GetMapping
    suspend fun getTrades(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) exchange: String? = null,
    ): Map<String, Any> {
        val target = requestValidators.parseExchange(exchange)
        val userId = currentUserId()
        val sanitizedLimit = requestValidators.sanitizeTradeLimit(limit)
        val sanitizedOffset = offset.coerceAtLeast(0)
        val records = historyRows(
            userId,
            target,
            orderRows.findFilledPage(userId, target.name, sanitizedLimit, sanitizedOffset).collectList().awaitSingle(),
        )
        val total = orderRows.countFilled(userId, target.name).awaitSingle()
        return mapOf("total" to total, "limit" to sanitizedLimit, "offset" to sanitizedOffset, "records" to records)
    }

    /**
     * 한 거래소의 매수→매도를 짝지어 돌려준다. flat 한 [getTrades] 만으로는 어느 매도가 어느 매수의 청산인지 알 수 없다.
     */
    @GetMapping("/roundtrips")
    suspend fun getRoundTrips(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) exchange: String? = null,
    ): Map<String, Any> {
        val target = requestValidators.parseExchange(exchange)
        val userId = currentUserId()
        val sanitizedLimit = requestValidators.sanitizeTradeLimit(limit)
        // 상한을 넘었는지 알려고 한 건 더 받는다.
        val fetched = historyRows(
            userId,
            target,
            orderRows.findFilledRecentAscending(userId, target.name, MAX_SOURCE_RECORDS + 1).collectList().awaitSingle(),
        )
        val truncated = fetched.size > MAX_SOURCE_RECORDS
        // 오름차순이라 버리는 한 건은 가장 오래된 행이다.
        val records = if (truncated) fetched.takeLast(MAX_SOURCE_RECORDS) else fetched
        val roundTrips = assembleRoundTrips(records, truncatedHead = truncated)
        return mapOf(
            "total" to roundTrips.size,
            "limit" to sanitizedLimit,
            "truncated" to truncated,
            // Map 키에는 Jackson SNAKE_CASE 전략이 적용되지 않는다.
            "round_trips" to roundTrips.take(sanitizedLimit),
        )
    }

    internal companion object {
        /** 조립용으로 메모리에 올리는 원본 레코드 상한. 넘으면 오래된 쪽부터 잘린다. */
        const val MAX_SOURCE_RECORDS = 5000

        /** WARN 한 줄에 싣는 orders id 수. */
        private const val SHOWN_IDS = 20
    }
}
