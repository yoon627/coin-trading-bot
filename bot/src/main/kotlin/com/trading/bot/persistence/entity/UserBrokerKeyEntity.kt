package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 사용자·거래소별 증권사 키. appKey·appSecret·accountNo 는 암호문이다.
 * exchange 는 [com.trading.common.domain.Exchange.name] 중 KIS_PAPER·KIS 만 DB CHECK 가 받는다.
 * 쓰기는 [com.trading.bot.persistence.UserBrokerKeyRepository.upsert] 로만 한다(시각은 DB 가 채운다).
 */
@Table("user_broker_keys")
data class UserBrokerKeyEntity(
    @Id val id: Long? = null,
    val userId: Long,
    val exchange: String,
    val appKey: String,
    val appSecret: String,
    val accountNo: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    // 암호문도 로그·예외 메시지로 새지 않게 키 칸은 빼고 낸다.
    override fun toString(): String =
        "UserBrokerKeyEntity(id=$id, userId=$userId, exchange=$exchange, updatedAt=$updatedAt)"
}
