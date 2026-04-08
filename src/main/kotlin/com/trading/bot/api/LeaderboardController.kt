package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.UserRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api")
class LeaderboardController(
    private val userRepository: UserRepository,
    private val tradeRecordRepository: TradeRecordRepository,
    private val userTradingManager: UserTradingManager,
    private val requestValidators: RequestValidators,
) {
    @GetMapping("/leaderboard")
    suspend fun getLeaderboard(): Map<String, Any> {
        val publicUsers = userRepository.findByPublicProfileTrue().collectList().awaitSingle()
        if (publicUsers.isEmpty()) return mapOf("rankings" to emptyList<Any>())

        val allSells = tradeRecordRepository.findAllSells()
        val sellsByUser = allSells.groupBy { it.userId }

        val rankings = publicUsers.mapNotNull { user ->
            val userId = user.id ?: return@mapNotNull null
            val sells = sellsByUser[userId] ?: emptyList()
            val totalTrades = sells.size
            val wins = sells.count { (it.pnlPercent ?: 0.0) > 0 }
            val totalPnl = sells.sumOf { it.pnlPercent ?: 0.0 }
            val avgPnl = if (sells.isNotEmpty()) totalPnl / sells.size else 0.0
            val botStatus = userTradingManager.getStatus(userId)

            val strategyInfo = if (user.publicStrategy) {
                botStatus["strategy"]
            } else {
                "비공개"
            }

            mapOf(
                "user_id" to userId,
                "username" to user.username,
                "total_trades" to totalTrades,
                "win_trades" to wins,
                "win_rate" to if (totalTrades > 0) wins.toDouble() / totalTrades * 100 else 0.0,
                "total_pnl_pct" to totalPnl,
                "avg_pnl_pct" to avgPnl,
                "strategy" to strategyInfo,
                "bot_running" to (botStatus["running"] ?: false),
            )
        }.sortedByDescending { it["total_pnl_pct"] as Double }

        return mapOf("rankings" to rankings)
    }

    @GetMapping("/user/{userId}/profile")
    suspend fun getUserProfile(@PathVariable userId: Long): Map<String, Any?> {
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!user.publicProfile) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This profile is private")
        }

        val sells = tradeRecordRepository.findByUserId(userId, 1000)
            .filter { it.side == "SELL" && it.pnlPercent != null }
        val wins = sells.count { (it.pnlPercent ?: 0.0) > 0 }
        val totalPnl = sells.sumOf { it.pnlPercent ?: 0.0 }
        val botStatus = userTradingManager.getStatus(userId)

        return mapOf(
            "username" to user.username,
            "total_trades" to sells.size,
            "win_rate" to if (sells.isNotEmpty()) wins.toDouble() / sells.size * 100 else 0.0,
            "total_pnl_pct" to totalPnl,
            "strategy" to if (user.publicStrategy) botStatus["strategy"] else "비공개",
            "bot_running" to (botStatus["running"] ?: false),
            "recent_trades" to if (user.publicStrategy) {
                sells.take(10).map {
                    mapOf(
                        "ticker" to it.ticker,
                        "pnl_percent" to it.pnlPercent,
                        "reason" to it.reason,
                        "created_at" to it.createdAt,
                    )
                }
            } else emptyList<Any>(),
        )
    }

    @PostMapping("/user/settings")
    suspend fun updateSettings(@RequestBody req: UserSettingsRequest): Map<String, Any> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingle()
        val webhookUrl = requestValidators.normalizeDiscordWebhookUrl(req.discordWebhookUrl)
        userRepository.save(
            user.copy(
                publicProfile = req.publicProfile ?: user.publicProfile,
                publicStrategy = req.publicStrategy ?: user.publicStrategy,
                discordWebhookUrl = webhookUrl,
            )
        ).awaitSingle()
        userTradingManager.reloadUserRuntime(userId)
        return mapOf(
            "public_profile" to (req.publicProfile ?: user.publicProfile),
            "public_strategy" to (req.publicStrategy ?: user.publicStrategy),
            "has_discord_webhook" to (webhookUrl != null),
        )
    }
}

data class UserSettingsRequest(
    val publicProfile: Boolean? = null,
    val publicStrategy: Boolean? = null,
    val discordWebhookUrl: String? = null,
)
