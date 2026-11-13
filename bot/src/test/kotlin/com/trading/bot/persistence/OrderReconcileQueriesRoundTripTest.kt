package com.trading.bot.persistence

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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 체결 기록 대조(#341)가 쓰는 쿼리를 실제 Postgres 로 본다 — `IN (:ids)` 컬렉션 바인딩은 이 코드에서 처음 쓴다. 접속 방식은 [TradingStateRoundTripTest] 와 같다. */
@DataR2dbcTest
class OrderReconcileQueriesRoundTripTest {

    @Autowired private lateinit var executions: TradeExecutionRepository
    @Autowired private lateinit var botStates: BotStateRepository
    @Autowired private lateinit var databaseClient: DatabaseClient

    companion object {
        private val host = System.getenv("TEST_DB_HOST")
        private val port = System.getenv("TEST_DB_PORT") ?: "5432"
        private val name = System.getenv("TEST_DB_NAME") ?: "trading"
        private val user = System.getenv("TEST_DB_USER") ?: "trading"
        private val password = System.getenv("TEST_DB_PASSWORD") ?: "trading"
        private val required = System.getenv("DB_TESTS_REQUIRED")?.toBoolean() ?: false

        private val available = host != null
        private val jdbcUrl = "jdbc:postgresql://$host:$port/$name"

        @JvmStatic
        @BeforeAll
        fun requireDatabase() {
            check(available || !required) {
                "DB_TESTS_REQUIRED=true 인데 TEST_DB_HOST 가 없다 — DB 통합테스트가 조용히 건너뛰어질 뻔했다."
            }
            assumeTrue(available, "TEST_DB_HOST 미설정 — DB 통합테스트 skip (scripts/run-db-tests.sh 로 실행)")
            Flyway.configure()
                .dataSource(jdbcUrl, user, password)
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
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf("DELETE FROM trade_executions WHERE user_id IN ($owned)", "DELETE FROM bot_state WHERE user_id IN ($owned)", "DELETE FROM users WHERE username LIKE 'it-%-$runId'")) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun execution(userId: Long, orderId: String?) {
        databaseClient.sql(
            "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, exchange_order_id) " +
                "VALUES (:u, 'UPBIT', 'KRW-BTC', 'SELL', 1, 1, 1, :o)",
        ).bind("u", userId).let { if (orderId == null) it.bindNull("o", String::class.java) else it.bind("o", orderId) }
            .fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `recorded order ids come back only for the given user and ids`() = runTest {
        val userId = insertUser("rec")
        val other = insertUser("other")
        execution(userId, "o-1")
        execution(userId, null)
        execution(other, "o-2")

        val found = executions.findRecordedOrderIds(userId, listOf("o-1", "o-2", "o-3")).collectList().awaitSingle()

        assertThat(found).containsExactly("o-1")
    }

    @Test
    fun `bot state rows are listed by exchange whether running or not`() = runTest {
        val userId = insertUser("bs")
        databaseClient.sql("INSERT INTO bot_state (user_id, exchange, running) VALUES (:u, 'UPBIT', false)").bind("u", userId).fetch().rowsUpdated().awaitSingle()

        assertThat(botStates.findByExchange("UPBIT").collectList().awaitSingle().map { it.userId }).contains(userId)
        assertThat(botStates.findByExchange("KIS").collectList().awaitSingle().map { it.userId }).doesNotContain(userId)
    }
}
