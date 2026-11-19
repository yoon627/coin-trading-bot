package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.client.UpbitMalformedResponseException
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.exchange.OrderLookupException
import com.trading.bot.exchange.UpbitTradingCalendar
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 주문 전송 결과가 불명확한 경우. 응답을 못 받은 주문을 미전송으로 보면 매수는 다음 tick 에 한 번 더 나가고,
 * 매도는 청산 기록 없이 사라진다. identifier 를 주문 전에 durable 로 남기고, 결과를 identifier 조회로 확정한다.
 */
class PositionManagerUnknownOrderTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-09-28T00:00:00Z"))
    private val manager = PositionManager(upbit, TradingProperties(maxInvestAmount = 100_000.0), stateService, 1L, clock = clock)

    /** 인자는 계속 바뀌는 같은 객체라 upsert 호출 시점에 복사해 둔다. */
    private val saved = mutableListOf<TradingState>()
    private val sent = mutableListOf<OrderRequest>()

    private val logger = LoggerFactory.getLogger("com.trading.bot.engine") as Logger // 확정 규칙은 UnknownOrderResolver 가 로그를 남긴다
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy(); 0L }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun errors() = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    /** 첫 잔고 흔적 알림만 센다. 경과시간 알림·확정 알림 등 다른 ERROR 에도 identifier 가 찍힌다. */
    private fun traceAlerts(identifier: String) = errors().count { identifier in it && "종료 주문 목록에도 없으면" in it }

    private fun krw(balance: String = "1000000") = Account(currency = "KRW", balance = balance)

    private fun btc(balance: String, avg: String = "50000000") = Account(currency = "BTC", balance = balance, avgBuyPrice = avg)

    private fun placeOrderAnswers(block: suspend (OrderRequest) -> Order) {
        coEvery { upbit.placeOrder(any()) } coAnswers { firstArg<OrderRequest>().also { sent += it }.let { block(it) } }
    }

    private fun filled(uuid: String, volume: String) = Order(
        uuid = uuid, state = "done", executedVolume = volume, paidFee = "50",
        trades = listOf(OrderTrade(price = "50000000", volume = volume, funds = "100000")),
    )

    private fun advance(seconds: Long) {
        clock.now = clock.now.plus(Duration.ofSeconds(seconds))
    }

    // --- 매수 ---

    @Test
    fun `an unanswered buy keeps the identifier that was recorded before sending`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        var durableAtSend: String? = null
        placeOrderAnswers {
            durableAtSend = saved.lastOrNull()?.pendingBuyIdentifier
            throw RuntimeException("ReadTimeoutException")
        }
        val state = TradingState("KRW-BTC")

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))

        val identifier = sent.single().identifier
        assertNotNull(identifier)
        assertEquals(identifier, durableAtSend) // 보내기 전에 이미 durable 이었다
        assertEquals(identifier, state.pendingBuyIdentifier)
        assertNull(state.pendingBuyUuid)
        assertEquals("test", state.pendingBuyStrategy)
    }

    @Test
    fun `the next tick does not buy again and confirms the order found by identifier`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))
        assertEquals(1, sent.size)

        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("u-1", "0.002")
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        val record = manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals("u-1", record?.exchangeOrderId)
        assertTrue(state.position)
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy the exchange never saw is released after two misses and sixty seconds`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(10)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier) // 두 번 못 찾았지만 아직 60초 전

        advance(60)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertNull(saved.last().pendingBuyIdentifier) // 해제가 durable 에도 내려갔다

        placeOrderAnswers { Order(uuid = "u-2") }
        coEvery { upbit.getOrder("u-2") } returns Order(uuid = "u-2", state = "cancel")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        assertNotEquals(identifier, sent.last().identifier) // identifier 는 다시 쓰지 않는다
    }

    @Test
    fun `a missing buy with new coins is neither recorded nor released and alerts once`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002")) // 주문 전엔 없던 코인
        logs.list.clear()

        repeat(3) {
            assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))
            advance(70)
        }
        // 흔적이 사라져도(그 사이 누가 팔았을 수 있다) 첫 흔적 뒤 30분이 지나기 전에는 풀지 않는다.
        coEvery { upbit.getAccounts() } returns listOf(krw())
        repeat(3) {
            manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
            advance(70)
        }

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertFalse(state.position)
        assertEquals(1, sent.size)
        assertEquals(1, traceAlerts(identifier))
    }

    @Test
    fun `a miss is counted only when both the order lookup and the balance were seen`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        val identifier = state.pendingBuyIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        // 조회 실패가 끼면 처음부터 다시 센다. 첫 404 로부터 80초가 지났어도 끊김 없는 404 는 1회뿐이다.
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(70)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(1, state.lookupFailures)
        advance(10)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)

        // 잔고를 못 본 경우도 같다.
        advance(70)
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(10)
        coEvery { upbit.getAccounts() } returns listOf(krw())
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)
        assertEquals(0, state.lookupFailures) // 거래소가 답한 조회는 간격을 되돌린다. 막힘 알림은 경과 시간으로 잰다

        advance(70)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertEquals(0, state.lookupFailures) // 확정되면 다음 주문으로 넘기지 않는다
    }

    @Test
    fun `a sell lookup failure also restarts the miss count`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(70)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(10)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(identifier, state.pendingSellIdentifier) // 첫 404 로부터 80초지만 끊김 없는 404 는 1회
    }

    @Test
    fun `an order answered without a uuid is kept pending by identifier`() = runTest {
        // client 는 uuid 없는 응답을 형식 오류로 던진다. 접수됐을 수 있으니 무산으로 풀지 않는다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.001"))
        placeOrderAnswers { throw UpbitMalformedResponseException("no uuid") }
        val buying = TradingState("KRW-BTC")
        val selling = held()

        manager.buy("KRW-BTC", buying, 50_000_000.0, "test")
        manager.sell("KRW-BTC", selling, 52_000_000.0, SellReason.STOP_LOSS)

        assertNotNull(buying.pendingBuyIdentifier)
        assertNull(buying.pendingBuyUuid)
        assertNotNull(selling.pendingSellIdentifier)
        assertNull(selling.pendingSellUuid)
    }

    @Test
    fun `a rejected buy is released at once but an ambiguous client error keeps it`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw UpbitApiException(400, "insufficient_funds_bid", null, "") }
        val rejected = TradingState("KRW-BTC")

        manager.buy("KRW-BTC", rejected, 50_000_000.0, "test")

        assertFalse(rejected.hasPendingBuy())
        assertNull(saved.last().pendingBuyIdentifier)

        placeOrderAnswers { throw UpbitApiException(400, "duplicated_identifier", null, "") }
        val ambiguous = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", ambiguous, 50_000_000.0, "test")
        assertNotNull(ambiguous.pendingBuyIdentifier)
    }

    @Test
    fun `a buy is not sent when its identifier cannot be recorded first`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        val state = TradingState("KRW-BTC")

        assertNull(manager.buy("KRW-BTC", state, 50_000_000.0, "test"))

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertFalse(state.hasPendingBuy())
        assertNull(state.pendingBuyStrategy)
    }

    @Test
    fun `the uuid of a buy in flight is recorded even if the tick is cancelled`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { delay(300); Order(uuid = "u-7") }
        coEvery { upbit.getOrder("u-7") } returns Order(uuid = "u-7", state = "wait")
        val state = TradingState("KRW-BTC")

        val job = launch { manager.buy("KRW-BTC", state, 50_000_000.0, "test") }
        advanceTimeBy(100) // 응답을 기다리는 중
        job.cancel()
        advanceUntilIdle()

        assertEquals("u-7", state.pendingBuyUuid)
        assertNull(state.pendingBuyIdentifier)
        assertEquals("u-7", saved.last().pendingBuyUuid)
    }

    @Test
    fun `a stopping engine does not start a new order`() = runTest {
        coEvery { upbit.getAccounts() } coAnswers { currentCoroutineContext().cancel(); listOf(krw()) }
        val state = TradingState("KRW-BTC")

        launch { manager.buy("KRW-BTC", state, 50_000_000.0, "test") }
        advanceUntilIdle()

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertFalse(state.hasPendingBuy())
    }

    @Test
    fun `a buy known only by identifier after a restart is confirmed by identifier`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { Order(uuid = "u-12") }
        // 선기록은 성공, uuid 기록은 실패 → durable 에는 identifier 만 남는다.
        var upserts = 0
        coEvery { stateService.upsert(any(), any()) } coAnswers {
            if (upserts++ == 0) saved += secondArg<TradingState>().copy() else throw RuntimeException("db down")
            0L
        }
        coEvery { upbit.getOrder("u-12") } throws RuntimeException("api down")
        manager.buy("KRW-BTC", TradingState("KRW-BTC"), 50_000_000.0, "test")
        val durable = saved.single()
        assertNull(durable.pendingBuyUuid)
        val identifier = durable.pendingBuyIdentifier!!

        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy(); 0L }
        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("u-12", "0.002")
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        val restarted = PositionManager(upbit, TradingProperties(), stateService, 1L, clock = clock)
        val record = restarted.reconcilePendingBuy("KRW-BTC", durable.copy(), 50_000_000.0)

        assertEquals("u-12", record?.exchangeOrderId)
    }

    // --- 매도 ---

    private fun held() = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.001)

    @Test
    fun `an unanswered sell keeps the identifier and the position and does not sell twice`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("ReadTimeoutException") }
        val state = held()

        assertNull(manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS))

        assertEquals(sent.single().identifier, state.pendingSellIdentifier)
        assertEquals(sent.single().identifier, saved.first().pendingSellIdentifier) // 보내기 전 기록
        assertEquals(0.001, state.pendingSellPriorVolume)
        assertNotNull(state.pendingSellSince)
        assertTrue(state.position)

        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a sell found by identifier is recorded with the exchange uuid`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!

        coEvery { upbit.getOrderByIdentifier(identifier) } returns filled("s-1", "0.001")
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val record = manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals("s-1", record?.exchangeOrderId)
        assertFalse(state.position)
        assertFalse(state.hasPendingSell())
    }

    @Test
    fun `a sell found by identifier later is recorded at the price it was decided at`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        assertEquals(52_000_000.0, saved.first().pendingSellTriggerPrice) // 보내기 전 선기록에 실린다(재시작에도 남는다)

        coEvery { upbit.getOrderByIdentifier(state.pendingSellIdentifier!!) } returns filled("s-2", "0.001")
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val record = manager.reconcilePendingSell("KRW-BTC", state, 45_000_000.0)

        assertEquals(52_000_000.0, record!!.price)
        assertEquals(52_000.0, record.totalAmount, 1e-6)
    }

    @Test
    fun `a missing sell whose coins are gone is neither recorded nor released`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw()) // 주문 전 0.001 → 0
        logs.list.clear()

        repeat(3) {
            assertNull(manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))
            advance(70)
        }

        assertEquals(identifier, state.pendingSellIdentifier)
        assertTrue(state.position)
        assertEquals(1, traceAlerts(identifier))
    }

    @Test
    fun `a sell the exchange never saw is released after two misses and sixty seconds`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(30)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        assertEquals(identifier, state.pendingSellIdentifier)

        advance(40)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        assertFalse(state.hasPendingSell())
        assertTrue(state.position) // 코인은 그대로라 다음 tick 에 다시 판다
        assertNull(saved.last().pendingSellIdentifier)
    }

    @Test
    fun `the uuid of a sell in flight is recorded even if the tick is cancelled`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { delay(300); Order(uuid = "s-7") }
        coEvery { upbit.getOrder("s-7") } returns Order(uuid = "s-7", state = "wait")
        val state = held()

        val job = launch { manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS) }
        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertEquals("s-7", state.pendingSellUuid)
        assertNull(state.pendingSellIdentifier)
        assertEquals("s-7", saved.last().pendingSellUuid)
    }

    @Test
    fun `a sell is still sent when its identifier cannot be recorded first`() = runTest {
        // 기록 장애가 손절을 막으면 안 된다. 같은 프로세스 안의 이중 매도는 메모리 identifier 가 막는다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()

        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)

        assertEquals(1, sent.size)
        assertTrue(state.pendingPersistFailed)
        assertNotNull(state.pendingSellIdentifier)
    }

    // --- 확정 규칙의 관측 가능한 계약 ---

    /** 응답을 못 받아 identifier 만 남은 매수. */
    private suspend fun unknownBuy(): Pair<TradingState, String> {
        coEvery { upbit.getAccounts() } returns listOf(krw())
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC")
        manager.buy("KRW-BTC", state, 50_000_000.0, "test")
        return state to state.pendingBuyIdentifier!!
    }

    @Test
    fun `an unknown sell backs off after a failed order lookup and after an unseen balance`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held()
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!

        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        assertEquals(1, state.lookupFailures)
        advance(10)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertEquals(2, state.lookupFailures)
    }

    @Test
    fun `finding a buy by identifier clears the failure count even before it fills`() = runTest {
        val (state, identifier) = unknownBuy()
        state.lookupFailures = 3
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(uuid = "u-wait", state = "wait", executedVolume = "0")

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals("u-wait", state.pendingBuyUuid) // 아직 체결 전. 이어받은 uuid 로 다음 tick 에 확정한다
        assertEquals(0, state.lookupFailures) // 주문 상태를 봤으니 조회 장애 카운트는 끊긴다
    }

    @Test
    fun `a buy found by identifier while still waiting with a partial fill is adopted but not confirmed`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns Order(uuid = "u-part", state = "wait", executedVolume = "0.0003")

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertEquals("u-part", state.pendingBuyUuid)
        assertFalse(state.position)
    }

    @Test
    fun `a released sell resyncs the position before dropping the order, as it does today`() = runTest {
        // 재시작 뒤처럼 position=false 로 복원된 행. 동기화 후 pending 해제 순서를 드러내려고, 운영에서는 생기지 않는
        // 요청량(0.001) > 주문 전 보유(0.0005) 행을 쓴다.
        val state = TradingState("KRW-BTC").apply {
            beginSellOrder("ctb-restored", SellReason.STOP_LOSS, since = clock.instant(), volume = 0.001, triggerPrice = 52_000_000.0, priorVolume = 0.0005)
        }
        coEvery { upbit.getOrderByIdentifier("ctb-restored") } returns null
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.0005", locked = "0.0005", avgBuyPrice = "50000000"))

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(70)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertFalse(state.hasPendingSell())
        assertTrue(state.position)
        assertEquals(0.001, state.holdVolume, 1e-12)
        assertTrue(logs.list.any { it.level == Level.WARN && "Sell order ctb-restored for KRW-BTC was never placed" in it.formattedMessage })
    }

    @Test
    fun `the balance is read only when the exchange does not know the order`() = runTest {
        val (state, identifier) = unknownBuy()

        clearMocks(upbit, answers = false)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        coVerify(exactly = 0) { upbit.getAccounts() } // 조회가 실패하면 흔적을 보지 않는다

        advance(10)
        clearMocks(upbit, answers = false)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        coVerify(exactly = 1) { upbit.getAccounts() } // 404 면 흔적 판정 한 번
    }

    @Test
    fun `an unknown buy is released exactly sixty seconds after the first miss`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(59)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)

        advance(1)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertTrue(logs.list.any { it.level == Level.WARN && "Buy order $identifier for KRW-BTC was never placed" in it.formattedMessage })
    }

    @Test
    fun `an unseen balance counts toward the failure alert even after a trace was seen`() = runTest {
        // 흔적을 봤어도 이번에 잔고를 못 봤으면 주문 상태를 확인할 수단이 없으니 조회 장애로 센다.
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002")) // 주문 전엔 없던 코인
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(0, state.lookupFailures)

        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(1, state.lookupFailures)
    }

    @Test
    fun `a trace keeps an unknown buy held through a lookup failure and a vanished balance`() = runTest {
        // 흔적을 한 번 봤으면 조회 실패로 연속 횟수가 끊겨도 흔적 기록은 남는다. 그 뒤 흔적이 사라져도 30분 전에는 풀지 않는다.
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        logs.list.clear()
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw())
        repeat(3) {
            advance(70)
            manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        }

        assertEquals(identifier, state.pendingBuyIdentifier)
        assertEquals(1, traceAlerts(identifier))
    }

    // --- 잔고 흔적이 있는 404 의 자동 확정 ---

    private val tracedWindow = Duration.ofMinutes(30)

    private fun closedOrder(uuid: String, identifier: String, state: String = "done", volume: String = "0.002") =
        Order(uuid = uuid, identifier = identifier, state = state, executedVolume = volume)

    private fun decisions(identifier: String) = errors().filter { identifier in it && "미접수로 확정했다" in it }

    @Test
    fun `a traced buy still missing after thirty minutes is adopted when the closed orders list it`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        coEvery { upbit.getClosedOrders("done", any(), any(), any(), any()) } returns listOf(closedOrder("u-late", identifier))
        coEvery { upbit.getClosedOrders("cancel", any(), any(), any(), any()) } returns emptyList()
        coEvery { upbit.getOrder("u-late") } returns filled("u-late", "0.002")

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))
        advance(tracedWindow.seconds - 1)
        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))
        assertEquals(identifier, state.pendingBuyIdentifier)
        coVerify(exactly = 0) { upbit.getClosedOrders(any(), any(), any(), any(), any()) }

        advance(1)
        val record = manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertEquals("u-late", record?.exchangeOrderId)
        assertTrue(state.position)
        assertFalse(state.hasPendingBuy())
        // 매수 시각은 저장되지 않아 업비트가 허용하는 7일 창 전체를 본다.
        coVerify { upbit.getClosedOrders("done", clock.now.minus(Duration.ofDays(7).minusMinutes(1)), clock.now, true, "KRW-BTC") }
    }

    @Test
    fun `a traced buy missing from the closed orders is settled as not placed and not bought again that day`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns listOf(closedOrder("u-other", "ctb-other"))
        logs.list.clear()

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(tracedWindow.seconds)
        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0))

        assertFalse(state.hasPendingBuy())
        assertFalse(state.position)
        assertTrue(state.boughtToday)
        assertEquals(UpbitTradingCalendar.tradingDate(LocalDateTime.now(TradingDay.KST)), state.boughtDate)
        assertNull(saved.last().pendingBuyIdentifier)
        assertTrue(saved.last().boughtToday)
        assertEquals(1, decisions(identifier).size, errors().toString())
        assertEquals(1, traceAlerts(identifier))
        coVerify(exactly = 0) { stateService.upsert(any(), match { it.position }) } // 거래 기록·장부 편입 없음
    }

    @Test
    fun `lookup failures, unseen balances and a vanished trace do not restart the thirty minute timer`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        advance(600)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws RuntimeException("order lookup down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(300)
        coEvery { upbit.getOrderByIdentifier(identifier) } throws OrderLookupException.NotYetVisible()
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(300)
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } throws RuntimeException("accounts down")
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)

        advance(tracedWindow.seconds - 1200)
        coEvery { upbit.getAccounts() } returns listOf(krw()) // 흔적이 사라졌어도 시작한 타이머는 이어 간다
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)

        assertFalse(state.hasPendingBuy())
        assertTrue(state.boughtToday)
    }

    @Test
    fun `a failed closed orders call keeps a traced buy pending until the next lookup`() = runTest {
        val (state, identifier) = unknownBuy()
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.002"))
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        advance(tracedWindow.seconds)
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } throws RuntimeException("closed orders down")

        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertEquals(identifier, state.pendingBuyIdentifier)
        assertEquals(1, state.lookupFailures)

        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()
        advance(10)
        manager.reconcilePendingBuy("KRW-BTC", state, 50_000_000.0)
        assertFalse(state.hasPendingBuy())
        assertEquals(0, state.lookupFailures)
    }

    @Test
    fun `a traced sell missing from the closed orders shrinks the book by the balance that left`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.003"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.003, bookKnown = true)
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        val since = state.pendingSellSince!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001")) // 주문 전 0.003 → 0.001
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()
        logs.list.clear()

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        assertNull(manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))

        assertFalse(state.hasPendingSell())
        assertTrue(state.position)
        assertEquals(0.001, state.holdVolume, 1e-12) // min(주문 0.003, 0.003 − 0.001) = 0.002 를 뺐다
        assertEquals(0.001, saved.last().holdVolume, 1e-12)
        assertNull(saved.last().pendingSellIdentifier)
        assertEquals(1, decisions(identifier).size, errors().toString())
        // 매도는 주문 시각부터 본다.
        coVerify { upbit.getClosedOrders("done", since.minus(Duration.ofMinutes(1)), clock.now, true, "KRW-BTC") }
    }

    @Test
    fun `a traced sell whose whole book left closes the position without a record`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = held().apply { bookKnown = true }
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(krw())
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        assertNull(manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0))

        assertFalse(state.hasPendingSell())
        assertFalse(state.position)
        assertEquals(0.0, state.holdVolume)
    }

    @Test
    fun `a traced sell without its order volume is released like an untraced miss with a warning`() = runTest {
        val state = held().apply {
            beginSellOrder("ctb-old", SellReason.STOP_LOSS, since = clock.instant(), volume = 0.001, triggerPrice = 52_000_000.0, priorVolume = 0.001)
            pendingSellVolume = null
        }
        coEvery { upbit.getOrderByIdentifier("ctb-old") } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0005"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertFalse(state.hasPendingSell())
        assertEquals(0.001, state.holdVolume, 1e-12)
        assertTrue(logs.list.any { it.level == Level.ERROR && "ctb-old" in it.formattedMessage && "주문 수량 기록이 없어" in it.formattedMessage })
    }

    // 장부를 모르는 채 확정하면 저장된 옛 장부가 남아, 다음 동기화 뒤 그 수량만큼 소유자 코인을 팔 수 있다.
    @Test
    fun `a traced sell is not settled while the bot book is unknown`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.003"))
        placeOrderAnswers { throw RuntimeException("connection reset") }
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 50_000_000.0, holdVolume = 0.003)
        manager.sell("KRW-BTC", state, 52_000_000.0, SellReason.STOP_LOSS)
        val identifier = state.pendingSellIdentifier!!
        coEvery { upbit.getOrderByIdentifier(identifier) } returns null
        coEvery { upbit.getAccounts() } returns listOf(btc("0.001"))
        coEvery { upbit.getClosedOrders(any(), any(), any(), any(), any()) } returns emptyList()
        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)
        advance(tracedWindow.seconds)
        state.bookKnown = false
        // 확정 처리 안의 동기화만 실패한다.
        coEvery { upbit.getAccounts() } returnsMany listOf(listOf(btc("0.001"))) andThenThrows RuntimeException("accounts down")

        manager.reconcilePendingSell("KRW-BTC", state, 52_000_000.0)

        assertTrue(state.hasPendingSell(), "장부를 모르면 확정하지 않는다")
        assertEquals(0.003, state.holdVolume, 1e-12)
        assertTrue(decisions(identifier).isEmpty(), errors().toString())
    }
}
