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

    fun jwtKeyBytes(): ByteArray = deriveKey(resolveSecret(appProperties.jwtSecret, "app.jwt-secret"))

    fun encryptionKeyBytes(): ByteArray {
        val configured = appProperties.encryptionSecret.ifBlank { appProperties.jwtSecret }
        return deriveKey(resolveSecret(configured, "app.encryption-secret or app.jwt-secret"))
    }

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

    private fun deriveKey(secret: String): ByteArray {
        return MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(StandardCharsets.UTF_8))
    }
}
