package com.trading.bot.persistence

import com.trading.bot.config.AppProperties
import com.trading.bot.security.SecretKeyMaterialProvider
import com.trading.bot.security.SecretsCrypto
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.mock.env.MockEnvironment
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 키 저장과 웹훅 저장이 자기 컬럼만 바꾸는지 실제 Postgres 로 본다. 행 전체를 다시 쓰면 동시에 저장한 다른 쪽 변경이
 * 옛 값으로 돌아간다. 접속·skip 규약은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class UserSettingsUpdateRoundTripTest {

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"
        private val required = System.getenv("DB_TESTS_REQUIRED")?.toBoolean() ?: false

        private val available = host != null

        @JvmStatic
        @BeforeAll
        fun requireDatabase() {
            check(available || !required) {
                "DB_TESTS_REQUIRED=true 인데 TEST_DB_HOST 가 없다 — DB 통합테스트가 조용히 건너뛰어질 뻔했다."
            }
            assumeTrue(available, "TEST_DB_HOST 미설정 — DB 통합테스트 skip (scripts/run-db-tests.sh 로 실행)")

            Flyway.configure()
                .dataSource("jdbc:postgresql://$host:$port/$name", user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        @JvmStatic
        @DynamicPropertySource
        fun connection(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://$host:$port/$name" }
            registry.add("spring.r2dbc.username") { user }
            registry.add("spring.r2dbc.password") { password }
            registry.add("spring.flyway.enabled") { "false" }
        }
    }

    private val runId = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun cleanUp() = runTest {
        databaseClient.sql("DELETE FROM users WHERE username LIKE 'it-%-$runId'").fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertUser(): Long =
        databaseClient.sql(
            "INSERT INTO users (username, password, upbit_access_key, upbit_secret_key, discord_webhook_url) " +
                "VALUES (:u, 'x', 'old-ak', 'old-sk', 'https://discord.com/api/webhooks/1/old') RETURNING id",
        )
            .bind("u", "it-settings-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    @Test
    fun `saving keys and saving the webhook each change only their own columns`() = runTest {
        val id = insertUser()

        assertThat(users.updateUpbitKeys(id, "new-ak", "new-sk").awaitSingle()).isEqualTo(1)
        assertThat(users.findById(id).awaitSingle().discordWebhookUrl).isEqualTo("https://discord.com/api/webhooks/1/old")
        assertThat(users.updateDiscordWebhookUrl(id, "https://discord.com/api/webhooks/1/new").awaitSingle()).isEqualTo(1)

        val row = users.findById(id).awaitSingle()
        assertThat(row.upbitAccessKey).isEqualTo("new-ak")
        assertThat(row.upbitSecretKey).isEqualTo("new-sk")
        assertThat(row.discordWebhookUrl).isEqualTo("https://discord.com/api/webhooks/1/new")
        assertThat(row.password).isEqualTo("x")
    }

    @Test
    fun `clearing the webhook stores null and leaves the keys`() = runTest {
        val id = insertUser()

        users.updateDiscordWebhookUrl(id, null).awaitSingle()

        val row = users.findById(id).awaitSingle()
        assertThat(row.discordWebhookUrl).isNull()
        assertThat(row.upbitAccessKey).isEqualTo("old-ak")
    }

    private suspend fun insertUser(suffix: String, webhook: String?): Long =
        databaseClient.sql("INSERT INTO users (username, password, discord_webhook_url) VALUES (:u, 'x', :w) RETURNING id")
            .bind("u", "it-$suffix-$runId")
            .let { if (webhook == null) it.bindNull("w", String::class.java) else it.bind("w", webhook) }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    // 기동 때의 평문 암호화는 평문 행만 고르고, 그사이 사용자가 새로 저장한 값은 덮지 않는다.
    @Test
    fun `plain webhooks are found and replaced only while unchanged`() = runTest {
        val plain = insertUser("plain", "https://discord.com/api/webhooks/1/plain")
        val encrypted = insertUser("enc", "enc:abc")
        val none = insertUser("none", null)
        val blank = insertUser("blank", "")

        val found = users.findWithPlainWebhook().collectList().awaitSingle().mapNotNull { it.id }
        assertThat(found).contains(plain).doesNotContain(encrypted, none, blank)

        assertThat(users.replaceWebhookIfUnchanged(plain, "https://discord.com/api/webhooks/1/other", "enc:x").awaitSingle()).isEqualTo(0)
        assertThat(users.replaceWebhookIfUnchanged(plain, "https://discord.com/api/webhooks/1/plain", "enc:new").awaitSingle()).isEqualTo(1)
        assertThat(users.findById(plain).awaitSingle().discordWebhookUrl).isEqualTo("enc:new")
        assertThat(users.findAnyEncryptedSecret().awaitSingle()).startsWith("enc:")
    }

    // 저장 상한(UTF-8 300 바이트)의 webhook 을 실제 암호화하면 컬럼(VARCHAR(500))에 든다.
    @Test
    fun `the longest storable webhook fits the column once encrypted`() = runTest {
        val id = insertUser("long", null)
        val base = "https://discord.com/api/webhooks/1/"
        val crypto = SecretsCrypto(SecretKeyMaterialProvider(AppProperties(encryptionSecret = "encrypt-secret"), MockEnvironment()))

        assertThat(users.updateDiscordWebhookUrl(id, crypto.encrypt(base + "a".repeat(300 - base.length))).awaitSingle()).isEqualTo(1)
    }
}
