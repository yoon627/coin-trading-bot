package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.TradingManagers
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val managers: TradingManagers,
    private val userRepository: UserRepository,
    private val userSecretsService: UserSecretsService,
    private val requestValidators: RequestValidators,
) {
    @GetMapping
    suspend fun getPortfolio(@RequestParam(required = false) exchange: String? = null): Map<String, Any> {
        // 원화 현금 행과 "KRW-" 마켓 규칙이 Upbit 계좌 어휘라 다른 거래소는 받지 않는다.
        if (requestValidators.parseExchange(exchange) != Exchange.UPBIT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "포트폴리오 조회는 아직 UPBIT 만 지원한다")
        }
        val manager = managers.get(Exchange.UPBIT)
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }

        val client = manager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        val accounts = client.getAccounts()

        val krw = accounts.find { it.currency == "KRW" }
        val krwBalance = krw?.balanceDouble() ?: 0.0

        val coins = accounts.filter { it.currency != "KRW" && it.balanceDouble() > 0 }
        val holdings = mutableListOf<Map<String, Any?>>()
        var totalEval = krwBalance

        if (coins.isNotEmpty()) {
            val markets = coins.map { "KRW-${it.currency}" }.joinToString(",")
            val tickers = try { client.getTicker(markets) } catch (_: Exception) { emptyList() }
            val priceMap = tickers.associateBy { it.market }

            for (coin in coins) {
                val market = "KRW-${coin.currency}"
                val ticker = priceMap[market]
                val currentPrice = ticker?.tradePrice ?: 0.0
                val avgPrice = coin.avgBuyPriceDouble()
                val balance = coin.balanceDouble()
                val evalAmount = currentPrice * balance
                val investAmount = avgPrice * balance
                val pnl = if (avgPrice > 0) ((currentPrice - avgPrice) / avgPrice) * 100.0 else 0.0
                val pnlAmount = evalAmount - investAmount

                totalEval += evalAmount

                holdings.add(mapOf(
                    "currency" to coin.currency,
                    "market" to market,
                    "balance" to balance,
                    "avg_buy_price" to avgPrice,
                    "current_price" to currentPrice,
                    "eval_amount" to evalAmount,
                    "invest_amount" to investAmount,
                    "pnl_percent" to pnl,
                    "pnl_amount" to pnlAmount,
                ))
            }
        }

        return mapOf(
            "krw_balance" to krwBalance,
            "total_eval" to totalEval,
            "holdings" to holdings,
        )
    }
}
