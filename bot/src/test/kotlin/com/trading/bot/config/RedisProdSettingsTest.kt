package com.trading.bot.config

import java.io.File
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisHealthContributorAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.data.redis.RedisReactiveHealthContributorAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.yaml.snakeyaml.Yaml

/**
 * prod 에서 Redis 장애가 앱 health·기동·배포를 막지 않게 둔 설정을 고정한다 (#229).
 *
 * Redis 는 rate limit 전용이고 장애 때 in-memory 카운터로 강등된다([RateLimitFilter]) — 필수 의존이 아니다.
 * 그런데 앱 헬스체크와 배포 health 게이트는 전체 `/actuator/health` 를 보고, compose 는 앱 기동을 Redis 에 묶을 수 있다.
 * 둘 중 하나라도 Redis 를 보면 Redis 장애 동안 배포·자동 롤백이 실패한다.
 */
class RedisProdSettingsTest {

    // prod 프로필로 실제 설정 파일(application.yml + application-prod.yml)을 읽는다. prod 는 Redis 자동 구성을 켠다.
    private val prod = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=prod")
        .withConfiguration(
            AutoConfigurations.of(
                RedisAutoConfiguration::class.java,
                RedisReactiveAutoConfiguration::class.java,
                RedisReactiveHealthContributorAutoConfiguration::class.java,
                RedisHealthContributorAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `prod 는 Redis 를 앱 health 에 넣지 않는다`() {
        prod.run { assertThat(it).doesNotHaveBean("redisHealthContributor") }
        // 대조: 켜면 붙는다 — 위 단언이 자동 구성이 빠져서 통과한 게 아님을 보인다.
        prod.withPropertyValues("management.health.redis.enabled=true")
            .run { assertThat(it).hasBean("redisHealthContributor") }
    }

    @Test
    fun `prod 의 Redis 명령·연결 타임아웃은 1초다`() {
        // 설정이 없으면 명령은 60s(Lettuce), 연결은 10s 까지 기다린다.
        prod.run {
            val redis = it.getBean(RedisProperties::class.java)
            assertThat(redis.timeout).isEqualTo(Duration.ofSeconds(1))
            assertThat(redis.connectTimeout).isEqualTo(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `prod compose 는 앱 기동을 Redis 의 healthy 에 묶지 않는다`() {
        // service_healthy 면 Redis 가 unhealthy 인 동안 compose up 이 실패해 배포가 health·롤백 분기에 닿기 전에 끝난다.
        val compose = Yaml().load<Map<String, Any>>(repoFile("deploy/vultr/docker-compose.prod.yml").readText())
        val app = (compose["services"] as Map<*, *>)["app"] as Map<*, *>
        val dependsOn = app["depends_on"] as Map<*, *>
        assertThat((dependsOn["redis"] as Map<*, *>)["condition"]).isEqualTo("service_started")
        assertThat((dependsOn["postgres"] as Map<*, *>)["condition"]).isEqualTo("service_healthy")
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        assertTrue(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found!!
    }
}
