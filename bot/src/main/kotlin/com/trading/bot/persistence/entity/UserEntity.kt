package com.trading.bot.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("users")
data class UserEntity(
    @Id
    val id: Long? = null,
    val username: String,
    val password: String,
    val upbitAccessKey: String? = null,
    val upbitSecretKey: String? = null,
    // KIS(한국투자증권) 자격증명 — kisAppSecret 은 암호화 저장(SecretsCrypto). (V15)
    val kisAppKey: String? = null,
    val kisAppSecret: String? = null,
    val kisCano: String? = null,          // 종합계좌번호
    val kisAcntPrdtCd: String? = null,    // 계좌상품코드
    val kisPaper: Boolean = true,         // KIS 모의투자 환경 여부(기본 안전)
    val publicProfile: Boolean = false,
    val publicStrategy: Boolean = false,
    val discordWebhookUrl: String? = null,
    val admin: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
