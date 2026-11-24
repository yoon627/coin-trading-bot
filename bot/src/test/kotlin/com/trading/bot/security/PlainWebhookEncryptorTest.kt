package com.trading.bot.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.config.AppProperties
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.UserEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.env.MockEnvironment
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class PlainWebhookEncryptorTest {
    private fun keys(secret: String) = SecretKeyMaterialProvider(AppProperties(jwtSecret = "test-secret", encryptionSecret = secret), MockEnvironment())
    private val keys = keys("encrypt-secret")
    private val crypto = SecretsCrypto(keys)
    private val secrets = UserSecretsService(crypto)
    private val webhook = "https://discord.com/api/webhooks/1/user-token"
    private val other = "https://discord.com/api/webhooks/2/other"
    private val written = mutableMapOf<Long, Pair<String, String>>()
    private var existingSecret: String? = crypto.encrypt("upbit-access-key")
    private val users = mockk<UserRepository> {
        every { findWithPlainWebhook() } returns Flux.just(
            UserEntity(id = 1L, username = "a", password = "p", discordWebhookUrl = webhook),
            UserEntity(id = 2L, username = "b", password = "p", discordWebhookUrl = other),
        )
        every { findAnyEncryptedSecret() } answers { Mono.justOrEmpty(existingSecret) }
        every { replaceWebhookIfUnchanged(any(), any(), any()) } answers {
            written[firstArg()] = secondArg<String>() to thirdArg<String>()
            // 2번은 그사이 사용자가 새 값을 저장했다 — 조건이 맞지 않아 0행.
            Mono.just(if (firstArg<Long>() == 2L) 0 else 1)
        }
    }

    private val logger = LoggerFactory.getLogger(PlainWebhookEncryptor::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun attach() {
        logger.addAppender(logs)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(logs)
    }

    private fun run(keys: SecretKeyMaterialProvider = this.keys, secrets: UserSecretsService = this.secrets) =
        runBlocking { PlainWebhookEncryptor(users, secrets, keys).encryptRemaining() }

    private fun errors() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    // 평문 행만 고르는 조건은 SQL 에 있다(UserSettingsUpdateRoundTripTest). 여기서는 바꾸는 값을 본다.
    @Test
    fun `plain webhooks are replaced with ciphertext only where the value is unchanged`() {
        assertEquals(1, run())

        val (expected, stored) = written.getValue(1L)
        assertEquals(webhook, expected)
        assertEquals(webhook, secrets.discordWebhookOf(UserEntity(id = 1L, username = "a", password = "p", discordWebhookUrl = stored)))
        verify(exactly = 2) { users.replaceWebhookIfUnchanged(any(), any(), any()) }
    }

    // 한 행이 실패해도(예: 너무 긴 옛 값) 나머지 행은 바꾼다. 로그에는 사용자 id 와 예외 이름만 남긴다.
    @Test
    fun `a failing row is logged without its value and the others still go through`() {
        every { users.replaceWebhookIfUnchanged(2L, any(), any()) } returns Mono.error(IllegalStateException(other))
        every { users.replaceWebhookIfUnchanged(1L, any(), any()) } returns Mono.just(1)

        assertEquals(1, run())

        assertEquals(listOf("사용자 2 의 평문 Discord webhook 을 암호화하지 못했다 — 다음 기동 때 다시 한다: IllegalStateException"), errors())
    }

    // 키가 틀리면 기존 업비트 키도 못 풀고, 그 키로 바꾼 webhook 은 맞는 키로 되돌아와도 풀 수 없다.
    @Test
    fun `a key that cannot decrypt existing ciphertext leaves plain webhooks untouched`() {
        val wrong = keys("wrong-secret")

        assertEquals(0, run(keys = wrong, secrets = UserSecretsService(SecretsCrypto(wrong))))

        verify(exactly = 0) { users.replaceWebhookIfUnchanged(any(), any(), any()) }
        assertTrue(errors().single().contains("APP_ENCRYPTION_SECRET"))
    }

    // 암호화 키가 없으면(개발) 기동마다 새 임시 키라 재시작 뒤 풀 수 없다.
    @Test
    fun `an ephemeral key encrypts nothing`() {
        val ephemeral = keys("")

        assertEquals(0, run(keys = ephemeral, secrets = UserSecretsService(SecretsCrypto(ephemeral))))

        verify(exactly = 0) { users.findWithPlainWebhook() }
    }

    // 암호문이 아직 하나도 없으면 맞춰 볼 것이 없다 — 지금 키가 처음 쓰는 키다.
    @Test
    fun `with no ciphertext yet the current key is used`() {
        existingSecret = null

        assertEquals(1, run())
    }
}
