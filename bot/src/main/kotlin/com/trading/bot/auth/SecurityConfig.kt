package com.trading.bot.auth

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
class SecurityConfig(private val jwtAuthFilter: JwtAuthFilter) {

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
        // allowCredentials=true일 때 wildcard("*")는 보안 취약점 — 명시적 도메인만 허용
        config.allowedOrigins = listOf(
            "http://localhost:3000",
            "http://localhost:8080",
        )
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "X-Requested-With")
        config.allowCredentials = true
        config.maxAge = 3600
        val source = org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
