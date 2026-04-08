package com.trading.bot.api

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.engine.TradingEngine
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class TradingController(
    private val tradingEngine: TradingEngine,
    private val upbitClient: UpbitClient,
) {

    @PostMapping("/bot/start")
    fun startBot(): Map<String, Any> {
        tradingEngine.start()
        return mapOf("status" to "started", "strategy" to tradingEngine.getActiveStrategyName())
    }

    @PostMapping("/bot/stop")
    fun stopBot(): Map<String, Any> {
        tradingEngine.stop()
        return mapOf("status" to "stopped")
    }

    @GetMapping("/bot/status")
    fun getStatus(): Map<String, Any> {
        val states = tradingEngine.getStates().map { (ticker, state) ->
            mapOf(
                "ticker" to ticker,
                "position" to state.position,
                "avgBuyPrice" to state.avgBuyPrice,
                "holdVolume" to state.holdVolume,
                "boughtToday" to state.boughtToday,
            )
        }
        return mapOf(
            "running" to tradingEngine.isRunning(),
            "strategy" to tradingEngine.getActiveStrategyName(),
            "positions" to states,
        )
    }

    @PostMapping("/bot/strategy")
    fun changeStrategy(@RequestBody request: StrategyRequest): Map<String, Any> {
        val success = tradingEngine.setStrategy(request.strategy)
        return if (success) {
            mapOf("status" to "changed", "strategy" to request.strategy)
        } else {
            mapOf("status" to "error", "message" to "Unknown strategy: ${request.strategy}")
        }
    }

    @GetMapping("/account")
    suspend fun getAccount(): List<Account> {
        return upbitClient.getAccounts()
    }
}

data class StrategyRequest(val strategy: String)
