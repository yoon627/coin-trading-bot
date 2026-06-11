package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.BacktestConfig
import com.trading.bot.engine.BacktestEngine
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
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
    private val requestValidators: RequestValidators,
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
        val ticker = requestValidators.normalizeMarket(req.ticker ?: "KRW-BTC")
        val days = (req.days ?: 200).coerceIn(30, 200)
        val candles = client.getDayCandles(ticker, days)

        // NaN/Infinity/음수/거대값이 BacktestConfig 로 흘러들어 비정상 시뮬/산술예외(500)를 내지 않도록 검증.
        // 폴백은 라이브 설정(tradingProperties)과 동일 — 백테 디폴트가 라이브와 정반대(TP5/SL3 vs TP2/SL5)이던
        // 정합 결함(이슈 #27) 수정. BacktestConfig 클래스 디폴트는 직접 생성 경로용으로 별개.
        val config = BacktestConfig(
            takeProfitPct = requireInRange(req.takeProfitPct ?: tradingProperties.takeProfitPct, "takeProfitPct", 0.0, 100.0),
            maxLossPct = requireInRange(req.maxLossPct ?: tradingProperties.maxLossPct, "maxLossPct", 0.0, 100.0),
            kValue = requireInRange(req.kValue ?: tradingProperties.kValue, "kValue", 0.0, 2.0),
            trailingStopPct = requireInRange(req.trailingStopPct ?: tradingProperties.trailingStopPct, "trailingStopPct", 0.0, 100.0),
            maxHoldDays = (req.maxHoldDays ?: 7).coerceIn(1, 365),
            useMarketFilter = req.useMarketFilter ?: true,
            chartExitEnabled = req.chartExitEnabled ?: false,
        )

        return if (req.strategy != null) {
            backtestEngine.run(req.strategy, candles, ticker, config)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown strategy: ${req.strategy}")
        } else {
            mapOf("results" to backtestEngine.compareAll(candles, ticker, config))
        }
    }

    private fun requireInRange(value: Double, name: String, min: Double, max: Double): Double {
        if (!value.isFinite() || value < min || value > max) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name must be a finite number in [$min, $max]")
        }
        return value
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
    val chartExitEnabled: Boolean? = null,
)
