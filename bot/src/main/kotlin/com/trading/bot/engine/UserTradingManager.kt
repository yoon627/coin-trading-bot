package com.trading.bot.engine

import com.trading.bot.client.UpbitAuthProvider
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitClientImpl
import com.trading.bot.client.UpbitWebSocketClient
import com.trading.bot.config.UpbitProperties
import com.trading.bot.kafka.MarketDataStore
import com.trading.bot.notification.DiscordNotifier
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.TradingStrategy
import jakarta.annotation.PostConstruct
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class UserTradingManager(
    private val userRepository: UserRepository,
    private val botStateRepository: BotStateRepository,
    private val tradeExecutionService: TradeExecutionService,
    private val discordNotifier: DiscordNotifier,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val upbitWebClient: WebClient,
    private val userSecretsService: UserSecretsService,
    private val upbitWebSocketClient: UpbitWebSocketClient,
    private val marketDataStore: MarketDataStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, TradingEngine>()
    private val userStrategies = ConcurrentHashMap<Long, String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    @PostConstruct
    fun restoreOnStartup() {
        if (!tradingProperties.autoStart) {
            log.info("Auto-start disabled. Skipping bot restoration.")
            return
        }
        scope.launch {
            try {
                val states = botStateRepository.findByRunningTrue().collectList().awaitSingle()
                log.info("Restoring {} running bot(s) from DB", states.size)
                for (state in states) {
                    try {
                        val user = userRepository.findById(state.userId).awaitSingleOrNull() ?: continue
                        if (user.upbitAccessKey.isNullOrBlank()) continue
                        val tickers = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        userStrategies[state.userId] = state.strategy
                        val engine = engines.computeIfAbsent(state.userId) {
                            createEngine(userSecretsService.decryptUserSecrets(user))
                        }
                        engine.setStrategy(state.strategy)
                        engine.start(tickers)
                        log.info("Restored bot for user {}: strategy={}, tickers={}", state.userId, state.strategy, tickers)
                    } catch (e: Exception) {
                        log.error("Failed to restore bot for user {}: {}", state.userId, e.message)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to restore bot states: {}", e.message)
            }
        }
    }

    fun getEngine(userId: Long): TradingEngine? = engines[userId]

    suspend fun startBot(userId: Long, tickers: List<String>?, strategyName: String?): Map<String, Any> {
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return mapOf("error" to "User not found")

        if (user.upbitAccessKey.isNullOrBlank() || user.upbitSecretKey.isNullOrBlank()) {
            return mapOf("error" to "Upbit API keys not configured. Set them via /api/user/keys")
        }

        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val engine = engines.computeIfAbsent(userId) { createEngine(decryptedUser) }

        val strategy = strategyName ?: userStrategies[userId]
        if (strategy != null) engine.setStrategy(strategy)

        val tickerList = tickers ?: tradingProperties.tickerList()
        engine.start(tickerList)

        saveState(userId, true, engine.getActiveStrategyName(), tickerList)
        return mapOf("status" to "started", "strategy" to engine.getActiveStrategyName())
    }

    suspend fun stopBot(userId: Long): Map<String, Any> {
        val engine = engines[userId] ?: return mapOf("status" to "not_running")
        engine.stop()
        saveState(userId, false, engine.getActiveStrategyName(), emptyList())
        // value-match remove: reloadUserRuntime 가 그 사이 새 engine 을 꽂았다면 evict 안 함
        engines.remove(userId, engine)
        userStrategies.remove(userId)
        return mapOf("status" to "stopped")
    }

    fun getStatus(userId: Long): Map<String, Any> {
        val engine = engines[userId]
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            "strategy" to (engine?.getActiveStrategyName() ?: userStrategies[userId] ?: tradingProperties.strategy),
            // engine.getActiveTickers() is set synchronously by start();
            // states keys only populate once the background loop initializes
            // them, so reading from states here would briefly return [] right
            // after /api/bot/start.
            "tickers" to (engine?.getActiveTickers() ?: emptyList<String>()),
            "positions" to (engine?.getStates()?.map { (ticker, state) ->
                mapOf(
                    "ticker" to ticker,
                    "position" to state.position,
                    "avg_buy_price" to state.avgBuyPrice,
                    "hold_volume" to state.holdVolume,
                    "bought_today" to state.boughtToday,
                )
            } ?: emptyList<Map<String, Any>>()),
        )
    }

    suspend fun setStrategy(userId: Long, strategyName: String): Boolean {
        val valid = strategies.any { it.name == strategyName }
        if (!valid) return false
        userStrategies[userId] = strategyName
        engines[userId]?.setStrategy(strategyName)

        val existing = botStateRepository.findByUserId(userId).awaitSingleOrNull()
        if (existing != null) {
            botStateRepository.save(existing.copy(strategy = strategyName, updatedAt = LocalDateTime.now())).awaitSingle()
        }
        return true
    }

    fun createUpbitClient(user: UserEntity): UpbitClient {
        val props = UpbitProperties(
            accessKey = user.upbitAccessKey ?: "",
            secretKey = user.upbitSecretKey ?: "",
        )
        val authProvider = UpbitAuthProvider(props)
        return UpbitClientImpl(upbitWebClient, authProvider)
    }

    suspend fun reloadUserRuntime(userId: Long) {
        val existing = engines[userId] ?: return
        val user = userRepository.findById(userId).awaitSingleOrNull() ?: return
        val decryptedUser = userSecretsService.decryptUserSecrets(user)
        val wasRunning = existing.isRunning()
        val tickers = existing.getActiveTickers()
        val strategy = existing.getActiveStrategyName()
        existing.stop()
        val replacement = createEngine(decryptedUser)
        replacement.setStrategy(strategy)
        if (!engines.replace(userId, existing, replacement)) {
            log.warn("reloadUserRuntime: existing engine evicted (user {}); not installing replacement", userId)
            return
        }
        userStrategies[userId] = strategy
        if (wasRunning) {
            replacement.start(tickers.ifEmpty { tradingProperties.tickerList() })
        }
    }

    private fun createEngine(user: UserEntity): TradingEngine {
        val client = createUpbitClient(user)
        val positionManager = PositionManager(client, tradingProperties)
        val dailyResetManager = DailyResetManager()

        return TradingEngine(
            upbitClient = client,
            positionManager = positionManager,
            dailyResetManager = dailyResetManager,
            tradeExecutionService = tradeExecutionService,
            strategies = strategies,
            tradingProperties = tradingProperties,
            userId = user.id!!,
            username = user.username,
            discordWebhookUrl = user.discordWebhookUrl,
            webSocketClient = upbitWebSocketClient,
            marketDataStore = marketDataStore,
        )
    }

    private suspend fun saveState(userId: Long, running: Boolean, strategy: String, tickers: List<String>) {
        try {
            val existing = botStateRepository.findByUserId(userId).awaitSingleOrNull()
            val tickersStr = tickers.joinToString(",").ifEmpty {
                existing?.tickers ?: tradingProperties.tickers
            }
            if (existing != null) {
                botStateRepository.save(
                    existing.copy(running = running, strategy = strategy, tickers = tickersStr, updatedAt = LocalDateTime.now())
                ).awaitSingle()
            } else {
                botStateRepository.save(
                    BotStateEntity(userId = userId, running = running, strategy = strategy, tickers = tickersStr)
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.error("Failed to save bot state for user {}: {}", userId, e.message)
        }
    }
}
