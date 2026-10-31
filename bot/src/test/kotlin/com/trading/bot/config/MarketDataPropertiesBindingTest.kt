package com.trading.bot.config

import com.trading.bot.marketdata.MarketDataIngestionService
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME as SYSTEM_ENV
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder

/**
 * watchlist·워치독 기본값은 설정 클래스에만 있어 `application.yml` 에 키가 없어도 운영 env 가 바인딩돼야 한다.
 * Boot 는 이름이 `-systemEnvironment` 로 끝나는 `SystemEnvironmentPropertySource` 에만 env 매퍼를 써서
 * `withPropertyValues` 로는 흉내낼 수 없다.
 */
class MarketDataPropertiesBindingTest {

    @Configuration
    @EnableScheduling
    @EnableConfigurationProperties(WatchlistProperties::class, MarketDataWatchdogProperties::class)
    @Import(SchedulerConfig::class)
    class Config {
        @Bean
        fun ingestion(): MarketDataIngestionService = mockk(relaxed = true)
    }

    private fun runner(vararg env: Pair<String, String>) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)
        .withInitializer { ctx ->
            ctx.environment.propertySources.addFirst(SystemEnvironmentPropertySource("test-$SYSTEM_ENV", mapOf(*env)))
        }

    @Test
    fun `env overrides bind without yml and unset keys keep class defaults`() {
        runner(
            "WATCHLIST_TICKERS" to "KRW-BTC, krw-eth",
            "MARKETDATA_WATCHDOG_ENABLED" to "false",
            "MARKETDATA_WATCHDOG_STALE_MS" to "90000",
            "MARKETDATA_WATCHDOG_RESTART_BACKOFF_MS" to "2500",
        ).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean(WatchlistProperties::class.java).tickerList()).containsExactly("KRW-BTC", "KRW-ETH")
            val watchdog = ctx.getBean(MarketDataWatchdogProperties::class.java)
            assertThat(watchdog).isEqualTo(MarketDataWatchdogProperties(enabled = false, staleMs = 90_000, restartBackoffMs = 2_500))
        }
    }

    @Test
    fun `watchdog check is scheduled with the interval and initial delay from properties`() {
        runner("MARKETDATA_WATCHDOG_INTERVAL_MS" to "1234", "MARKETDATA_WATCHDOG_INITIAL_DELAY_MS" to "567000").run { ctx ->
            assertThat(ctx).hasNotFailed()
            val tasks = ctx.getBeansOfType(ScheduledTaskHolder::class.java).values
                .flatMap { holder -> holder.scheduledTasks.map { it.task } }
                .filterIsInstance<FixedDelayTask>()
            assertThat(tasks.map { it.intervalDuration to it.initialDelayDuration })
                .containsExactly(Duration.ofMillis(1234) to Duration.ofMillis(567_000))

            val ingestion = ctx.getBean(MarketDataIngestionService::class.java)
            tasks.single().runnable.run()
            verify(exactly = 1) { ingestion.checkTickerHealth() }
        }
    }
}
