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

    /** KIS appKey/appSecret 모두 암호화(Upbit 와 동일 — 식별자도 함께 보호). */
    fun encryptKisKeys(appKey: String?, appSecret: String?): Pair<String?, String?> {
        return appKey?.let(secretsCrypto::encrypt) to appSecret?.let(secretsCrypto::encrypt)
    }

    fun decryptUserSecrets(user: UserEntity): UserEntity {
        return user.copy(
            upbitAccessKey = secretsCrypto.decryptIfNeeded(user.upbitAccessKey),
            upbitSecretKey = secretsCrypto.decryptIfNeeded(user.upbitSecretKey),
            kisAppKey = secretsCrypto.decryptIfNeeded(user.kisAppKey),
            kisAppSecret = secretsCrypto.decryptIfNeeded(user.kisAppSecret),
        )
    }
}
