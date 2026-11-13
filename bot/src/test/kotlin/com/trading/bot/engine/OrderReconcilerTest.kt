package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Order
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.BotStateRepository
import com.trading.bot.persistence.TradeExecutionRepository
import com.trading.bot.persistence.TradingStateService
import com.trading.bot.persistence.UserRepository
import com.trading.bot.persistence.entity.BotStateEntity
import com.trading.bot.persistence.entity.UserEntity
import com.trading.bot.security.UserSecretsService
import com.trading.common.domain.Exchange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class OrderReconcilerTest {

    private val now = Instant.parse("2026-11-13T01:00:00Z")
    private val botStates = mockk<BotStateRepository>()
    private val users = mockk<UserRepository>()
    private val secrets = mockk<UserSecretsService>()
    private val client = mockk<UpbitClient>()
    private val states = mockk<TradingStateService>()
    private val executions = mockk<TradeExecutionRepository>()
    private val reconciler = OrderReconciler(botStates, users, secrets, { client }, states, executions, Clock.fixed(now, ZoneOffset.UTC))

    private val logger = LoggerFactory.getLogger(OrderReconciler::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        logger.addAppender(logs)
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 1L, exchange = "UPBIT", running = false))
        val user = UserEntity(id = 1L, username = "u", password = "x", upbitAccessKey = "enc-a", upbitSecretKey = "enc-s")
        every { users.findById(1L) } returns Mono.just(user)
        every { secrets.decryptUserSecrets(any()) } answers { firstArg() }
        coEvery { states.loadStates(1L, Exchange.UPBIT) } returns emptyMap()
        coEvery { client.getClosedOrders("cancel", any(), any()) } returns emptyList()
    }

    @AfterEach
    fun teardown() = logger.detachAppender(logs).let { }

    private fun order(uuid: String, identifier: String? = "ctb-$uuid", executed: String? = "0.1") =
        Order(uuid = uuid, identifier = identifier, side = "ask", market = "KRW-BTC", state = "done", executedVolume = executed, createdAt = "2026-11-12T10:00:00+09:00")

    private fun logged(level: Level) = logs.list.filter { it.level == level }.map { it.formattedMessage }

    private fun recorded(vararg uuids: String) {
        every { executions.findRecordedOrderIds(1L, any()) } returns Flux.fromArray(uuids)
    }

    @Test
    fun `a filled bot order without a trade record is reported once, within the settled window`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"), order("o-2"))
        recorded("o-2")

        reconciler.reconcileAll()
        reconciler.reconcileAll()

        val errors = logged(Level.ERROR)
        assertEquals(1, errors.size, errors.toString())
        assertTrue("1건" in errors.single() && "KRW-BTC 매도 0.1 2026-11-12T10:00:00+09:00 o-1" in errors.single() && "o-2" !in errors.single(), errors.single())
        coVerify { client.getClosedOrders("done", now.minusSeconds(48 * 3600), now.minusSeconds(3600)) }
    }

    // 소유자가 앱에서 낸 주문(다른 접두·identifier 없음)과 체결되지 않은 주문은 보지 않는다.
    @Test
    fun `owner orders, unfilled orders and pending orders are not candidates, and all recorded means one info line`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns
            listOf(order("o-1", identifier = null), order("o-2", identifier = "app-x"), order("o-3", executed = "0"), order("o-4", executed = null), order("o-5"), order("o-6"))
        coEvery { states.loadStates(1L, Exchange.UPBIT) } returns mapOf("KRW-BTC" to TradingState("KRW-BTC", pendingSellUuid = "o-5"))
        recorded("o-6")

        reconciler.reconcileAll()

        assertEquals(emptyList<String>(), logged(Level.ERROR))
        assertEquals(1, logged(Level.INFO).count { "all recorded" in it }, logged(Level.INFO).toString())
    }

    // 기록부터 읽으면, 그 사이 끝난 매도(기록과 pending 해제가 한 트랜잭션)가 기록 없음으로 보인다.
    @Test
    fun `the exchange is read before pending and pending before trade records`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"))
        recorded("o-1")

        reconciler.reconcileAll()

        coVerifyOrder {
            client.getClosedOrders("done", any(), any())
            states.loadStates(1L, Exchange.UPBIT)
            executions.findRecordedOrderIds(1L, any())
        }
    }

    @Test
    fun `only the first eight unrecorded fills are listed, with the rest counted`() = runBlocking {
        coEvery { client.getClosedOrders("done", any(), any()) } returns (1..11).map { order("o-$it") }
        recorded()

        reconciler.reconcileAll()

        val error = logged(Level.ERROR).single()
        assertTrue("11건" in error && "o-8 외 3건" in error && "o-9" !in error, error)
        assertTrue(logged(Level.WARN).single().contains("o-11"), "나머지 uuid 는 로그 파일에 남는다")
    }

    // 한 사용자의 조회 실패는 다음 사용자를 막지 않는다. 모두 실패면 대조가 꺼진 것이라 ERROR 다.
    @Test
    fun `a failed lookup skips that user with a warning, and a run where every user failed raises one error`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 1L, exchange = "UPBIT"))
        coEvery { client.getClosedOrders("done", any(), any()) } throws IllegalStateException("500")

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.WARN).size)
        assertEquals(1, logged(Level.ERROR).count { "모두 실패" in it }, logged(Level.ERROR).toString())
    }

    @Test
    fun `the loop keeps running after a failed run and stops when the bean is destroyed`() = runBlocking {
        val runs = java.util.concurrent.atomic.AtomicInteger()
        every { botStates.findByExchange("UPBIT") } answers {
            if (runs.incrementAndGet() == 1) throw IllegalStateException("db down") else Flux.empty()
        }
        val looping = OrderReconciler(
            botStates, users, secrets, { client }, states, executions, Clock.fixed(now, ZoneOffset.UTC),
            OrderReconciler.Timings(firstRunAfter = java.time.Duration.ofMillis(1), every = java.time.Duration.ofMillis(20)),
        )

        looping.start()
        kotlinx.coroutines.withTimeout(5_000) { while (runs.get() < 3) kotlinx.coroutines.delay(5) }
        looping.stop()
        val stoppedAt = runs.get()
        kotlinx.coroutines.delay(100)

        // 취소는 다음 중단점에서 보인다 — 이미 시작한 한 번은 끝날 수 있다.
        assertTrue(runs.get() <= stoppedAt + 1, "멈춘 뒤에는 돌지 않는다")
        assertEquals(1, logged(Level.WARN).count { "trying again" in it })
    }

    @Test
    fun `a user without both keys is skipped with an info line and not counted, and a decrypt failure is skipped with a warning while the next user still runs`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns
            Flux.just(BotStateEntity(userId = 2L, exchange = "UPBIT"), BotStateEntity(userId = 3L, exchange = "UPBIT"), BotStateEntity(userId = 1L, exchange = "UPBIT"))
        every { users.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "nokey", password = "x", upbitAccessKey = "a"))
        every { users.findById(3L) } returns Mono.just(UserEntity(id = 3L, username = "bad", password = "x", upbitAccessKey = "a", upbitSecretKey = "s"))
        every { secrets.decryptUserSecrets(match { it.id == 3L }) } throws IllegalStateException("decrypt failed")
        coEvery { client.getClosedOrders("done", any(), any()) } returns listOf(order("o-1"))
        recorded()

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.WARN).size, logged(Level.WARN).toString())
        assertEquals(1, logged(Level.ERROR).size)
        assertEquals(1, logged(Level.INFO).count { "no Upbit keys" in it })
    }

    // 키 없는 사용자는 시도가 아니다 — 그 사용자가 있어도 시도한 사용자가 모두 실패면 ERROR 다.
    @Test
    fun `keyless users do not hide a run where every attempted user failed`() = runBlocking {
        every { botStates.findByExchange("UPBIT") } returns Flux.just(BotStateEntity(userId = 2L, exchange = "UPBIT"), BotStateEntity(userId = 1L, exchange = "UPBIT"))
        every { users.findById(2L) } returns Mono.just(UserEntity(id = 2L, username = "nokey", password = "x"))
        coEvery { client.getClosedOrders("done", any(), any()) } throws IllegalStateException("401")

        reconciler.reconcileAll()

        assertEquals(1, logged(Level.ERROR).count { "모두 실패" in it }, logged(Level.ERROR).toString())
    }
}
