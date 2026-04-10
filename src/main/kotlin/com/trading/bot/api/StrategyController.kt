package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.config.TradingProperties
import com.trading.bot.engine.BacktestConfig
import com.trading.bot.engine.BacktestEngine
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import com.trading.bot.strategy.TradingStrategy
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/strategies")
class StrategyController(
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val tradeRecordRepository: TradeRecordRepository,
    private val userTradingManager: UserTradingManager,
    private val userRepository: UserRepository,
    private val userSecretsService: UserSecretsService,
) {
    private val backtestEngine = BacktestEngine(strategies, tradingProperties)

    @GetMapping
    fun listStrategies(): List<Map<String, String>> {
        return strategies.map { mapOf("name" to it.name) }
    }

    @GetMapping("/performance")
    suspend fun getPerformance(): Map<String, Any> {
        val userId = currentUserId()
        val records = tradeRecordRepository.findByUserId(userId, 1000)

        val byStrategy = records.groupBy { it.strategy ?: "unknown" }
        val results = byStrategy.map { (strategy, trades) ->
            val sells = trades.filter { it.side == "SELL" && it.pnlPercent != null }
            val wins = sells.count { (it.pnlPercent ?: 0.0) > 0 }
            val totalPnl = sells.sumOf { it.pnlPercent ?: 0.0 }
            val totalAmount = trades.sumOf { it.totalAmount }

            mapOf(
                "strategy" to strategy,
                "total_trades" to trades.size,
                "sell_trades" to sells.size,
                "win_trades" to wins,
                "win_rate" to if (sells.isNotEmpty()) (wins.toDouble() / sells.size * 100) else 0.0,
                "total_pnl_pct" to totalPnl,
                "avg_pnl_pct" to if (sells.isNotEmpty()) totalPnl / sells.size else 0.0,
                "total_amount" to totalAmount,
            )
        }

        return mapOf("strategies" to results)
    }

    @PostMapping("/backtest")
    suspend fun runBacktest(@RequestBody req: BacktestRequest): Any {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys required for backtest")
        }

        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        val ticker = req.ticker ?: "KRW-BTC"
        val days = (req.days ?: 200).coerceIn(30, 200)
        val candles = client.getDayCandles(ticker, days)

        val config = BacktestConfig(
            takeProfitPct = req.takeProfitPct ?: 5.0,
            maxLossPct = req.maxLossPct ?: 3.0,
            kValue = req.kValue ?: tradingProperties.kValue,
            trailingStopPct = req.trailingStopPct ?: 2.0,
            maxHoldDays = req.maxHoldDays ?: 7,
            useMarketFilter = req.useMarketFilter ?: true,
        )

        return if (req.strategy != null) {
            backtestEngine.run(req.strategy, candles, ticker, config)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy: ${req.strategy}")
        } else {
            mapOf("results" to backtestEngine.compareAll(candles, ticker, config))
        }
    }
}

data class BacktestRequest(
    val strategy: String? = null,
    val ticker: String? = null,
    val days: Int? = null,
    val takeProfitPct: Double? = null,
    val maxLossPct: Double? = null,
    val kValue: Double? = null,
    val trailingStopPct: Double? = null,
    val maxHoldDays: Int? = null,
    val useMarketFilter: Boolean? = null,
)
