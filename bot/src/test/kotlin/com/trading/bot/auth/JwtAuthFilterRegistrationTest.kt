package com.trading.bot.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/** auth 패키지를 실제 애노테이션 그대로 스캔해 필터의 전역 WebFilter 중복 등록과 허용 경로 목록을 본다. */
class JwtAuthFilterRegistrationTest {

    @Configuration
    @EnableWebFlux
    @ComponentScan(
        basePackageClasses = [SecurityConfig::class],
        excludeFilters = [
            ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [AuthController::class, JwtProvider::class]),
        ],
    )
    class Config {
        @Bean
        fun jwtProvider(): JwtProvider = mockk { every { validateAndGetUserId(TOKEN) } returns USER_ID }

        @Bean
        fun principalProbe() = coRouter { GET("/probe") { ok().bodyValueAndAwait(currentUserId()) } }
    }

    @Test
    fun `a token request is verified once and still authenticates`() {
        ReactiveWebApplicationContextRunner().withUserConfiguration(Config::class.java).run { ctx ->
            assertThat(ctx).hasNotFailed()

            WebTestClient.bindToApplicationContext(ctx).build()
                .get().uri("/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $TOKEN")
                .exchange()
                .expectStatus().isOk
                .expectBody(Long::class.java).isEqualTo(USER_ID)

            val jwtProvider = ctx.getBean(JwtProvider::class.java)
            verify(exactly = 1) { jwtProvider.validateAndGetUserId(TOKEN) }
        }
    }

    @Test
    fun `only health and info are open among actuator paths without a token`() {
        // 인증 없이 열리는 것은 두 경로뿐이다. health 는 컨테이너 헬스체크가 쓴다.
        // 이 컨텍스트에는 actuator 가 없어 허용된 경로는 404, 막힌 경로는 401 이다.
        ReactiveWebApplicationContextRunner().withUserConfiguration(Config::class.java).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val client = WebTestClient.bindToApplicationContext(ctx).build()

            listOf("/actuator/health", "/actuator/info").forEach {
                client.get().uri(it).exchange().expectStatus().isNotFound
            }
            listOf("/actuator", "/actuator/env", "/actuator/heapdump", "/actuator/prometheus").forEach {
                client.get().uri(it).exchange().expectStatus().isUnauthorized
            }
        }
    }

    private companion object {
        const val TOKEN = "t"
        const val USER_ID = 7L
    }
}
