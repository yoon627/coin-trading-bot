package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserRepository : R2dbcRepository<UserEntity, Long> {
    fun findByUsername(username: String): Mono<UserEntity>
    fun findByAdminTrue(): Flux<UserEntity>

    // 설정 저장은 자기 컬럼만 바꾼다(#284) — 행 전체를 save 하면 동시에 저장한 다른 설정이 읽어 둔 옛 값으로 돌아간다.
    @Modifying
    @Query("UPDATE users SET upbit_access_key = :accessKey, upbit_secret_key = :secretKey WHERE id = :id")
    fun updateUpbitKeys(id: Long, accessKey: String?, secretKey: String?): Mono<Int>

    @Modifying
    @Query("UPDATE users SET discord_webhook_url = :webhookUrl WHERE id = :id")
    fun updateDiscordWebhookUrl(id: Long, webhookUrl: String?): Mono<Int>
}
