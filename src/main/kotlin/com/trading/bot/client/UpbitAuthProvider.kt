package com.trading.bot.client

import com.trading.bot.config.UpbitProperties
import io.jsonwebtoken.Jwts
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Component
class UpbitAuthProvider(private val upbitProperties: UpbitProperties) {

    fun createToken(queryString: String? = null): String {
        val claims = mutableMapOf<String, Any>(
            "access_key" to upbitProperties.accessKey,
            "nonce" to UUID.randomUUID().toString(),
        )

        if (!queryString.isNullOrEmpty()) {
            val md = MessageDigest.getInstance("SHA-512")
            val queryHash = md.digest(queryString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            claims["query_hash"] = queryHash
            claims["query_hash_alg"] = "SHA512"
        }

        val key = SecretKeySpec(
            upbitProperties.secretKey.toByteArray(),
            "HmacSHA256"
        )

        return Jwts.builder()
            .claims(claims)
            .signWith(key)
            .compact()
    }

    fun authorizationHeader(queryString: String? = null): String {
        return "Bearer ${createToken(queryString)}"
    }
}
