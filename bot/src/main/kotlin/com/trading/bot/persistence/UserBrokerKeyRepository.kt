package com.trading.bot.persistence

import com.trading.bot.persistence.entity.UserBrokerKeyEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserBrokerKeyRepository : R2dbcRepository<UserBrokerKeyEntity, Long> {
    fun findByUserIdAndExchange(userId: Long, exchange: String): Mono<UserBrokerKeyEntity>
    fun findByUserId(userId: Long): Flux<UserBrokerKeyEntity>

    // 같은 사용자의 두 저장이 겹쳐도 행이 하나로 남고 created_at 은 처음 값을 지킨다.
    @Modifying
    @Query(
        "INSERT INTO user_broker_keys (user_id, exchange, app_key, app_secret, account_no) " +
            "VALUES (:userId, :exchange, :appKey, :appSecret, :accountNo) " +
            "ON CONFLICT (user_id, exchange) DO UPDATE SET " +
            "app_key = EXCLUDED.app_key, app_secret = EXCLUDED.app_secret, account_no = EXCLUDED.account_no, updated_at = now()",
    )
    fun upsert(userId: Long, exchange: String, appKey: String, appSecret: String, accountNo: String): Mono<Int>

    @Modifying
    @Query("DELETE FROM user_broker_keys WHERE user_id = :userId AND exchange = :exchange")
    fun deleteByUserIdAndExchange(userId: Long, exchange: String): Mono<Int>
}
