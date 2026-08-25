package com.trading.bot.api

import com.trading.bot.auth.currentUserId
import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import com.trading.bot.kis.config.KisProperties
import com.trading.bot.kis.marketdata.KisMarketCalendar
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.kis.order.StockOrderValidationException
import com.trading.bot.kis.order.SubmitOrderCommand
import com.trading.bot.persistence.StockOrderIntentRepository
import com.trading.bot.persistence.UserRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * KIS 주식 수동 주문/조회. 등록된 사용자 KIS 키로 WAL([StockOrderService])을 통해 주문 — 주문유실 방지 경유.
 * 실주문은 서버 kis.live-enabled=true 일 때만 송신(아니면 DRY_RUN 기록). 자율 전략 루프는 후속(Phase 2).
 */
@RestController
@RequestMapping("/api/kis")
class KisTradeController(
    private val userRepository: UserRepository,
    private val kisClientFactory: KisClientFactory,
    private val stockOrderService: StockOrderService,
    private val stockOrderIntentRepository: StockOrderIntentRepository,
    private val requestValidators: RequestValidators,
    private val marketCalendar: KisMarketCalendar,
    private val kisProperties: KisProperties,
) {

    @PostMapping("/order")
    suspend fun placeOrder(@RequestBody req: KisOrderApiRequest): Map<String, Any?> {
        // 빠른 실패용 1차 게이트(실주문일 때만). 최종 불변식은 송신 직전 StockOrderService.validate 가 다시 본다 —
        // 여기서 통과해도 DB·시세 조회 사이에 장이 닫힐 수 있다.
        // dry-run 은 실송신이 없으므로 장외에도 허용한다(시뮬레이션 계약 유지).
        // ⚠️ 현 캘린더는 평일 09:00~15:30 하드코딩이라 공휴일·임시휴장·단축거래를 모른다(chk-holiday 연동은 후속).
        if (kisProperties.liveEnabled && !marketCalendar.isTradingNow()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "market is closed")
        }
        val userId = currentUserId()
        val user = userRepository.findById(userId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val cano = user.kisCano
        val acntPrdtCd = user.kisAcntPrdtCd
        if (user.kisAppKey.isNullOrBlank() || user.kisAppSecret.isNullOrBlank() ||
            cano.isNullOrBlank() || acntPrdtCd.isNullOrBlank()
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "KIS API keys not configured")
        }
        val symbol = requestValidators.normalizeKisSymbol(req.symbol)
        val side = parseSide(req.side)
        val orderType = parseOrderType(req.orderType)

        val client = kisClientFactory.forUser(user)
        val cmd = SubmitOrderCommand(
            userId, cano, acntPrdtCd, symbol, side, orderType, req.qty, req.price,
            strategy = "manual",
            reason = if (side == KisSide.SELL) SellReason.MANUAL.name else null,
        )
        val intent = try {
            stockOrderService.submit(client, cmd)
        } catch (e: StockOrderValidationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        return mapOf(
            "id" to intent.id,
            "status" to intent.status,
            "odno" to intent.odno,
            "client_ref" to intent.clientRef,
        )
    }

    @GetMapping("/orders")
    suspend fun listOrders(@RequestParam(required = false, defaultValue = "50") limit: Int): List<Map<String, Any?>> {
        val userId = currentUserId()
        val rows = stockOrderIntentRepository
            .findByUserId(userId, requestValidators.sanitizeTradeLimit(limit))
            .collectList().awaitSingle()
        return rows.map {
            mapOf(
                "id" to it.id,
                "symbol" to it.symbol,
                "side" to it.side,
                "order_type" to it.orderType,
                "qty" to it.qty,
                "executed_qty" to it.executedQty,
                "status" to it.status,
                "odno" to it.odno,
                "order_date" to it.orderDate,
                "created_at" to it.createdAt,
            )
        }
    }

    private fun parseSide(value: String): KisSide =
        when (value.trim().uppercase()) {
            "BUY" -> KisSide.BUY
            "SELL" -> KisSide.SELL
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "side must be BUY or SELL")
        }

    private fun parseOrderType(value: String): KisOrderType =
        when (value.trim().uppercase()) {
            "LIMIT" -> KisOrderType.LIMIT
            "MARKET" -> KisOrderType.MARKET
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "orderType must be LIMIT or MARKET")
        }
}

data class KisOrderApiRequest(
    val symbol: String,
    val side: String,
    val orderType: String = "LIMIT",
    val qty: Long,
    val price: Long? = null,
)
