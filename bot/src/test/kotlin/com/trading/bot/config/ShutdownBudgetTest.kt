package com.trading.bot.config

import com.trading.bot.engine.UpbitExchangeRuntime
import com.trading.common.config.TradingProperties
import io.mockk.mockk
import java.io.File
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.core.io.FileSystemResource
import org.springframework.http.client.ReactorResourceFactory
import org.yaml.snakeyaml.Yaml
import reactor.netty.resources.LoopResources

/**
 * SIGTERM 부터 JVM 종료까지의 상한 합이 Docker `stop_grace_period` 안에 들어야 마지막 저장이 SIGKILL 로 잘리지 않는다.
 *
 * phase 는 높은 것부터 차례로 멈추고, `timeout-per-shutdown-phase` 는 비동기 `stop(Runnable)` 대기만 묶어 동기 `stop()` 인
 * 엔진 정지는 [com.trading.bot.engine.ExchangeRuntime.stopBudget] 이 묶는다. 합은 웹 요청 정리(MAX-1024) + Netty 서버
 * 닫기(MAX-2048) + 엔진 정지 + 공유 HTTP 자원 정리(ReactorResourceFactory, phase 0) + 빈 정리·JVM 종료 여유다.
 * 같은 phase 의 매니저는 차례로 멈추므로 매니저가 늘면 정지 예산을 모두 더한다(지금은 Upbit 하나).
 * Spring 이 관리하는 executor·scheduler 빈(MAX phase)이 정지 때 할 일이 있으면 `timeout-per-shutdown-phase` 를 더한다 —
 * 지금의 watchdog 스케줄러는 빈이 아니라 정지 단계에 들지 않는다.
 */
class ShutdownBudgetTest {

    private val compose: Map<*, *> = Yaml().load(repoFile(COMPOSE).readText())
    private val app = (compose["services"] as Map<*, *>)["app"] as Map<*, *>
    private val env: Map<String, String?> = (app["environment"] as List<*>).associate { entry ->
        val raw = entry.toString()
        raw.substringBefore('=') to raw.substringAfter('=', missingDelimiterValue = "").takeIf { '=' in raw }
    }
    private val runtime = UpbitExchangeRuntime(mockk(relaxed = true), TradingProperties(), null, null)

    @Test
    fun `정지 시간 상한 합이 stop_grace_period 안에 든다`() {
        val grace = gracePeriod()
        val total = webDrain() + NETTY_DISPOSE_NOW + runtime.stopBudget +
            Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_TIMEOUT) + MARGIN

        assertThat(total)
            .describedAs("정지 상한 합 ${total.seconds}s 가 stop_grace_period ${grace.seconds}s 를 넘으면 정지 저장 중에 SIGKILL 이 난다")
            .isLessThanOrEqualTo(grace)
    }

    @Test
    fun `엔진은 웹 서버가 멈춘 뒤, 공유 HTTP 자원을 닫기 전에 멈춘다`() {
        val phase = runtime.lifecyclePhase

        assertThat(phase)
            .describedAs("같은 phase 의 ReactorResourceFactory 와는 순서 계약이 없어, 엔진이 늦게 멈추면 주문 HTTP 가 끊긴다")
            .isGreaterThan(ReactorResourceFactory().phase)
        // 웹 서버 정지 lifecycle 은 package-private 이라 graceful 정리 phase 에서 1024 를 빼 쓴다.
        assertThat(phase).isLessThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024)
    }

    /** 이 테스트가 읽는 yml 값을 운영에서 다른 값이 덮으면 합이 맞아도 실제 정지 시간은 다르다. */
    @Test
    fun `컨테이너 설정이 정지 설정을 덮지 않는다`() {
        assertThat(env.keys.filter { it.startsWith("SPRING_LIFECYCLE_") || it.startsWith("SERVER_SHUTDOWN") || it in JVM_OPTION_ENVS })
            .isEmpty()
        assertThat(env["SPRING_PROFILES_ACTIVE"]).describedAs("이 테스트는 prod 프로파일만 읽는다").isEqualTo("prod")
        val javaOpts = env["JAVA_OPTS"]
        assertThat(javaOpts).describedAs("JAVA_OPTS 는 compose 에 값으로 적는다(.env 에서 넘기면 이 테스트가 읽지 못한다)").isNotNull()
        assertThat(OVERRIDING_SYSTEM_PROPERTIES.filter { it in javaOpts!! }).isEmpty()
    }

    private fun gracePeriod(): Duration {
        val raw = app["stop_grace_period"].toString()
        val seconds = GRACE_FORMAT.matchEntire(raw)?.groupValues?.get(1)
            ?: error("stop_grace_period 는 '<정수>s' 로 쓴다 (지금 '$raw') — 다른 형식은 이 테스트가 읽지 못한다")
        return Duration.ofSeconds(seconds.toLong())
    }

    /** Spring 과 같은 relaxed binding 으로 읽는다. prod 프로파일 값이 기본 값을 덮는다. */
    private fun webDrain(): Duration {
        val loader = YamlPropertySourceLoader()
        val sources = listOf(PROD_YML, BASE_YML).flatMap { loader.load(it, FileSystemResource(repoFile(it))) }
        return Binder(ConfigurationPropertySources.from(sources))
            .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration::class.java)
            .orElseThrow { IllegalStateException("spring.lifecycle.timeout-per-shutdown-phase 가 없다 — Boot 기본값(30s)에 기대지 말고 적어 둔다") }
    }

    private fun repoFile(relative: String): File {
        // 테스트 cwd 는 gradle 서브프로젝트(`bot/`)라 repo 루트까지 거슬러 올라간다.
        val found = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
        check(found != null) { "$relative 를 못 찾았다 (cwd=${File("").absolutePath})" }
        return found
    }

    private companion object {
        const val COMPOSE = "deploy/vultr/docker-compose.prod.yml"
        const val BASE_YML = "bot/src/main/resources/application.yml"
        const val PROD_YML = "bot/src/main/resources/application-prod.yml"
        val GRACE_FORMAT = Regex("""(\d+)s""")
        val JVM_OPTION_ENVS = setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "SPRING_APPLICATION_JSON")
        val OVERRIDING_SYSTEM_PROPERTIES = listOf("-Dspring.lifecycle", "-Dserver.shutdown", "reactor.netty.ioShutdown")

        /** reactor-netty 1.2.13 `DisposableChannel.disposeNow()` 의 기본 시한. 공개 상수가 없다. */
        val NETTY_DISPOSE_NOW: Duration = Duration.ofSeconds(3)

        /** 빈 정리(R2DBC 풀·@PreDestroy)와 JVM 종료. 실측 전 추정치다. */
        val MARGIN: Duration = Duration.ofSeconds(10)
    }
}
