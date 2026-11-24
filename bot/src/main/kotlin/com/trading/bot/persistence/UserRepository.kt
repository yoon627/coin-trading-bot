package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserRepository : R2dbcRepository<UserEntity, Long> {
    fun findByUsername(username: String): Mono<UserEntity>

    // 행 전체를 save 하면 동시에 저장한 다른 설정이 옛 값으로 덮이므로 자기 컬럼만 바꾼다.
    @Modifying
    @Query("UPDATE users SET upbit_access_key = :accessKey, upbit_secret_key = :secretKey WHERE id = :id")
    fun updateUpbitKeys(id: Long, accessKey: String?, secretKey: String?): Mono<Int>

    @Modifying
    @Query("UPDATE users SET discord_webhook_url = :webhookUrl WHERE id = :id")
    fun updateDiscordWebhookUrl(id: Long, webhookUrl: String?): Mono<Int>

    @Query("SELECT * FROM users WHERE discord_webhook_url <> '' AND discord_webhook_url NOT LIKE 'enc:%'")
    fun findWithPlainWebhook(): Flux<UserEntity>

    @Query(
        "SELECT upbit_access_key FROM users WHERE upbit_access_key LIKE 'enc:%' " +
            "UNION ALL SELECT discord_webhook_url FROM users WHERE discord_webhook_url LIKE 'enc:%' LIMIT 1",
    )
    fun findAnyEncryptedSecret(): Mono<String>

    /** 읽은 뒤 사용자가 새로 저장했으면 0행 — 새 값을 옛 값의 암호문으로 덮지 않는다. */
    @Modifying
    @Query("UPDATE users SET discord_webhook_url = :encrypted WHERE id = :id AND discord_webhook_url = :plain")
    fun replaceWebhookIfUnchanged(id: Long, plain: String, encrypted: String): Mono<Int>
}
