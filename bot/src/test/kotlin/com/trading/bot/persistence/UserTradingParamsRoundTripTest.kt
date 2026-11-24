package com.trading.bot.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.TradingState
import com.trading.bot.domain.UserTradingParams
import com.trading.common.domain.Exchange
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * 사용자 매매 값 저장과 보유 포지션 스냅샷 덮기가 한 트랜잭션인지 실제 Postgres 로 본다. 덮은 뒤 상태에 실린 버전으로 다음 저장이
 * 충돌하지 않아야 하고(엔진이 계속 저장한다), 중간에 실패하면 아무것도 바뀌지 않아야 한다. 접속·skip 규약은 [TradingStateRoundTripTest] 와 같다.
 */
@DataR2dbcTest
class UserTradingParamsRoundTripTest {

    @Autowired
    private lateinit var repository: UserTradingParamsRepository

    @Autowired
    private lateinit var stateRepository: TradingStateRepository

    @Autowired
    private lateinit var transactionManager: ReactiveTransactionManager

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val states by lazy { TradingStateService(stateRepository, jacksonObjectMapper()) }
    private val service by lazy { UserTradingParamsService(repository, states, TransactionalOperator.create(transactionManager)) }

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
    private val entry = ExitParamsSnapshot(takeProfitPct = 5.0, maxLossPct = 5.0, trailingStopPct = 2.0, trailingArmPct = 3.0, maxHoldDays = 1)
    private val tighter = ExitParamsSnapshot(takeProfitPct = 4.0, maxLossPct = 2.0, trailingStopPct = 1.0, trailingArmPct = 1.5, maxHoldDays = 3)
    private val params = UserTradingParams(takeProfitPct = 4.0, maxLossPct = 2.0, maxHoldDays = 3)

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            "DELETE FROM trading_states WHERE user_id IN ($owned)",
            "DELETE FROM users WHERE username LIKE 'it-%-$runId'",
        )) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-params-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    /** 저장해 행 버전을 실은 상태. */
    private suspend fun saved(userId: Long, state: TradingState): TradingState = state.also { it.rowVersion = states.upsert(userId, it) }

    @Test
    fun `saving params rewrites held snapshots in one transaction and the engine can keep saving`() = runTest {
        val userId = insertUser()
        val held = saved(userId, TradingState("KRW-BTC", avgBuyPrice = 100.0, exitParams = entry))
        val flat = saved(userId, TradingState("KRW-ETH"))
        val selling = saved(userId, TradingState("KRW-XRP", exitParams = entry, pendingSellUuid = "sell-$runId"))
        assertThat(service.find(userId, Exchange.UPBIT)).isEqualTo(UserTradingParams.NONE)

        // 어느 포지션을 덮을지는 호출부(매니저)가 미리보기와 같은 규칙으로 고른다 — 여기서는 보유 행만 넘긴다.
        val rewritten = service.saveAndRewrite(userId, Exchange.UPBIT, params, listOf(held), tighter)

        assertThat(rewritten).containsExactly("KRW-BTC")
        assertThat(service.find(userId, Exchange.UPBIT)).isEqualTo(params)
        val loaded = states.loadStates(userId)
        assertThat(loaded.getValue("KRW-BTC").exitParams).isEqualTo(tighter)
        assertThat(loaded.getValue("KRW-ETH").exitParams).isNull()
        assertThat(loaded.getValue("KRW-XRP").exitParams).isEqualTo(entry)
        // 엔진은 메모리 상태로 계속 저장한다 — 버전이 실려 있지 않으면 여기서 충돌한다.
        held.peakPrice = 120.0
        held.rowVersion = states.upsert(userId, held)
        assertThat(states.loadStates(userId).getValue("KRW-BTC").peakPrice).isEqualTo(120.0)
    }

    // 한 행이라도 저장하지 못하면 값 행도 스냅샷도 그대로다 — 일부만 바뀐 상태가 남으면 옛 엔진과 새 값이 엇갈린다.
    @Test
    fun `a failed rewrite changes nothing and leaves the states as they were`() = runTest {
        val userId = insertUser()
        val held = saved(userId, TradingState("KRW-BTC", avgBuyPrice = 100.0, exitParams = entry))
        val stale = saved(userId, TradingState("KRW-ETH", avgBuyPrice = 10.0, exitParams = entry)).also { it.rowVersion = it.rowVersion!! - 1 }

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking { service.saveAndRewrite(userId, Exchange.UPBIT, params, listOf(held, stale), tighter) }
        }.isInstanceOf(TradingStateConflictException::class.java)

        assertThat(service.find(userId, Exchange.UPBIT)).isEqualTo(UserTradingParams.NONE)
        assertThat(states.loadStates(userId).values.map { it.exitParams }).containsOnly(entry)
        assertThat(held.exitParams).isEqualTo(entry)
        held.peakPrice = 110.0
        held.rowVersion = states.upsert(userId, held)
    }

    @Test
    fun `saving again updates the same row and null fields fall back to env`() = runTest {
        val userId = insertUser()
        service.saveAndRewrite(userId, Exchange.UPBIT, params, emptyList(), tighter)
        service.saveAndRewrite(userId, Exchange.UPBIT, UserTradingParams(kValue = 0.7), emptyList(), tighter)

        assertThat(service.find(userId, Exchange.UPBIT)).isEqualTo(UserTradingParams(kValue = 0.7))
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM user_trading_params WHERE user_id = $userId").map { r -> r.get("n", java.lang.Long::class.java) }.one().awaitSingle()).isEqualTo(1L)
    }
}
