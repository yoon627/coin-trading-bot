package com.trading.bot.kis.keys

import com.trading.bot.config.AppProperties
import com.trading.bot.kis.config.KisTradingProperties
import com.trading.bot.persistence.UserBrokerKeyRepository
import com.trading.bot.persistence.entity.UserBrokerKeyEntity
import com.trading.bot.security.SecretKeyMaterialProvider
import com.trading.bot.security.SecretsCrypto
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.springframework.mock.env.MockEnvironment
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

// 가짜 값이다. 원문이 새는지 볼 때 이 문자열을 찾는다.
internal const val FAKE_APP_KEY = "PSfakeAppKeyValue0000000000000000AB"
internal const val FAKE_APP_SECRET = "fakeAppSigValue+/0123456789abcdefghijklmnopqrstuvwxyzFAKE="
internal const val FAKE_ACCOUNT = "50123456-01"
internal val FAKE_RAW_VALUES = listOf(FAKE_APP_KEY, FAKE_APP_SECRET, "50123456")

internal fun testCrypto(material: String = "test-encryption-material"): SecretsCrypto =
    SecretsCrypto(SecretKeyMaterialProvider(AppProperties(jwtSecret = "test-jwt", encryptionSecret = material), MockEnvironment()))

/** upsert 한 행을 메모리에 들고 조회·삭제에 돌려준다. */
internal class InMemoryBrokerKeys {
    val rows = linkedMapOf<Pair<Long, String>, UserBrokerKeyEntity>()
    val repository: UserBrokerKeyRepository = mockk()

    init {
        every { repository.upsert(any(), any(), any(), any(), any()) } answers {
            val userId = arg<Long>(0)
            val exchange = arg<String>(1)
            val previous = rows[userId to exchange]
            rows[userId to exchange] = UserBrokerKeyEntity(
                id = previous?.id ?: (rows.size + 1L),
                userId = userId,
                exchange = exchange,
                appKey = arg(2),
                appSecret = arg(3),
                accountNo = arg(4),
                createdAt = previous?.createdAt ?: UPDATED_AT,
                updatedAt = UPDATED_AT,
            )
            Mono.just(1)
        }
        every { repository.findByUserIdAndExchange(any(), any()) } answers {
            Mono.justOrEmpty(rows[arg<Long>(0) to arg<String>(1)])
        }
        every { repository.findByUserId(any()) } answers {
            Flux.fromIterable(rows.values.filter { it.userId == arg<Long>(0) })
        }
        every { repository.deleteByUserIdAndExchange(any(), any()) } answers {
            Mono.just(if (rows.remove(arg<Long>(0) to arg<String>(1)) != null) 1 else 0)
        }
    }

    companion object {
        val UPDATED_AT: Instant = Instant.parse("2026-10-21T00:30:00Z")
    }
}

internal fun keyStore(
    keys: InMemoryBrokerKeys,
    liveEnabled: Boolean = false,
    crypto: SecretsCrypto = testCrypto(),
    dbTimeoutMs: Long = KisKeyStore.DB_TIMEOUT_MS,
): KisKeyStore = KisKeyStore(keys.repository, crypto, KisTradingProperties(enabled = true, liveEnabled = liveEnabled), dbTimeoutMs)
