package com.trading.bot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@Configuration
class PersistenceConfig {

    // R2DBC suspend 함수에선 @Transactional 이 불안정하므로 명시적 TransactionalOperator 사용.
    // ReactiveTransactionManager(R2dbcTransactionManager)는 Spring Boot 가 자동 구성.
    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
