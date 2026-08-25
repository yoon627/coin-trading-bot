package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * KIS 주식 주문 write-ahead 로그(WAL). placeOrder *전에* SUBMITTING 으로 INSERT(별도 커밋)되어
 * 주문 직후 프로세스 사망 시에도 주문 추적이 유실되지 않게 한다(plan D4). 상태는 [com.trading.bot.kis.order.StockOrderStatus].
 */
@Table("stock_order_intent")
data class StockOrderIntentEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val clientRef: String,
    val exchange: String = "KIS",
    val accountNo: String,          // CANO-ACNT_PRDT_CD
    val symbol: String,             // PDNO
    val side: String,               // BUY / SELL
    val orderType: String,          // LIMIT / MARKET
    val qty: Long,
    val price: BigDecimal? = null,
    val strategy: String? = null,   // 엔진 전략명 또는 "manual" — 체결 기록 귀속의 근거
    val reason: String? = null,     // 매도 사유(SellReason.name). 매수는 null
    val status: String,
    val odno: String? = null,
    val orgNo: String? = null,
    val orderDate: String,          // KST YYYYMMDD
    val executedQty: Long = 0,
    val auditRecorded: Boolean = false,
    val failReason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
