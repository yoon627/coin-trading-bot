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
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.yaml.snakeyaml.Yaml

/**
 * watchlist·워치독 설정의 기본값 정의처는 설정 클래스 하나다(#232) — `application.yml` 이 키를 정의하지 않아도
 * 운영 env(`WATCHLIST_TICKERS`·`MARKETDATA_WATCHDOG_*`)가 바인딩되고, 워치독 주기도 그 값으로 등록된다.
 * 환경변수는 `withPropertyValues` 로 흉내낼 수 없다 — Boot 는 이름이 `-systemEnvironment` 로 끝나는
 * `SystemEnvironmentPropertySource` 에만 운영과 같은 env 매퍼를 쓴다.
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
    fun `application yml does not define watchlist or watchdog keys`() {
        val yml: Map<*, *> = Yaml().load(ClassPathResource("application.yml").inputStream)
        assertThat(yml.keys).doesNotContain("watchlist")
        assertThat((yml["marketdata"] as? Map<*, *>)?.keys.orEmpty()).doesNotContain("watchdog")
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
