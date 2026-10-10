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

/**
 * JWT 검증은 Security 체인 안에서 요청당 한 번만 돈다(#293). auth 패키지를 실제 애노테이션 그대로 스캔해,
 * 필터가 전역 WebFilter 빈으로도 등록되는 구성을 잡는다.
 */
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

    private companion object {
        const val TOKEN = "t"
        const val USER_ID = 7L
    }
}
