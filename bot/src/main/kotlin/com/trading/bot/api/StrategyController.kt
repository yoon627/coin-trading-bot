package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.common.strategy.TradingStrategy
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/strategies")
class StrategyController(
    private val strategies: List<TradingStrategy>,
    private val tradeRecordRepository: TradeRecordRepository,
) {
    @GetMapping
    fun listStrategies(): List<Map<String, String>> {
        return strategies.map { mapOf("name" to it.name) }
    }

    @GetMapping("/performance")
    suspend fun getPerformance(): Map<String, Any> {
        val userId = currentUserId()
        // 전략 미상(null)은 unknown 으로 묶는다 — 외부·과거 수동 매수 보유를 syncPosition 으로 편입한 포지션은 진입 전략이 없다.
        val results = tradeRecordRepository.aggregateByStrategy(userId).map { row ->
            mapOf(
                "strategy" to (row.strategy ?: "unknown"),
                "total_trades" to row.totalTrades,
                "sell_trades" to row.sellTrades,
                "win_trades" to row.winTrades,
                "win_rate" to if (row.sellTrades > 0) row.winTrades.toDouble() / row.sellTrades * 100 else 0.0,
                "total_pnl_pct" to row.totalPnlPct,
                "avg_pnl_pct" to if (row.sellTrades > 0) row.totalPnlPct / row.sellTrades else 0.0,
                "total_pnl_amount" to row.totalPnlAmount,
                // 실체결 대금(order_amount) 합 — 미상 행은 빠지므로 건수를 같이 내려 "일부 미상"을 알 수 있게 한다(#146).
                "total_amount" to row.totalAmount,
                "amount_unknown_trades" to row.amountUnknownTrades,
            )
        }

        return mapOf("strategies" to results)
    }
}
