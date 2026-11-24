package com.trading.bot.persistence

import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
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

/** 체결 기록 대조(#341)가 쓰는 봇 상태 쿼리를 실제 Postgres 로 본다(기록된 주문번호 쿼리는 OrdersRoundTripTest). 접속 방식은 [TradingStateRoundTripTest] 와 같다. */
@DataR2dbcTest
class OrderReconcileQueriesRoundTripTest {

    @Autowired private lateinit var botStates: BotStateRepository
    @Autowired private lateinit var orders: OrderRowRepository
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
        for (sql in listOf("DELETE FROM orders WHERE user_id IN ($owned)", "DELETE FROM bot_state WHERE user_id IN ($owned)", "DELETE FROM users WHERE username LIKE 'it-%-$runId'")) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun insertOrder(userId: Long, key: String, status: String, ageMinutes: Long, touchedMinutesAgo: Long = ageMinutes, exchange: String = "UPBIT"): Long =
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, client_order_id, status, created_at, updated_at) " +
                "VALUES (:u, :e, 'KRW-BTC', 'BUY', :k, :s, now() - make_interval(mins => :age), now() - make_interval(mins => :touched)) RETURNING id",
        )
            .bind("u", userId).bind("e", exchange).bind("k", "ctb-$key-$runId").bind("s", status)
            .bind("age", ageMinutes.toInt()).bind("touched", touchedMinutesAgo.toInt())
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun statusOf(id: Long): String =
        databaseClient.sql("SELECT status FROM orders WHERE id = :id").bind("id", id).map { row -> row.get("status", String::class.java)!! }.one().awaitSingle()

    // 기준은 DB 의 now() 라 만든 시각을 1시간 양쪽에 직접 넣는다. 미해결로 둔 줄은 updated_at 이 바뀌어 뒤로 간다.
    @Test
    fun `stale unfinished rows are older than an hour, still pending or open, and come least recently touched first`() = runTest {
        val userId = insertUser("st")
        val other = insertUser("st2")
        val older = insertOrder(userId, "a", "OPEN", ageMinutes = 300, touchedMinutesAgo = 10)
        val oldest = insertOrder(userId, "b", "PENDING", ageMinutes = 120, touchedMinutesAgo = 100)
        insertOrder(userId, "c", "PENDING", ageMinutes = 59)
        insertOrder(userId, "d", "FILLED", ageMinutes = 300)
        insertOrder(userId, "e", "PENDING", ageMinutes = 300, exchange = "KIS")
        insertOrder(other, "f", "PENDING", ageMinutes = 300)

        assertThat(orders.findStaleUnfinished(userId, "UPBIT", 50).collectList().awaitSingle().map { it.id }).containsExactly(oldest, older)
        assertThat(orders.findStaleUnfinished(userId, "UPBIT", 1).collectList().awaitSingle().map { it.id }).containsExactly(oldest)

        assertThat(orders.markUnresolved(oldest, "reconcile: exchange state wait").awaitSingle()).isEqualTo(oldest)
        assertThat(orders.findStaleUnfinished(userId, "UPBIT", 50).collectList().awaitSingle().map { it.id }).containsExactly(older, oldest)
    }

    @Test
    fun `closing by id keeps the earlier note and leaves rows another writer already finished`() = runTest {
        val userId = insertUser("cl")
        val open = insertOrder(userId, "a", "OPEN", ageMinutes = 120)
        databaseClient.sql("UPDATE orders SET note = 'engine note' WHERE id = :id").bind("id", open).fetch().rowsUpdated().awaitSingle()
        val done = insertOrder(userId, "b", "NOT_PLACED", ageMinutes = 120)

        assertThat(orders.closeStale(open, "CANCELLED", "cancel", "reconcile (was OPEN): cancel without a fill").awaitSingle()).isEqualTo(open)
        assertThat(orders.closeStale(done, "CANCELLED", "cancel", "x").awaitSingleOrNull()).isNull()
        assertThat(orders.markUnresolved(done, "x").awaitSingleOrNull()).isNull()

        val closed = orders.findById(open).awaitSingle()
        assertThat(closed.status).isEqualTo("CANCELLED")
        assertThat(closed.exchangeState).isEqualTo("cancel")
        assertThat(closed.note).isEqualTo("reconcile (was OPEN): cancel without a fill | engine note")
        assertThat(closed.completedAt).isNotNull()
        assertThat(statusOf(done)).isEqualTo("NOT_PLACED")
    }

    @Test
    fun `bot state rows are listed by exchange whether running or not`() = runTest {
        val userId = insertUser("bs")
        databaseClient.sql("INSERT INTO bot_state (user_id, exchange, running) VALUES (:u, 'UPBIT', false)").bind("u", userId).fetch().rowsUpdated().awaitSingle()

        assertThat(botStates.findByExchange("UPBIT").collectList().awaitSingle().map { it.userId }).contains(userId)
        assertThat(botStates.findByExchange("KIS").collectList().awaitSingle().map { it.userId }).doesNotContain(userId)
    }
}
