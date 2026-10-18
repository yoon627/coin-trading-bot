package com.trading.bot.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val jwtProvider: JwtProvider,
    @Value("\${APP_DOMAIN:}") private val appDomain: String,
) {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfig()) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ ->
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    Mono.empty()
                }
            }
            .authorizeExchange {
                it.pathMatchers("/", "/index.html", "/app.html", "/login.html", "/tide-app/**").permitAll()
                    .pathMatchers("/api/auth/**").permitAll()
                    .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                    .anyExchange().authenticated()
            }
            .addFilterAt(JwtAuthFilter(jwtProvider), SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    private fun corsConfig(): org.springframework.web.cors.reactive.CorsConfigurationSource {
        val config = org.springframework.web.cors.CorsConfiguration()
        // allowCredentials 와 wildcard 는 함께 쓸 수 없어 도메인을 명시한다.
        // same-origin 이어도 fetch 가 POST 에 Origin 을 붙이므로 서비스 도메인을 넣지 않으면 403 이 난다.
        val origins = mutableListOf("http://localhost:3000", "http://localhost:8080")
        if (appDomain.isNotBlank()) origins += "https://$appDomain"
        config.allowedOrigins = origins
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "X-Requested-With")
        config.allowCredentials = true
        config.maxAge = 3600
        val source = org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
