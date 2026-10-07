package com.trading.bot.client

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.security.MessageDigest
import java.util.UUID

class UpbitAuthProvider(
    private val accessKey: String,
    private val secretKey: String,
) {

    fun createToken(queryString: String? = null): String {
        val claims = mutableMapOf<String, Any>(
            "access_key" to accessKey,
            "nonce" to UUID.randomUUID().toString(),
        )

        if (!queryString.isNullOrEmpty()) {
            val md = MessageDigest.getInstance("SHA-512")
            val queryHash = md.digest(queryString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            claims["query_hash"] = queryHash
            claims["query_hash_alg"] = "SHA512"
        }

        val key = Keys.hmacShaKeyFor(secretKey.toByteArray())

        return Jwts.builder()
            .claims(claims)
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun authorizationHeader(queryString: String? = null): String {
        return "Bearer ${createToken(queryString)}"
    }
}
