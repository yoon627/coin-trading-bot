package com.trading.bot.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.trading.bot.ci.RepoFiles
import com.trading.bot.domain.FeeBasis
import com.trading.bot.domain.FeeKind
import com.trading.bot.domain.FillSource
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.bot.engine.OrderLedger
import com.trading.bot.engine.TradeExecutionService
import com.trading.common.config.TradingProperties
import com.trading.common.domain.Exchange
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.time.ZoneOffset
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
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * 실제 Postgres 로 `orders` 표와 V41 의 옛 체결 옮기기를 본다. 접속 방식은 [TradingStateRoundTripTest] 와 같다.
 * 마이그레이션 시험은 실행마다 만든 스키마에서 JDBC 로 돈다 — 공유 스키마에서 돌리면 다른 시험의 행이 섞인다.
 */
@DataR2dbcTest
@ImportAutoConfiguration(JacksonAutoConfiguration::class)
class OrdersRoundTripTest {

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var tradeRecords: TradeRecordR2dbcRepository
    @Autowired private lateinit var executions: TradeExecutionRepository
    @Autowired private lateinit var outbox: TradeNotificationOutboxRepository
    @Autowired private lateinit var states: TradingStateRepository
    @Autowired private lateinit var orderRows: OrderRowRepository
    @Autowired private lateinit var transactionManager: ReactiveTransactionManager
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val stateService by lazy { TradingStateService(states, jacksonObjectMapper()) }
    private val service by lazy {
        TradeExecutionService(
            TradeRecordRepository(tradeRecords), executions, outbox, OrderLedger(orderRows),
            TransactionalOperator.create(transactionManager), TradingProperties(),
        )
    }

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

