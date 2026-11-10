package com.trading.bot.notification

import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.engine.TradeExecutionService
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradeNotificationOutboxRepository
import com.trading.bot.persistence.TradeRecordRepository
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.TradeExecutionEntity
import com.trading.common.config.TradingProperties
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

/** 커밋 뒤 깨우기가 실제 Spring 배선에서 발송기에 닿는지. 기본값 파라미터가 no-op 발행기로 남으면 알림이 5초 늦을 뿐 조용히 지나간다. */
class TradeNotificationWiringTest {

    class Wakeups {
        val received = mutableListOf<Any>()

        @EventListener(TradeNotificationQueued::class)
        fun on(event: TradeNotificationQueued) {
            received += event
        }
    }

    @Configuration
    class Config {
        @Bean fun tradeRecordRepository(): TradeRecordRepository = mockk(relaxed = true)

        @Bean fun tradeExecutionRepository(): TradeExecutionRepository = mockk {
            every { existsByUserIdAndExchangeOrderId(any(), any()) } returns Mono.just(false)
            every { save(any()) } answers { Mono.just(firstArg<TradeExecutionEntity>().copy(id = 1L)) }
        }

        @Bean fun outboxRepository(): TradeNotificationOutboxRepository = mockk {
            every { save(any()) } answers { Mono.just(firstArg()) }
        }

        @Bean fun transactionalOperator(): TransactionalOperator = mockk {
            every { transactional(any<Mono<Any>>()) } answers { firstArg() }
        }

        @Bean fun tradingProperties() = TradingProperties()

        @Bean fun userRepository(): UserRepository = mockk(relaxed = true)

        @Bean fun balances() = TradeBalanceLookup { _, _ -> null }

        @Bean fun discordNotifier(): DiscordNotifier = mockk(relaxed = true)

        @Bean fun wakeups() = Wakeups()
    }

    @Test
    fun `a committed fill wakes the dispatcher through the real event publisher`() {
        ApplicationContextRunner()
            .withUserConfiguration(Config::class.java)
            .withBean(TradeExecutionService::class.java)
            .withBean(TradeNotificationDispatcher::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val record = TradeRecord(
                    ticker = "KRW-BTC", side = TradeSide.BUY, price = 1.0, volume = 1.0, totalAmount = 1.0,
                    pnlPercent = null, pnlAmount = null, strategy = null, exchangeOrderId = "o-1", userId = 1L,
                    fee = FeeBasis.Estimate, orderAmount = null,
                )

                runBlocking { ctx.getBean(TradeExecutionService::class.java).commitFill({}, record) }

                assertThat(ctx.getBean(Wakeups::class.java).received).containsExactly(TradeNotificationQueued)
                assertThat(ctx).hasSingleBean(TradeNotificationDispatcher::class.java)
            }
    }
}
