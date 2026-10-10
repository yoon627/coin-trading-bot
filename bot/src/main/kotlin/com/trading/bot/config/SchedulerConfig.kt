package com.trading.bot.config

import com.trading.bot.marketdata.MarketDataIngestionService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration

@Configuration
class SchedulerConfig(
    private val marketDataIngestionService: MarketDataIngestionService,
    private val watchdogProperties: MarketDataWatchdogProperties,
) : SchedulingConfigurer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("trading-scheduler-")
        scheduler.initialize()
        taskRegistrar.setTaskScheduler(scheduler)

        // @Scheduled placeholder 가 아니라 프로퍼티 빈 값으로 등록해야 주기 기본값이 설정 클래스 한 곳에만 있다.
        taskRegistrar.addFixedDelayTask(
            FixedDelayTask(
                marketDataIngestionService::checkTickerHealth,
                Duration.ofMillis(watchdogProperties.intervalMs),
                Duration.ofMillis(watchdogProperties.initialDelayMs),
            ),
        )
        // 운영 env 가 실제로 먹었는지는 이 줄로 본다(전달 계층이 여럿이라 목록 테스트만으로는 알 수 없다).
        log.info(
            "[watchdog] enabled={} staleMs={} intervalMs={} initialDelayMs={} restartBackoffMs={}",
            watchdogProperties.enabled, watchdogProperties.staleMs, watchdogProperties.intervalMs,
            watchdogProperties.initialDelayMs, watchdogProperties.restartBackoffMs,
        )
    }
}
