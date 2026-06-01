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
    private val jwtAuthFilter: JwtAuthFilter,
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
                    .pathMatchers("/api/leaderboard").permitAll()
                    .pathMatchers("/api/user/*/profile").permitAll()
                    .pathMatchers("/api/prices/**").permitAll()
                    .pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                    .anyExchange().authenticated()
            }
            .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    private fun corsConfig(): org.springframework.web.cors.reactive.CorsConfigurationSource {
        val config = org.springframework.web.cors.CorsConfiguration()
        // allowCredentials=true일 때 wildcard("*")는 보안 취약점 — 명시적 도메인만 허용.
        // SPA 는 same-origin 상대경로로 호출하지만 fetch 는 POST 에 Origin 헤더를 붙이므로,
        // Caddy 가 종단하는 실제 서비스 도메인을 넣지 않으면 CORS 가 403 으로 막는다.
        // APP_DOMAIN(예: 13-125-170-147.sslip.io) 이 있으면 https origin 추가, dev(미설정)면 localhost 만.
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
