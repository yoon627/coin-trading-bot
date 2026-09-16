package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.FillOutcome
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.engine.UserTradingManager
import com.trading.bot.persistence.UserRepository
import com.trading.bot.security.UserSecretsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/trade")
class ManualTradeController(
    private val userTradingManager: UserTradingManager,
    private val tradeExecutionService: TradeExecutionService,
    private val userRepository: UserRepository,
    private val requestValidators: RequestValidators,
    private val userSecretsService: UserSecretsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/sell")
    suspend fun manualSell(@RequestBody req: ManualSellRequest): Map<String, Any?> {
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (user.upbitAccessKey.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Upbit API keys not configured")
        }
        // 모순 입력 거부: 전량 매도와 수량 지정을 동시에 보내면 의도가 모호 → 자금 사고 방지.
        if (req.sellAll == true && req.volume != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify either sell_all or volume, not both")
        }
        val market = requestValidators.normalizeMarket(req.market)

        val client = userTradingManager.createUpbitClient(userSecretsService.decryptUserSecrets(user))
        // 손익은 포지션을 연 전략의 것이다 — 사람이 청산해도 그 전략에 귀속한다(#129). 사유(reason=MANUAL)는 별개로 남는다.
        val entryStrategy = userTradingManager.resolveEntryStrategy(userId, market)
        val strategy = entryStrategy ?: MANUAL_STRATEGY
        log.info("Manual sell attributed to strategy '{}' (entry known: {}): userId={}, market={}", strategy, entryStrategy != null, userId, market)

        val result = if (req.sellAll == true) {
            tradeExecutionService.executeSellAll(
                client = client,
                market = market,
                strategy = strategy,
                userId = userId,
                username = user.username,
                discordWebhookUrl = user.discordWebhookUrl,
            )
        } else {
            val volume = requestValidators.normalizeSellVolume(
                req.volume ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify volume or sell_all")
            )
            tradeExecutionService.executeSellVolume(
                client = client,
                market = market,
                sellVolume = volume,
                strategy = strategy,
                userId = userId,
                username = user.username,
                discordWebhookUrl = user.discordWebhookUrl,
            )
        }

        if (!result.success) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.error)
        }
        if (result.fill == FillOutcome.CONFIRMED) {
            clearEntryMetaIfSoldOut(client, userId, market)
        }
        // 주문 접수 성공. recorded=false 면 주문은 나갔으나 기록/알림 후처리가 실패한 상태 — 재주문 대신 경고로 노출.
        // fill=not_filled/unconfirmed 는 체결이 없거나 확인하지 못해 행을 남기지 않은 경우(#105).
        return mapOf(
            "status" to "success",
            "order_uuid" to (result.orderUuid ?: ""),
            "recorded" to result.recorded,
            "fill" to result.fill?.name?.lowercase(),
        )
    }

    /**
     * 잔량이 0 일 때만 durable 진입 메타를 비운다 — 부분 매도면 남은 포지션의 귀속을 잃는다. 잔량은 free+locked 로 본다:
     * 다른 매도 주문에 묶인 수량도 아직 보유다(phantom 판정·heldVolume 과 같은 규칙). 실패는 매도 응답을 막지 않는다.
     */
    private suspend fun clearEntryMetaIfSoldOut(client: UpbitClient, userId: Long, market: String) {
        val currency = market.substringAfter("-")
        try {
            val remaining = client.getAccounts().find { it.currency == currency }?.totalBalance() ?: 0.0
            if (remaining <= VOLUME_EPSILON) userTradingManager.clearDurableEntryMeta(userId, market)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Post-sell holdings check or entry-meta clear failed — entry meta kept: userId={}, market={}: {}", userId, market, e.message)
        }
    }

    private companion object {
        /** 진입 전략을 모를 때의 귀속 버킷. 과거 수동 매수 행(2026-09-16 이전)도 이 값을 쓴다. */
        const val MANUAL_STRATEGY = "manual"
        /** 코인 수량은 소수라 0 을 정확히 비교할 수 없다(TradeRoundTrip 과 같은 기준). */
        const val VOLUME_EPSILON = 1e-8
    }
}

data class ManualSellRequest(val market: String, val volume: String? = null, val sellAll: Boolean? = null)
