package com.trading.bot.auth

import com.trading.bot.security.SecretKeyMaterialProvider
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtProvider(
    secretKeyMaterialProvider: SecretKeyMaterialProvider,
) {
    private val key = Keys.hmacShaKeyFor(secretKeyMaterialProvider.jwtKeyBytes())
    private val expirationMs = secretKeyMaterialProvider.jwtExpirationMs()

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
