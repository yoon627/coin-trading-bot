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
}