        private const val V41 = "bot/src/main/resources/db/migration/V41__orders.sql"
        private const val V42 = "bot/src/main/resources/db/migration/V42__orders_read.sql"
        private const val V43 = "bot/src/main/resources/db/migration/V43__orders_read_switch.sql"
        private const val MANUAL_CATCHUP = "deploy/vultr/sql/orders_catchup.sql"
    }

    private val runId = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun cleanUp() = runTest {
        val owned = "SELECT id FROM users WHERE username LIKE 'it-%-$runId'"
        for (sql in listOf(
            // 알림 대기열이 orders 를 가리키므로(V42) 먼저 지운다.
            "DELETE FROM trade_notification_outbox WHERE user_id IN ($owned)",
            "DELETE FROM orders WHERE user_id IN ($owned)",
            "DELETE FROM trade_executions WHERE user_id IN ($owned)",
            "DELETE FROM trade_records WHERE user_id IN ($owned)",
            "DELETE FROM trading_states WHERE user_id IN ($owned)",
            "DELETE FROM users WHERE username LIKE 'it-%-$runId'",
        )) {
            databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
        }
    }

    private suspend fun insertUser(label: String): Long =
        databaseClient.sql("INSERT INTO users (username, password) VALUES (:u, 'x') RETURNING id")
            .bind("u", "it-$label-$runId")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    private suspend fun count(table: String, userId: Long): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM $table WHERE user_id = :u").bind("u", userId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()

    private suspend fun orderRow(userId: Long): Map<String, Any?> =
        databaseClient.sql("SELECT * FROM orders WHERE user_id = :u").bind("u", userId).fetch().one().awaitSingle()

    private fun sellFill(userId: Long, market: String = "KRW-BTC") = TradeRecord(
        ticker = market, side = TradeSide.SELL, price = 50_000_000.0, volume = 0.002, totalAmount = 100_000.0,
        pnlPercent = 1.5, pnlAmount = 1_500.0, strategy = "combined", reason = "TAKE_PROFIT",
        exchangeOrderId = "s-$runId", userId = userId, fee = FeeBasis.Measured(50.0), orderAmount = 99_950.0,
        executedVwap = 49_975_000.0, orderVolume = 0.002, positionVolumeAfter = 0.0, bookAvgPrice = 49_000_000.0,
        clientOrderId = "ctb-$runId",
    )

    // --- 화면 읽기: FILLED 만, 사용자·거래소별, 체결 시각 내림차순(같으면 id), 쪽 나누기는 SQL ---

    /** 체결 시각이 [minute] 분인 orders 줄을 넣고 id 를 돌려준다. [minute] 이 null 이면 체결 시각이 없다. */
    private suspend fun filledRow(userId: Long, minute: Int?, exchange: String = "UPBIT", status: String = "FILLED", orderId: String? = null): Long =
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, status, exchange_order_id, decision_price, filled_volume, fill_source, fee_basis, completed_at) " +
                "VALUES (:u, :e, 'KRW-BTC', 'SELL', :s, :o, 100, 1, 'ORDER', 'MEASURED', " +
                "CASE WHEN CAST(:m AS int) IS NULL THEN NULL ELSE TIMESTAMPTZ '2026-11-23 00:00:00+00' + make_interval(mins => CAST(:m AS int)) END) RETURNING id",
        )
            .bind("u", userId).bind("e", exchange).bind("s", status)
            .let { if (orderId == null) it.bindNull("o", String::class.java) else it.bind("o", orderId) }
            .let { if (minute == null) it.bindNull("m", Integer::class.java) else it.bind("m", minute) }
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()

    @Test
    fun `the history page reads only this user's FILLED rows on this exchange, newest first, ties by id, rows without a time last`() = runTest {
        val userId = insertUser("page")
        val other = insertUser("page-other")
        val noTime = filledRow(userId, null)
        val older = filledRow(userId, 1)
        val tieA = filledRow(userId, 5)
        val tieB = filledRow(userId, 5)
        val newest = filledRow(userId, 9)
        filledRow(userId, 20, status = "OPEN")
        filledRow(userId, 30, exchange = "KIS_PAPER")
        filledRow(other, 40)

        val all = orderRows.findFilledPage(userId, "UPBIT", 100, 0).collectList().awaitSingle().map { it.id }
        assertThat(all).containsExactly(newest, tieB, tieA, older, noTime)
        assertThat(orderRows.countFilled(userId, "UPBIT").awaitSingle()).isEqualTo(5L)
        assertThat(orderRows.findFilledPage(userId, "UPBIT", 2, 2).collectList().awaitSingle().map { it.id }).containsExactly(tieA, older)
        assertThat(orderRows.findFilledPage(userId, "UPBIT", 2, 5).collectList().awaitSingle()).isEmpty()
        assertThat(orderRows.findFilledPage(userId, "KIS_PAPER", 100, 0).collectList().awaitSingle()).hasSize(1)
    }

    @Test
    fun `the round-trip source is the newest rows in ascending order, so a cut drops the oldest`() = runTest {
        val userId = insertUser("recent")
        val ids = (1..4).map { filledRow(userId, it) }

        assertThat(orderRows.findFilledRecentAscending(userId, "UPBIT", 3).collectList().awaitSingle().map { it.id })
            .containsExactlyElementsOf(ids.drop(1))
        assertThat(orderRows.findFilledRecentAscending(userId, "UPBIT", 10).collectList().awaitSingle().map { it.id })
            .containsExactlyElementsOf(ids)
    }

    // --- 매일 대조: 같은 거래소의 FILLED 줄에 있는 주문번호만 기록된 것이다 ---

    @Test
    fun `an order number counts as recorded only for a FILLED row on the same exchange`() = runTest {
        val userId = insertUser("recorded")
        filledRow(userId, 1, orderId = "u-filled-$runId")
        filledRow(userId, 2, orderId = "u-open-$runId", status = "OPEN")
        filledRow(userId, 3, orderId = "u-shared-$runId", exchange = "KIS_PAPER")

        val asked = listOf("u-filled-$runId", "u-open-$runId", "u-shared-$runId", "u-none-$runId")
        assertThat(orderRows.findRecordedOrderIds(userId, "UPBIT", asked).collectList().awaitSingle()).containsExactly("u-filled-$runId")
    }

    // --- 체결 기록 트랜잭션 안의 orders 쓰기 ---

    private suspend fun timesMatchRecord(userId: Long): List<Boolean> =
        databaseClient.sql(
            "SELECT o.completed_at = tr.created_at AS same FROM orders o JOIN trade_records tr ON tr.id = o.trade_record_id WHERE o.user_id = :u",
        ).bind("u", userId).map { r -> r.get("same", java.lang.Boolean::class.java)!!.booleanValue() }.all().collectList().awaitSingle()

    // 화면은 trade_records.created_at 을 보여 준다. orders 의 체결 시각이 그와 같아야 화면을 orders 로 바꿔도 시각이 그대로다.
    // 같은 LocalDateTime 을 같은 방식으로 넘겨야 마이크로초 반올림까지 같다(초 경계·날 경계의 나노초 포함).
    @Test
    fun `a fill's orders time equals its trade record time, including nanosecond rounding at a day boundary`() = runTest {
        val userId = insertUser("time")
        val times = listOf(
            java.time.LocalDateTime.of(2026, 11, 23, 23, 59, 59, 999_999_600),
            java.time.LocalDateTime.of(2026, 11, 24, 0, 0, 0, 500),
            java.time.LocalDateTime.of(2026, 11, 24, 9, 0, 0, 123_456_789),
        )
        times.forEachIndexed { i, at -> service.commitFill({}, sellFill(userId).copy(exchangeOrderId = "t-$runId-$i", clientOrderId = null, createdAt = at)) }

        assertThat(timesMatchRecord(userId)).containsExactly(true, true, true)
    }

    // 화면을 orders 로 바꿔도 같은 값이 나오는지 — 실제 체결 경로로 쓴 네 종류의 줄로 본다(운영 데이터는 TradeHistoryParityTest).
    @Test
    fun `fills written through the real path give the same screen values from orders as from trade records`() = runTest {
        val userId = insertUser("parity")
        val buy = TradeRecord(
            ticker = "KRW-BTC", side = TradeSide.BUY, price = 100.0, volume = 2.0, totalAmount = 200.0, pnlPercent = null, pnlAmount = null,
            strategy = "combined", exchangeOrderId = "pb-$runId", userId = userId, fee = FeeBasis.Measured(0.1), orderAmount = 50.0,
            executedVwap = 100.0, orderVolume = 0.5, positionVolumeAfter = 2.0, bookAvgPrice = 100.0, clientOrderId = "ctb-pb-$runId",
            createdAt = java.time.LocalDateTime.of(2026, 11, 23, 9, 0, 0, 111_111_111),
        )
        val balanceBuy = buy.copy(
            exchangeOrderId = "pbb-$runId", clientOrderId = null, fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null,
            orderVolume = null, price = 51_000_000.0, volume = 0.0011, totalAmount = 51_000_000.0 * 0.0011, positionVolumeAfter = 0.0011,
            bookAvgPrice = 51_000_000.0, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(1),
        )
        // 부분 체결 매도: 장부가 남는다.
        val sell = sellFill(userId).copy(
            exchangeOrderId = "ps-$runId", clientOrderId = "ctb-ps-$runId", positionVolumeAfter = 0.3, createdAt = buy.createdAt.plusMinutes(2),
        )
        val balanceSell = sellFill(userId).copy(
            exchangeOrderId = "psb-$runId", clientOrderId = null, fee = FeeBasis.Estimate, orderAmount = null, executedVwap = null,
            orderVolume = null, volume = 0.7, totalAmount = 50_000_000.0 * 0.7, fillSource = FillSource.BALANCE, createdAt = buy.createdAt.plusMinutes(3),
        )
        for (record in listOf(buy, balanceBuy, sell, balanceSell)) assertThat(service.commitFill({}, record)).isTrue()

        val result = TradeHistoryParity(TradeRecordRepository(tradeRecords), orderRows, objectMapper, ZoneId.systemDefault())
            .compare(userId, Exchange.UPBIT)

        assertThat(orderRows.countFilled(userId, "UPBIT").awaitSingle()).isEqualTo(4L)
        assertThat(result.responses).isEqualTo(2)
        assertThat(result.differences).isEmpty()
    }

    @Test
    fun `a balance-confirmed sell keeps its order-time volume and the notification row points at its orders row`() = runTest {
        val userId = insertUser("balsell")
        val record = sellFill(userId).copy(
            fee = FeeBasis.Estimate, orderAmount = null, executedVwap = null, orderVolume = null, clientOrderId = null,
            fillSource = FillSource.BALANCE, volume = 0.7,
        )

        assertThat(service.commitFill({}, record)).isTrue()

        val row = orderRow(userId)
        assertThat(row["requested_volume"]).isEqualTo(0.7)
        assertThat(row["filled_volume"]).isNull()
        val linked = databaseClient.sql("SELECT order_id FROM trade_notification_outbox WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("order_id", java.lang.Long::class.java)?.toLong() }.one().awaitSingle()
        assertThat(linked).isEqualTo(row["id"])
    }

    @Test
    fun `a committed fill leaves one FILLED orders row with this order's values, linked to its execution and record`() = runTest {
        val userId = insertUser("fill")

        assertThat(service.commitFill({ stateService.upsert(userId, TradingState("KRW-BTC")) }, sellFill(userId))).isTrue()

        val row = orderRow(userId)
        val executionId = databaseClient.sql("SELECT id FROM trade_executions WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        val recordId = databaseClient.sql("SELECT id FROM trade_records WHERE user_id = :u").bind("u", userId)
            .map { r -> r.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(row["status"]).isEqualTo("FILLED")
        assertThat(row["trade_execution_id"]).isEqualTo(executionId)
        assertThat(row["trade_record_id"]).isEqualTo(recordId)
        assertThat(row).containsEntry("exchange", "UPBIT").containsEntry("market", "KRW-BTC").containsEntry("side", "SELL")
            .containsEntry("exchange_order_id", "s-$runId").containsEntry("client_order_id", "ctb-$runId")
            .containsEntry("filled_volume", 0.002).containsEntry("filled_funds", 99_950.0).containsEntry("avg_fill_price", 49_975_000.0)
            .containsEntry("fee", 50.0).containsEntry("fee_basis", "MEASURED").containsEntry("fill_source", "ORDER")
            .containsEntry("decision_price", 50_000_000.0).containsEntry("requested_volume", null)
            .containsEntry("position_volume_after", 0.0).containsEntry("book_avg_price", 49_000_000.0)
            .containsEntry("pnl_percent", 1.5).containsEntry("pnl_amount", 1_500.0)
            .containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")
        assertThat(row["completed_at"]).isNotNull()
    }

    @Test
    fun `a fill whose state save conflicts leaves no orders row`() = runTest {
        val userId = insertUser("conflict")

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.commitFill({ throw TradingStateConflictException("KRW-BTC", "test") }, sellFill(userId))
            }
        }.isInstanceOf(TradingStateConflictException::class.java)

        assertThat(count("orders", userId)).isZero()
        assertThat(count("trade_executions", userId)).isZero()
    }

    // orders 쓰기의 SQL 오류는 잡지 않는다 — 잡아도 Postgres 가 트랜잭션을 중단해 뒤의 알림 행이 실패한다. 체결 전체가 되돌아가 pending 이 남는다.
    @Test
    fun `an SQL error in the orders write rolls back the whole fill`() = runTest {
        val userId = insertUser("sqlerror")
        val market = "KRW-FAIL$runId"
        databaseClient.sql(
            "CREATE FUNCTION it_fail_orders_$runId() RETURNS trigger AS \$\$ BEGIN " +
                "IF NEW.market = '$market' THEN RAISE EXCEPTION 'it orders failure'; END IF; RETURN NEW; END \$\$ LANGUAGE plpgsql",
        ).fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("CREATE TRIGGER it_fail_orders_$runId BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION it_fail_orders_$runId()")
            .fetch().rowsUpdated().awaitSingle()
        try {
            assertThatThrownBy {
                kotlinx.coroutines.runBlocking {
                    service.commitFill({ stateService.upsert(userId, TradingState(market)) }, sellFill(userId, market))
                }
            }.hasStackTraceContaining("it orders failure")
        } finally {
            databaseClient.sql("DROP TRIGGER it_fail_orders_$runId ON orders").fetch().rowsUpdated().awaitSingle()
            databaseClient.sql("DROP FUNCTION it_fail_orders_$runId()").fetch().rowsUpdated().awaitSingle()
        }

        for (table in listOf("orders", "trade_executions", "trade_records", "trade_notification_outbox", "trading_states")) {
            assertThat(count(table, userId)).describedAs(table).isZero()
        }
    }

    // trade_executions 의 중복 확인을 지난 체결인데 같은 주문 키의 줄이 이미 있으면 넣지 않고 ERROR 만 남긴다 — 체결 기록은 막지 않는다.
    @Test
    fun `a fill whose order key already has an orders row commits without a second row`() = runTest {
        val userId = insertUser("dupkey")
        databaseClient.sql(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status) VALUES (:u, 'UPBIT', 'KRW-BTC', 'SELL', :o, 'OPEN')",
        ).bind("u", userId).bind("o", "s-$runId").fetch().rowsUpdated().awaitSingle()

        assertThat(service.commitFill({}, sellFill(userId))).isTrue()

        assertThat(count("orders", userId)).isEqualTo(1)
        assertThat(orderRow(userId)["status"]).isEqualTo("OPEN")
        assertThat(count("trade_executions", userId)).isEqualTo(1)
        assertThat(count("trade_notification_outbox", userId)).isEqualTo(1)
    }

    // 코드의 enum 이름이 V41 의 CHECK 목록에 없으면 그 값의 체결마다 기록 트랜잭션이 실패해 pending 이 남는다. 모든 조합을 실제로 넣어 본다.
    @Test
    fun `every fill source and fee kind the code writes passes the orders CHECK constraints`() = runTest {
        val userId = insertUser("enums")
        var n = 0L
        for (source in FillSource.entries) {
            for (kind in FeeKind.entries) {
                n++
                val id = orderRows.insertFilled(
                    userId = userId, exchange = "UPBIT", market = "KRW-BTC", side = TradeSide.entries[(n % 2).toInt()].name,
                    clientOrderId = null, exchangeOrderId = "e-$runId-$n", decisionPrice = null, filledVolume = null, filledFunds = null,
                    avgFillPrice = null, fee = null, feeBasis = kind.name, positionVolumeAfter = null, bookAvgPrice = null,
                    pnlPercent = null, pnlAmount = null, reason = null, strategy = null, fillSource = source.name,
                    tradeExecutionId = -n, tradeRecordId = null, requestedVolume = null, recordCreatedAt = java.time.LocalDateTime.now(),
                ).awaitSingle()
                assertThat(id).isNotNull()
            }
        }
        assertThat(count("orders", userId)).isEqualTo(n)
    }

    @Test
    fun `a fill confirmed from the balance leaves a row with unknown fee and volume marked as balance`() = runTest {
        val userId = insertUser("balance")
        val record = sellFill(userId).copy(
            fee = FeeBasis.Unrecorded, orderAmount = null, executedVwap = null, orderVolume = null, clientOrderId = null,
            fillSource = FillSource.BALANCE,
        )

        assertThat(service.commitFill({}, record)).isTrue()

        assertThat(orderRow(userId)).containsEntry("fee", null).containsEntry("fee_basis", "UNRECORDED")
            .containsEntry("fill_source", "BALANCE").containsEntry("filled_volume", null).containsEntry("filled_funds", null)
            .containsEntry("avg_fill_price", null).containsEntry("client_order_id", null)
    }

    // --- V41: 옛 체결 옮기기 ---

    @Test
    fun `V41 moves every trade execution into orders, takes order amount and id from its paired trade record, and marks legacy buys`() =
        withScratchSchema("v41") { schema ->
            migrate(schema, "40")
            val ids = inSchema(schema) { conn -> seedLegacyTrades(conn) }

            migrate(schema, "41")
            inSchema(schema) { conn ->
                val rows = conn.rows(
                    "SELECT trade_execution_id, trade_record_id, side, status, fill_source, exchange_order_id, filled_volume, filled_funds, " +
                        "avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price, decision_price, requested_volume, " +
                        "pnl_percent, reason, strategy, client_order_id FROM orders ORDER BY trade_execution_id",
                )
                assertThat(rows.map { it["trade_execution_id"] }).containsExactlyElementsOf(ids.executions.map { it.toString() })
                assertThat(rows.map { it["status"] }.toSet()).containsExactly("FILLED")
                assertThat(rows.map { it["client_order_id"] }.toSet()).containsExactly(null)

                val snapshot = rows.single { it["trade_execution_id"] == ids.snapshotBuy.toString() }
                assertThat(snapshot).containsEntry("fill_source", "BACKFILL_SNAPSHOT")
                    .containsEntry("trade_record_id", ids.snapshotBuyRecord.toString())
                    .containsEntry("exchange_order_id", "o-snap")
                    .containsEntry("position_volume_after", "2").containsEntry("book_avg_price", "100")
                    .containsEntry("filled_volume", null).containsEntry("filled_funds", "150")
                    .containsEntry("fee", "0.075").containsEntry("fee_basis", "LEGACY")

                val manual = rows.single { it["trade_execution_id"] == ids.manualBuy.toString() }
                assertThat(manual).containsEntry("fill_source", "BACKFILL_MANUAL")
                    .containsEntry("filled_volume", "1").containsEntry("filled_funds", "90")
                    .containsEntry("position_volume_after", null).containsEntry("fee", null).containsEntry("exchange_order_id", null)

                val sold = rows.single { it["trade_execution_id"] == ids.sellWithAmount.toString() }
                assertThat(sold).containsEntry("fill_source", "BACKFILL")
                    .containsEntry("filled_volume", "2").containsEntry("requested_volume", null)
                    .containsEntry("decision_price", "110").containsEntry("filled_funds", "218").containsEntry("avg_fill_price", "109")
                    .containsEntry("pnl_percent", "9.5").containsEntry("reason", "TAKE_PROFIT").containsEntry("strategy", "combined")

                val soldOld = rows.single { it["trade_execution_id"] == ids.sellWithoutAmount.toString() }
                assertThat(soldOld).containsEntry("filled_funds", null).containsEntry("avg_fill_price", null)

                // trade_executions 만 있는 정정 줄은 옮기고, trade_records 만 있는 줄은 옮기지 않는다.
                assertThat(rows.single { it["trade_execution_id"] == ids.correctionOnly.toString() }).containsEntry("trade_record_id", null)
                assertThat(rows.map { it["trade_record_id"] }).doesNotContain(ids.recordOnly.toString())

                // 값이 같은 두 쌍은 id 순서대로 짝짓는다.
                assertThat(ids.twinExecutions.map { te -> rows.single { it["trade_execution_id"] == te.toString() }["trade_record_id"] })
                    .containsExactlyElementsOf(ids.twinRecords.map { it.toString() })

                // 같은 옮기기를 다시 돌려도 늘지 않는다(2-2 의 따라잡기가 이 부분을 다시 쓴다).
                conn.update(backfillSql())
                assertThat(conn.strings("SELECT count(*)::text FROM orders")).containsExactly(ids.executions.size.toString())
            }
        }

    @Test
    fun `the V41 down sql refuses unless V41 is last, then drops the table and its history row`() = withScratchSchema("v41down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V41_down.sql").readText()
        migrate(schema, "40")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V41 이 적용돼 있지 않다")

        migrate(schema, "41")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly(null)
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '41'")).isEmpty()
        }
        migrate(schema, "41")
        inSchema(schema) { conn -> assertThat(conn.strings("SELECT to_regclass('orders')::text")).containsExactly("orders") }
    }

    // --- V42: 읽기 전환 준비 — 시각·값 맞추기, 빠진 체결 따라잡기, 알림 대기열 연결 ---

    @Test
    fun `V42 catches up fills written without an orders row, aligns times and missing values with trade records, and links notifications`() =
        withScratchSchema("v42") { schema ->
            migrate(schema, "40")
            val legacy = inSchema(schema) { conn -> seedLegacyTrades(conn) }
            migrate(schema, "41")
            val ids = inSchema(schema) { conn -> seedAfterV41(conn) }

            migrate(schema, "42")
            inSchema(schema) { conn -> assertCaughtUp(conn, legacy, ids) }
        }

    // --- V43: 읽기 전환 직전의 따라잡기 다시 돌리기 ---

    @Test
    fun `V43 catches up fills an image without orders wrote after V42, and the manual catch-up file changes nothing after it`() =
        withScratchSchema("v43") { schema ->
            migrate(schema, "40")
            val legacy = inSchema(schema) { conn -> seedLegacyTrades(conn) }
            migrate(schema, "42")
            // V42 아래 이미지로 손으로 롤백한 동안의 쓰기.
            val ids = inSchema(schema) { conn -> seedAfterV41(conn) }

            migrate(schema, "43")
            inSchema(schema) { conn ->
                assertCaughtUp(conn, legacy, ids)
                // 손 따라잡기 파일은 운영 JVM 과 같은 UTC 로 시각을 바꾼다 — Flyway(JVM 시간대)와 같을 때만 비교가 뜻이 있다.
                assumeTrue(ZoneId.systemDefault().normalized() == ZoneOffset.UTC, "JVM 시간대가 UTC 가 아니다")
                val before = conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id")
                conn.update(RepoFiles.file(MANUAL_CATCHUP).readText())
                assertThat(conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id"))
                    .isEqualTo(before)
            }
        }

    @Test
    fun `V42, V43 and the manual catch-up file run the same catch-up, and V43 and the manual file report the same counts`() {
        val blocks = listOf(V42, V43, MANUAL_CATCHUP).map { marked(it, "catchup") }
        assertThat(blocks[0]).contains("INSERT INTO orders")
        assertThat(blocks).containsOnly(blocks[0])
        assertThat(marked(MANUAL_CATCHUP, "report")).isEqualTo(marked(V43, "report").replace("'V43 orders:", "'orders catchup:"))
    }

    @Test
    fun `the V43 report counts filled rows without a time, without a trade record, or with empty history columns`() =
        withScratchSchema("v43report") { schema ->
            migrate(schema, "43")
            inSchema(schema) { conn ->
                conn.update("INSERT INTO users (username, password) VALUES ('it-report-$runId', 'x')")
                val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-report-$runId'").single()!!.toLong()
                conn.update(
                    "INSERT INTO orders (user_id, exchange, market, side, status, decision_price, filled_volume, fill_source, fee_basis) VALUES " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'FILLED', 100, 1, 'ORDER', 'MEASURED'), " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'OPEN', NULL, NULL, 'ORDER', 'MEASURED')",
                )
                conn.update(
                    "INSERT INTO orders (user_id, exchange, market, side, status, book_avg_price, fill_source, fee_basis, completed_at) VALUES " +
                        "($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'FILLED', 100, 'ORDER', 'MEASURED', now())",
                )

                assertThat(conn.notices(marked(V43, "report"))).singleElement().asString()
                    .contains("filled without time=1,", "filled without record=2,", "incomplete history rows=2,")
            }
        }

    @Test
    fun `the V43 down sql refuses unless V43 is last, then removes only its history row`() = withScratchSchema("v43down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V43_down.sql").readText()
        migrate(schema, "42")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V43 이 적용돼 있지 않다")

        migrate(schema, "43")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT max(version::int)::text FROM flyway_schema_history WHERE version IS NOT NULL")).containsExactly("42")
        }
    }

    /** 옛 이미지가 orders 없이 쓴 체결은 따라잡고, 같은 거래소 주문번호 줄이 이미 있는 체결은 건너뛴다. 값·시각·알림 연결이 맞는다. */
    private fun assertCaughtUp(conn: Connection, legacy: LegacyIds, ids: AfterV41Ids) {
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE trade_execution_id = ${ids.lateExecution}")).containsExactly("1")
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE exchange_order_id = 'o-orph'")).containsExactly("1")
        assertThat(conn.strings("SELECT count(*)::text FROM orders WHERE trade_execution_id = ${ids.orphanExecution}")).containsExactly("0")

        // 연결된 모든 체결의 시각이 화면 기록과 같다.
        assertThat(
            conn.strings(
                "SELECT count(*)::text FROM orders o JOIN trade_records tr ON tr.id = o.trade_record_id " +
                    "WHERE o.completed_at IS DISTINCT FROM tr.created_at",
            ),
        ).containsExactly("0")
        assertThat(conn.strings("SELECT requested_volume::text FROM orders WHERE trade_execution_id = ${ids.balanceSellExecution}"))
            .containsExactly("0.7")
        assertThat(conn.strings("SELECT decision_price::text FROM orders WHERE trade_execution_id = ${legacy.manualBuy}"))
            .containsExactly("90")
        assertThat(
            conn.strings(
                "SELECT (n.order_id = o.id)::text FROM trade_notification_outbox n JOIN orders o ON o.trade_execution_id = n.trade_execution_id",
            ),
        ).containsExactly("true", "true")

        // 다시 돌려도 바뀌는 것이 없다.
        val before = conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id")
        conn.update(marked(V42, "catchup"))
        assertThat(conn.rows("SELECT * FROM orders ORDER BY id") to conn.rows("SELECT * FROM trade_notification_outbox ORDER BY id"))
            .isEqualTo(before)
    }

    @Test
    fun `the previous image's orders insert and an outbox row without order id still work on V42`() = withScratchSchema("v42compat") { schema ->
        migrate(schema, "42")
        inSchema(schema) { conn ->
            conn.update("INSERT INTO users (username, password) VALUES ('it-compat-$runId', 'x')")
            val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-compat-$runId'").single()!!.toLong()
            val te = conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, exchange_order_id) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', 'BUY', 1, 1, 1, 'o-compat') RETURNING id::text",
            ).single()!!.toLong()
            // 74fe47b4 의 OrderRowRepository.insertFilled 문(완료 시각 now()).
            conn.update(
                "INSERT INTO orders (user_id, exchange, market, side, client_order_id, exchange_order_id, status, decision_price, " +
                    "filled_volume, filled_funds, avg_fill_price, fee, fee_basis, position_volume_after, book_avg_price, " +
                    "pnl_percent, pnl_amount, reason, strategy, fill_source, trade_execution_id, trade_record_id, completed_at) VALUES (" +
                    "$userId, 'UPBIT', 'KRW-BTC', 'BUY', NULL, 'o-compat', 'FILLED', NULL, 1, 1, 1, NULL, 'UNRECORDED', 1, 1, " +
                    "NULL, NULL, NULL, 'combined', 'ORDER', $te, NULL, now()) ON CONFLICT DO NOTHING RETURNING id",
            )
            conn.update("INSERT INTO trade_notification_outbox (trade_execution_id, user_id, exchange, payload) VALUES ($te, $userId, 'UPBIT', '{}')")
            assertThat(conn.strings("SELECT count(*)::text FROM trade_notification_outbox WHERE order_id IS NULL")).containsExactly("1")
        }
    }

    @Test
    fun `the V42 down sql refuses unless V42 is last, then drops the notification link`() = withScratchSchema("v42down") { schema ->
        val downSql = RepoFiles.file("deploy/vultr/sql/V42_down.sql").readText()
        migrate(schema, "41")
        assertThatThrownBy { inSchema(schema) { it.update(downSql) } }.hasMessageContaining("V42 이 적용돼 있지 않다")

        migrate(schema, "42")
        inSchema(schema) { conn ->
            conn.update(downSql)
            assertThat(conn.strings("SELECT column_name FROM information_schema.columns WHERE table_schema = '$schema' AND column_name = 'order_id'")).isEmpty()
            assertThat(conn.strings("SELECT version FROM flyway_schema_history WHERE version = '42'")).isEmpty()
        }
    }

    private class AfterV41Ids(val lateExecution: Long, val balanceSellExecution: Long, val orphanExecution: Long)

    /**
     * V41 뒤에 생긴 체결: orders 를 모르는 옛 이미지가 쓴 것, 74fe47b4 가 now() 시각으로 쓴 것(주문 응답 매도와 수량 없는 잔고 매도),
     * 알림 대기열, 같은 거래소 주문번호의 orders 줄이 이미 있는 것.
     */
    private fun seedAfterV41(conn: Connection): AfterV41Ids {
        val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-legacy-$runId'").single()!!.toLong()

        fun fill(side: String, price: Double, volume: Double, orderAmount: Double?, orderId: String, createdAt: String): Pair<Long, Long> {
            val tr = conn.strings(
                "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount, strategy, order_amount, created_at) " +
                    "VALUES ($userId, 'KRW-BTC', '$side', $price, $volume, ${price * volume}, 'combined', $orderAmount, '$createdAt') RETURNING id::text",
            ).single()!!.toLong()
            val te = conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, strategy, fee, exchange_order_id) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', '$side', $price, $volume, ${price * volume}, 'combined', 0.1, '$orderId') RETURNING id::text",
            ).single()!!.toLong()
            return te to tr
        }

        val (late, _) = fill("SELL", 130.0, 1.0, 129.0, "o-late", "2026-11-23 10:00:00.123456")
        val (sold, soldRecord) = fill("SELL", 140.0, 2.0, 279.0, "o-new", "2026-11-23 11:00:00.654321")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, decision_price, filled_volume, filled_funds, " +
                "fill_source, fee_basis, trade_execution_id, trade_record_id, completed_at) VALUES " +
                "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'o-new', 'FILLED', 140, 2, 279, 'ORDER', 'MEASURED', $sold, $soldRecord, now())",
        )
        val (balance, balanceRecord) = fill("SELL", 150.0, 0.7, null, "o-bal", "2026-11-23 12:00:00.000001")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status, decision_price, fill_source, fee_basis, " +
                "trade_execution_id, trade_record_id, completed_at) VALUES " +
                "($userId, 'UPBIT', 'KRW-BTC', 'SELL', 'o-bal', 'FILLED', 150, 'BALANCE', 'ESTIMATED', $balance, $balanceRecord, now())",
        )
        for (te in listOf(sold, balance)) {
            conn.update("INSERT INTO trade_notification_outbox (trade_execution_id, user_id, exchange, payload) VALUES ($te, $userId, 'UPBIT', '{}')")
        }
        val (orphan, _) = fill("BUY", 60.0, 1.0, 60.0, "o-orph", "2026-11-23 13:00:00")
        conn.update(
            "INSERT INTO orders (user_id, exchange, market, side, exchange_order_id, status) VALUES ($userId, 'UPBIT', 'KRW-BTC', 'BUY', 'o-orph', 'OPEN')",
        )
        return AfterV41Ids(late, balance, orphan)
    }

    /** SQL 파일의 `-- <label>:begin`·`-- <label>:end` 표시 사이를 그대로 쓴다. */
    private fun marked(path: String, label: String): String {
        val text = RepoFiles.file(path).readText()
        check(text.contains("-- $label:begin") && text.contains("-- $label:end")) { "$path 에 $label 표시가 없다" }
        return text.substringAfter("-- $label:begin").substringBefore("-- $label:end")
    }

    private class LegacyIds(
        val snapshotBuy: Long,
        val snapshotBuyRecord: Long,
        val manualBuy: Long,
        val sellWithAmount: Long,
        val sellWithoutAmount: Long,
        val correctionOnly: Long,
        val recordOnly: Long,
        val twinExecutions: List<Long>,
        val twinRecords: List<Long>,
    ) {
        val executions get() = (listOf(snapshotBuy, manualBuy, sellWithAmount, sellWithoutAmount, correctionOnly) + twinExecutions).sorted()
    }

    /** 운영에 있던 모양의 옛 기록을 심는다. trade_records 와 trade_executions 는 같은 체결을 같은 값으로 두 번 적었다. */
    private fun seedLegacyTrades(conn: Connection): LegacyIds {
        conn.update("INSERT INTO users (username, password) VALUES ('it-legacy-$runId', 'x')")
        val userId = conn.strings("SELECT id::text FROM users WHERE username = 'it-legacy-$runId'").single()!!.toLong()

        fun record(side: String, price: Double, volume: Double, total: Double, strategy: String?, orderAmount: Double?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_records (user_id, ticker, side, price, volume, total_amount, strategy, order_amount, pnl_percent, reason) " +
                    "VALUES ($userId, 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $orderAmount, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        fun execution(side: String, price: Double, volume: Double, total: Double, strategy: String?, fee: Double, orderId: String?, pnl: Double? = null, reason: String? = null): Long =
            conn.strings(
                "INSERT INTO trade_executions (user_id, exchange, market, side, price, volume, total_amount, strategy, fee, exchange_order_id, pnl_percent, reason) " +
                    "VALUES ($userId, 'UPBIT', 'KRW-BTC', '$side', $price, $volume, $total, ${strategy?.let { "'$it'" }}, $fee, ${orderId?.let { "'$it'" }}, $pnl, ${reason?.let { "'$it'" }}) RETURNING id::text",
            ).single()!!.toLong()

        val snapRecord = record("BUY", 100.0, 2.0, 200.0, "combined", 150.0)
        val snap = execution("BUY", 100.0, 2.0, 200.0, "combined", 0.075, "o-snap")
        record("BUY", 90.0, 1.0, 90.0, "manual", null)
        val manual = execution("BUY", 90.0, 1.0, 90.0, "manual", 0.0, null)
        record("SELL", 110.0, 2.0, 220.0, "combined", 218.0, 9.5, "TAKE_PROFIT")
        val sold = execution("SELL", 110.0, 2.0, 220.0, "combined", 0.11, "o-sell", 9.5, "TAKE_PROFIT")
        record("SELL", 95.0, 1.0, 95.0, "combined", null, -5.2, "STOP_LOSS")
        val soldOld = execution("SELL", 95.0, 1.0, 95.0, "combined", 0.0, null, -5.2, "STOP_LOSS")
        val correction = execution("SELL", 120.0, 0.5, 60.0, "combined", 0.03, "o-fix")
        val recordOnly = record("BUY", 80.0, 3.0, 240.0, "combined", 240.0)
        val twinRecords = listOf(record("BUY", 70.0, 1.0, 70.0, "combined", 70.0), record("BUY", 70.0, 1.0, 70.0, "combined", 70.0))
        val twins = listOf(execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t1"), execution("BUY", 70.0, 1.0, 70.0, "combined", 0.035, "o-t2"))
        return LegacyIds(snap, snapRecord, manual, sold, soldOld, correction, recordOnly, twins, twinRecords)
    }

    /** V41 의 옛 체결 옮기기 부분. 마이그레이션 파일의 표시 사이를 그대로 쓴다. */
    private fun backfillSql(): String =
        RepoFiles.file(V41).readText().substringAfter("-- backfill:begin").substringBefore("-- backfill:end")

    private fun migrate(schema: String, target: String) {
        Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate()
    }

    private fun <T> inSchema(schema: String, block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().use { it.execute("SET search_path TO $schema") }
            block(conn)
        }

    private fun Connection.update(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    /** 문이 RAISE NOTICE 로 남긴 메시지들. */
    private fun Connection.notices(sql: String): List<String> =
        createStatement().use { st ->
            st.execute(sql)
            generateSequence(st.warnings) { it.nextWarning }.mapNotNull { it.message }.toList()
        }

    private fun Connection.strings(sql: String): List<String?> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun Connection.rows(sql: String): List<Map<String, String?>> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
            }
        }

    private fun withScratchSchema(label: String, block: (String) -> Unit) {
        val schema = "it_${label}_$runId".replace('-', '_')
        try {
            block(schema)
        } finally {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.update("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
