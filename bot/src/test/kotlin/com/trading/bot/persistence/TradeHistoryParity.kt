package com.trading.bot.persistence

import com.trading.bot.api.TradeHistoryRow
import com.trading.bot.api.toHistoryRow
import com.trading.bot.persistence.entity.OrderRowEntity
import com.trading.bot.persistence.entity.TradeRecordEntity
import java.time.ZoneId

/**
 * 화면 값을 trade_records 에서 만든 것과 orders 에서 만든 것이 같은지 본다. 화면은 사용자·거래소마다 시간순으로 쌓아 왕복을
 * 묶으므로 순서까지 같아야 한다: trade_records 는 (created_at, id), orders 는 (completed_at, 짝 trade_records id, id) 순으로
 * 놓고 줄마다 비교한다. id 는 표마다 달라 뺀다.
 */
object TradeHistoryParity {

    class Result(val compared: Int, val differences: List<String>, val sameTimeGroups: Int, val incomplete: Int)

    fun compare(records: List<TradeRecordEntity>, orders: List<OrderRowEntity>, zone: ZoneId): Result {
        val old = records.groupBy { it.userId to it.exchange }
            .mapValues { (_, rows) -> rows.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.toHistoryRow() } }
        val filled = orders.filter { it.status == "FILLED" }.map { it to it.toHistoryRow(zone) }
        val new = filled.groupBy { (row, _) -> row.userId as Long? to row.exchange }
            .mapValues { (_, rows) ->
                rows.sortedWith(compareBy({ it.second.createdAt }, { it.first.tradeRecordId ?: Long.MAX_VALUE }, { it.first.id })).map { it.second }
            }
        val differences = mutableListOf<String>()
        for (key in old.keys + new.keys) {
            val left = old[key].orEmpty().map { it.comparable() }
            val right = new[key].orEmpty().map { it.comparable() }
            if (left.size != right.size) differences += "user=${key.first} exchange=${key.second}: ${left.size} trade records vs ${right.size} orders"
            left.zip(right).forEachIndexed { i, (l, r) ->
                if (l != r) differences += "user=${key.first} exchange=${key.second} #$i\n  trade_records: $l\n  orders:        $r"
            }
        }
        val sameTime = old.values.sumOf { rows -> rows.groupBy { it.createdAt }.count { it.value.size > 1 } }
        return Result(records.size, differences, sameTime, filled.count { it.second.incomplete })
    }

    private fun TradeHistoryRow.comparable() = copy(id = null, fillSource = null, incomplete = false)
}
