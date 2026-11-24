package com.trading.bot.security

import com.trading.bot.persistence.entity.UserEntity
import org.springframework.stereotype.Service

@Service
class UserSecretsService(
    private val secretsCrypto: SecretsCrypto,
) {
    fun encryptUpbitKeys(accessKey: String?, secretKey: String?): Pair<String?, String?> {
        return accessKey?.let(secretsCrypto::encrypt) to secretKey?.let(secretsCrypto::encrypt)
    }

    fun decryptUserSecrets(user: UserEntity): UserEntity {
        return user.copy(
            upbitAccessKey = secretsCrypto.decryptIfNeeded(user.upbitAccessKey),
            upbitSecretKey = secretsCrypto.decryptIfNeeded(user.upbitSecretKey),
        )
    }

    /** 알림을 보낼 때만 푼다 — 거래 키와 묶으면 webhook 하나가 깨져도 봇 기동·대사가 막힌다. 암호화 전 평문은 그대로 돌려준다. */
    fun discordWebhookOf(user: UserEntity?): String? = secretsCrypto.decryptIfNeeded(user?.discordWebhookUrl)
}
