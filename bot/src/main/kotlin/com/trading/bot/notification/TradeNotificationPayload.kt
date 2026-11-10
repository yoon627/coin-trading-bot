package com.trading.bot.notification

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.bot.domain.TradeRecord

/**
 * 체결 알림 embed 의 입력. outbox 에 JSON 으로 남아 다른 버전이 읽을 수 있으므로 판([v])을 붙이고 모르는 필드는 무시한다.
 * 체결 행에서 다시 만들 수 없는 값(orderAmount)이 있어 체결 행을 참조하지 않고 따로 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TradeNotificationPayload(
    val v: Int = VERSION,
    val ticker: String,
    val side: String,
    val price: Double,
    val totalAmount: Double,
    val orderAmount: Double? = null,
    val pnlPercent: Double? = null,
    val reason: String? = null,
    val strategy: String? = null,
    val exchangeOrderId: String? = null,
    /** embed timestamp 그대로(KST 시각, 시간대 표기 없음). */
    val createdAt: String,
) {
    companion object {
        const val VERSION = 1

        // NaN·무한대는 JSON 숫자가 아니라 읽을 때 깨지므로 비운다.
        private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

        fun of(record: TradeRecord) = TradeNotificationPayload(
            ticker = record.ticker,
            side = record.side.name,
            price = record.price.takeIf { it.isFinite() } ?: 0.0,
            totalAmount = record.totalAmount.takeIf { it.isFinite() } ?: 0.0,
            orderAmount = record.orderAmount.finiteOrNull(),
            pnlPercent = record.pnlPercent.finiteOrNull(),
            reason = record.reason,
            strategy = record.strategy,
            exchangeOrderId = record.exchangeOrderId,
            createdAt = record.createdAt.toString(),
        )

        // 쓰는 쪽과 읽는 쪽이 같은 형식이어야 해서 앱 전역 설정(snake_case 등)과 무관한 전용 매퍼를 쓴다.
        private val mapper = jacksonObjectMapper()

        /**
         * 체결 트랜잭션 안에 들어가므로 예외를 던지지 않는다. 직렬화가 실패하면 종목·방향만 담은 최소 payload 로 남긴다
         * — 알림 내용이 줄어도 체결이 막히는 것보다 낫다.
         */
        fun toJson(record: TradeRecord): String =
            runCatching { mapper.writeValueAsString(of(record)) }.getOrElse {
                val ticker = record.ticker.filter { it.isLetterOrDigit() || it == '-' }
                """{"v":$VERSION,"ticker":"$ticker","side":"${record.side.name}","price":0.0,"totalAmount":0.0,"createdAt":"${record.createdAt}"}"""
            }

        fun fromJson(json: String): TradeNotificationPayload = mapper.readValue(json)
    }
}

/** 체결 알림 이벤트가 커밋됐다는 신호. 발송기를 깨울 뿐이고 내용은 outbox 행에 있다. */
data object TradeNotificationQueued
