package com.trading.bot.kis.engine

import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.order.StockOrderReconciler
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.marketdata.MarketDataStore
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.StockPositionStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 주식 봇 유저별 오케스트레이션(크립토 UserTradingManager 미러). bot_state(exchange=KIS) 로 상태 영속·복원. */
@Service
class StockUserTradingManager(
    private val userRepository: UserRepository,
    private val botStateRepository: BotStateRepository,
    private val kisClientFactory: KisClientFactory,
    private val stockOrderService: StockOrderService,
    private val stockOrderReconciler: StockOrderReconciler,
    private val strategies: List<TradingStrategy>,
    private val tradingProperties: TradingProperties,
    private val marketDataStore: MarketDataStore,
    private val marketCalendar: KisMarketCalendar,
    private val kisProperties: KisProperties,
    private val stockPositionStateService: StockPositionStateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val engines = ConcurrentHashMap<Long, KisStockTradingEngine>()
    private val userLocks = ConcurrentHashMap<Long, Mutex>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun lockFor(userId: Long): Mutex = userLocks.computeIfAbsent(userId) { Mutex() }

    @PostConstruct
    fun restoreOnStartup() {
        scope.launch {
            // 엔진 기동 전 부팅 reconcile 완료 보장(D21/M1) — 재시작 갭의 미해소 주문을 먼저 확정.
            try {
                stockOrderReconciler.reconcileNow()
            } catch (e: Exception) {
                log.error("stock boot reconcile failed: {}", e.message)
            }
            if (!tradingProperties.autoStart) {
                log.info("KIS auto-start disabled. Skipping stock bot restoration.")
                return@launch
            }
            try {
                val states = botStateRepository.findByRunningTrueAndExchange(EXCHANGE).collectList().awaitSingle()
                for (state in states) {
                    try {
                        val user = userRepository.findById(state.userId).awaitSingleOrNull() ?: continue
                        if (!hasKisKeys(user)) continue
                        val symbols = state.tickers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val engine = engines.computeIfAbsent(state.userId) { createEngine(user) }
                        engine.setStrategy(state.strategy)
                        engine.restorePositionState(symbols)
                        engine.start(symbols)
                        log.info("Restored KIS bot user={} strategy={} symbols={}", state.userId, state.strategy, symbols)
                    } catch (e: Exception) {
                        log.error("Failed to restore KIS bot user={}: {}", state.userId, e.message)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to restore KIS bot states: {}", e.message)
            }
        }
    }

    suspend fun startBot(userId: Long, symbols: List<String>, strategyName: String?): Map<String, Any> = lockFor(userId).withLock {
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: return@withLock mapOf("error" to "User not found")
        if (!hasKisKeys(user)) {
            return@withLock mapOf("error" to "KIS API keys not configured. Set them via /api/user/kis-keys")
        }
        if (symbols.isEmpty()) return@withLock mapOf("error" to "No symbols provided")
        if (strategyName != null && strategies.none { it.name == strategyName }) {
            return@withLock mapOf("error" to "Unknown strategy: $strategyName")
        }
        // 엔진 기동 전 미확정 WAL 주문 확정(M1/M-C) — reconcileMutex 가 부팅 패스와 직렬화.
        try {
            stockOrderReconciler.reconcileNow()
        } catch (e: Exception) {
            log.warn("pre-start reconcile failed: {}", e.message)
        }

        val engine = engines.computeIfAbsent(userId) { createEngine(user) }
        strategyName?.let { engine.setStrategy(it) }
        engine.restorePositionState(symbols)
        engine.start(symbols)
        saveState(userId, true, engine.getActiveStrategyName(), symbols)
        mapOf("status" to "started", "strategy" to engine.getActiveStrategyName(), "live" to kisProperties.liveEnabled)
    }

    suspend fun stopBot(userId: Long): Map<String, Any> = lockFor(userId).withLock {
        val engine = engines[userId] ?: return@withLock mapOf("status" to "not_running")
        engine.stop()
        saveState(userId, false, engine.getActiveStrategyName(), emptyList())
        engines.remove(userId)
        mapOf("status" to "stopped")
    }

    fun getStatus(userId: Long): Map<String, Any> {
        val engine = engines[userId]
        return mapOf(
            "running" to (engine?.isRunning() ?: false),
            "live" to kisProperties.liveEnabled,
            "trading_now" to marketCalendar.isTradingNow(),
            "strategy" to (engine?.getActiveStrategyName() ?: tradingProperties.strategy),
            "symbols" to (engine?.getActiveSymbols() ?: emptyList<String>()),
            "positions" to (engine?.getPositions()?.values?.map {
                mapOf(
                    "symbol" to it.symbol,
                    "position" to it.position,
                    "avg_buy_price" to it.avgBuyPrice,
                    "hold_qty" to it.holdQty,
                    "bought_today" to it.boughtToday,
                )
            } ?: emptyList<Map<String, Any>>()),
        )
    }

    suspend fun setStrategy(userId: Long, strategyName: String): Boolean = lockFor(userId).withLock {
        if (strategies.none { it.name == strategyName }) return@withLock false
        engines[userId]?.setStrategy(strategyName)
        botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()?.let {
            botStateRepository.save(it.copy(strategy = strategyName, updatedAt = LocalDateTime.now())).awaitSingle()
        }
        true
    }

    /** KIS 키 변경 시 — 캐시 client 무효화 + 엔진 재생성. */
    suspend fun reloadUserRuntime(userId: Long) = lockFor(userId).withLock {
        kisClientFactory.invalidate(userId)
        val existing = engines[userId] ?: return@withLock
        val user = userRepository.findById(userId).awaitSingleOrNull() ?: return@withLock
        val wasRunning = existing.isRunning()
        val symbols = existing.getActiveSymbols()
        val strategy = existing.getActiveStrategyName()
        existing.stop()
        val replacement = createEngine(user)
        replacement.setStrategy(strategy)
        engines[userId] = replacement
        if (wasRunning) replacement.start(symbols)
    }

    private fun createEngine(user: UserEntity): KisStockTradingEngine {
        val client = kisClientFactory.forUser(user)
        val positionManager = StockPositionManager(
            userId = user.id!!,
            cano = user.kisCano!!,
            acntPrdtCd = user.kisAcntPrdtCd!!,
            client = client,
            orderService = stockOrderService,
            tradingProperties = tradingProperties,
        )
        return KisStockTradingEngine(
            userId = user.id,
            positionManager = positionManager,
            client = client,
            strategies = strategies,
            tradingProperties = tradingProperties,
            marketDataStore = marketDataStore,
            marketCalendar = marketCalendar,
            liveEnabled = kisProperties.liveEnabled,
            positionStateService = stockPositionStateService,
        )
    }

    private fun hasKisKeys(user: UserEntity): Boolean =
        !user.kisAppKey.isNullOrBlank() && !user.kisAppSecret.isNullOrBlank() &&
            !user.kisCano.isNullOrBlank() && !user.kisAcntPrdtCd.isNullOrBlank()

    private suspend fun saveState(userId: Long, running: Boolean, strategy: String, symbols: List<String>) {
        try {
            val existing = botStateRepository.findByUserIdAndExchange(userId, EXCHANGE).awaitSingleOrNull()
            val symbolsStr = symbols.joinToString(",").ifEmpty { existing?.tickers ?: "" }
            if (existing != null) {
                botStateRepository.save(
                    existing.copy(running = running, strategy = strategy, tickers = symbolsStr, updatedAt = LocalDateTime.now()),
                ).awaitSingle()
            } else {
                botStateRepository.save(
                    BotStateEntity(userId = userId, exchange = EXCHANGE, running = running, strategy = strategy, tickers = symbolsStr),
                ).awaitSingle()
            }
        } catch (e: Exception) {
            log.error("Failed to save KIS bot state user={}: {}", userId, e.message)
        }
    }

    private companion object {
        const val EXCHANGE = "KIS"
    }
}
