package com.trading.bot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@Configuration
class PersistenceConfig {

    // R2DBC suspend 함수에서는 @Transactional 이 불안정해 TransactionalOperator 를 쓴다.
    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
