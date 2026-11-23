package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.trading.bot.api.RequestValidators
import com.trading.bot.api.TradeHistoryController
import com.trading.bot.api.assembleRoundTrips
import com.trading.bot.api.toHistoryRow
import com.trading.common.domain.Exchange
import java.time.ZoneId
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder

/**
 * 거래 기록 화면(`/api/trades` 의 모든 쪽, `/roundtrips`)의 응답이 orders 를 읽는 지금 컨트롤러와 trade_records 를 읽던 옛 경로에서
 * 같은지 본다. 앱과 같은 ObjectMapper 로 직렬화해 문자열로 비교한다. 줄 id 는 표마다 달라 뺀다.
 * 옛 경로는 전환 직전의 컨트롤러 본문을 옛 읽기(TradeRecordRepository)로 그대로 옮긴 것이다 — 옛 표를 지울 때 함께 지운다.
 */
class TradeHistoryParity(
    private val tradeRecords: TradeRecordRepository,
    orderRows: OrderRowRepository,
    private val mapper: ObjectMapper,
    zone: ZoneId,
) {
    private val validators = RequestValidators()
    private val controller = TradeHistoryController(orderRows, validators, zone)
    private val maxLimit = validators.sanitizeTradeLimit(Int.MAX_VALUE)

    /** 다른 응답 하나마다 한 줄. [responses] 는 비교한 응답 수다. */
    class Result(val responses: Int, val differences: List<String>)

    suspend fun compare(userId: Long, exchange: Exchange): Result {
        val differences = mutableListOf<String>()
        var responses = 0
        fun check(label: String, old: Map<String, Any>, new: Map<String, Any>) {
            responses++
            val left = comparable(old)
            val right = comparable(new)
            if (left != right) differences += "user=$userId exchange=$exchange $label\n  trade_records: $left\n  orders:        $right"
        }

        val total = maxOf(tradeRecords.countByUserIdAndExchange(userId, exchange), countNew(userId, exchange))
        // 마지막 쪽 뒤의 빈 쪽까지 본다.
        var offset = 0
        while (offset <= total) {
            check("trades offset=$offset", oldTrades(userId, exchange, maxLimit, offset), asUser(userId) { controller.getTrades(maxLimit, offset, exchange.name) })
            offset += maxLimit
        }
        check("roundtrips", oldRoundTrips(userId, exchange, maxLimit), asUser(userId) { controller.getRoundTrips(maxLimit, exchange.name) })
        return Result(responses, differences)
    }

    private suspend fun countNew(userId: Long, exchange: Exchange): Long =
        (asUser(userId) { controller.getTrades(1, 0, exchange.name) }["total"] as Number).toLong()

    private suspend fun oldTrades(userId: Long, exchange: Exchange, limit: Int, offset: Int): Map<String, Any> {
        val records = tradeRecords.findByUserIdAndExchange(userId, exchange, limit, offset).map { it.toHistoryRow() }
        val total = tradeRecords.countByUserIdAndExchange(userId, exchange)
        return mapOf("total" to total, "limit" to limit, "offset" to offset, "records" to records)
    }

    private suspend fun oldRoundTrips(userId: Long, exchange: Exchange, limit: Int): Map<String, Any> {
        val max = TradeHistoryController.MAX_SOURCE_RECORDS
        val fetched = tradeRecords.findRecentAscending(userId, exchange, max + 1)
        val truncated = fetched.size > max
        val records = if (truncated) fetched.takeLast(max) else fetched
        val roundTrips = assembleRoundTrips(records.map { it.toHistoryRow() }, truncatedHead = truncated)
        return mapOf("total" to roundTrips.size, "limit" to limit, "truncated" to truncated, "round_trips" to roundTrips.take(limit))
    }

    private fun comparable(response: Map<String, Any>): String {
        val tree = mapper.valueToTree<ObjectNode>(response)
        tree.get("records")?.forEach { (it as ObjectNode).remove("id") }
        return mapper.writeValueAsString(tree)
    }

    private suspend fun <T : Any> asUser(userId: Long, block: suspend () -> T): T =
        mono { block() }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(userId, null, emptyList())))
            .awaitSingle()
}
