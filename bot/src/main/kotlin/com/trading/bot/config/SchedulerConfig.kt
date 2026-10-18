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

        // 주기 기본값을 설정 클래스 한 곳에만 두려고 @Scheduled 대신 프로퍼티 값으로 등록한다.
        taskRegistrar.addFixedDelayTask(
            FixedDelayTask(
                marketDataIngestionService::checkTickerHealth,
                Duration.ofMillis(watchdogProperties.intervalMs),
                Duration.ofMillis(watchdogProperties.initialDelayMs),
            ),
        )
        // env 가 실제로 적용됐는지 기동 로그로 확인한다.
        log.info(
            "[watchdog] enabled={} staleMs={} intervalMs={} initialDelayMs={} restartBackoffMs={}",
            watchdogProperties.enabled, watchdogProperties.staleMs, watchdogProperties.intervalMs,
            watchdogProperties.initialDelayMs, watchdogProperties.restartBackoffMs,
        )
    }
}
