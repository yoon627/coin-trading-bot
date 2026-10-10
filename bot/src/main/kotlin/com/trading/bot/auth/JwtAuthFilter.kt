package com.trading.bot.auth

import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

// 빈으로 두지 않는다 — WebFilter 빈은 WebFlux 가 전역 체인에도 올려 Security 체인과 함께 요청당 두 번 돈다.
class JwtAuthFilter(private val jwtProvider: JwtProvider) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val token = extractToken(exchange)
        if (token != null) {
            val userId = jwtProvider.validateAndGetUserId(token)
            if (userId != null) {
                val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }
        }
        return chain.filter(exchange)
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val header = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7)
        }
        // Also check cookie for frontend
        val cookie = exchange.request.cookies.getFirst("token")
        return cookie?.value
    }
}
