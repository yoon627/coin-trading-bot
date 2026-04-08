package com.trading.bot.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtProvider(
    @Value("\${app.jwt-secret:default-secret-key-that-is-at-least-32-bytes-long!!}")
    private val secret: String,
    @Value("\${app.jwt-expiration-ms:86400000}")
    private val expirationMs: Long,
) {
    private val key by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    fun generateToken(userId: Long, username: String): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("username", username)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun validateAndGetUserId(token: String): Long? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
            claims.subject.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
