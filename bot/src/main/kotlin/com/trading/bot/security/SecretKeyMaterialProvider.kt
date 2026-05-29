package com.trading.bot.security

import com.trading.bot.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Component
class SecretKeyMaterialProvider(
    private val appProperties: AppProperties,
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val runtimeFallbackSecret by lazy {
        ByteArray(32).also(SecureRandom()::nextBytes)
            .let { Base64.getEncoder().encodeToString(it) }
    }

    fun jwtKeyBytes(): ByteArray = deriveKey(resolveSecret(appProperties.jwtSecret, "app.jwt-secret"), "jwt")

    // 암호화 키는 JWT 키와 반드시 분리. 폴백(jwtSecret) 제거 — secret 1개 유출로
    // 토큰 위조 + 사용자 Upbit 키 복호화가 동시에 성립하던 결함 차단.
    fun encryptionKeyBytes(): ByteArray =
        deriveKey(resolveSecret(appProperties.encryptionSecret, "app.encryption-secret"), "encryption")

    fun jwtExpirationMs(): Long = appProperties.jwtExpirationMs

    private fun resolveSecret(value: String, propertyName: String): String {
        if (value.isNotBlank()) {
            return value
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw IllegalStateException("$propertyName must be configured in production")
        }
        log.warn("{} is not configured. Using an ephemeral runtime secret.", propertyName)
        return runtimeFallbackSecret
    }

    // label 로 도메인 분리: 같은 secret 이라도 용도별 키가 달라지도록 prefix 후 해시.
    private fun deriveKey(secret: String, label: String): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest("$label:$secret".toByteArray(StandardCharsets.UTF_8))
    }
}
