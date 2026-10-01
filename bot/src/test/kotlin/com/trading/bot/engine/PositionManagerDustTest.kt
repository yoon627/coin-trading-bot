package com.trading.bot.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.trading.bot.client.UpbitApiException
import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.ExitParamsSnapshot
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderTrade
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradingDay
import com.trading.bot.domain.TradingState
import com.trading.bot.persistence.TradingStateService
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 거래소 최소주문(5,000원) 미만이라 팔 수 없는 보유(dust)가 스윙 티커를 멈추지 않게 한다(#234).
 * 판정은 현재가 기준이고, 주문(흡수 매수·매도·해제)은 모두 실잔고로 다시 확인한다.
 */
class PositionManagerDustTest {

    private val upbit = mockk<UpbitClient>(relaxed = true)
    private val stateService = mockk<TradingStateService>(relaxed = true)
    private val manager = PositionManager(upbit, TradingProperties(maxInvestAmount = 100_000.0), stateService, 1L)
    private val saved = mutableListOf<TradingState>()

    private val logger = LoggerFactory.getLogger(PositionManager::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun setup() {
        coEvery { stateService.upsert(any(), any()) } coAnswers { saved += secondArg<TradingState>().copy() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(logs)
    }

    private fun logsAt(level: Level) = logs.list.filter { it.level == level }

    private val price = 10_000_000.0

    private fun krw(balance: String = "1000000") = Account(currency = "KRW", balance = balance)

    private fun btc(balance: String, avg: String = "12000000") = Account(currency = "BTC", balance = balance, avgBuyPrice = avg)

    /** 1,000원어치 — 수동 부분매도 잔량 같은 dust. 옛 진입 메타가 남아 있다. */
    private fun dust() = TradingState(
        "KRW-BTC",
        position = true,
        avgBuyPrice = 12_000_000.0,
        holdVolume = 0.0001,
        buyDate = LocalDate.of(2026, 8, 1),
        entryStrategy = "old_strategy",
        peakPrice = 15_000_000.0,
        exitParams = ExitParamsSnapshot(takeProfitPct = 9.0, maxLossPct = 9.0, trailingStopPct = 9.0, trailingArmPct = 9.0, maxHoldDays = 9),
    )

    @Test
    fun `exactly the minimum order is sellable`() {
        assertFalse(isBelowMinOrder(0.0005, 10_000_000.0))
        assertTrue(isBelowMinOrder(0.00049, 10_000_000.0))
    }

    @Test
    fun `a buy on top of dust is a fresh entry that absorbs the dust`() = runTest {
        coEvery { upbit.getAccounts() } returnsMany listOf(
            listOf(krw(), btc("0.0001")), // 주문 전
            listOf(krw(), btc("0.0101", avg = "10019802")), // 체결 뒤 — 평단에 dust 원가가 섞인다(수용된 결정)
        )
        coEvery { upbit.placeOrder(any()) } returns Order(uuid = "b-1")
        coEvery { upbit.getOrder("b-1") } returns Order(
            uuid = "b-1", state = "done", executedVolume = "0.01", paidFee = "50",
            trades = listOf(OrderTrade(price = "10000000", volume = "0.01", funds = "100000")),
        )
        val state = dust()

        val record = manager.buy("KRW-BTC", state, price, "combined")

        assertEquals(0.0101, record!!.volume, 1e-12) // dust + 이번 체결
        assertEquals(0.0001, saved.first().pendingBuyPriorVolume) // 선기록된 주문 전 보유 = dust
        assertEquals(TradingDay.of(LocalDateTime.now(TradingDay.KST)), state.buyDate)
        assertEquals("combined", state.entryStrategy)
        assertEquals(TradingProperties().maxLossPct, state.exitParams!!.maxLossPct) // 옛 스냅샷(9.0)이 아니라 지금 설정
        assertEquals(state.avgBuyPrice, state.peakPrice) // 옛 고점(15,000,000)을 물려받지 않는다
    }

    @Test
    fun `a rejected absorbing buy leaves the dust under management`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0001"))
        coEvery { upbit.placeOrder(any()) } throws UpbitApiException(400, "insufficient_funds_bid", null, "")
        val state = dust()

        manager.buy("KRW-BTC", state, price, "combined")

        coVerify(exactly = 1) { upbit.placeOrder(any()) } // 흡수 매수가 실제로 나갔다
        assertTrue(state.position)
        assertEquals(0.0001, state.holdVolume)
        assertEquals(12_000_000.0, state.avgBuyPrice)
    }

    @Test
    fun `an absorbing buy that cannot be recorded first leaves the dust under management`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0001"))
        coEvery { stateService.upsert(any(), any()) } throws RuntimeException("db down")
        val state = dust()

        manager.buy("KRW-BTC", state, price, "combined")

        coVerify(atLeast = 1) { stateService.upsert(any(), any()) } // 선기록까지 갔다
        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertTrue(state.position)
        assertEquals(0.0001, state.holdVolume)
    }

    @Test
    fun `an absorbing fill without a coin row after the fill keeps the dust and averages the cost`() = runTest {
        // 체결은 확인됐는데 계좌가 코인 행을 안 돌려주면, 체결분만으로 replace 하면 dust 가 장부에서 사라진다.
        coEvery { upbit.getAccounts() } returnsMany listOf(
            listOf(krw(), btc("0.0001")), // 주문 전
            listOf(krw()), // 체결 뒤 — 코인 행 없음
        )
        coEvery { upbit.placeOrder(any()) } returns Order(uuid = "b-nf")
        coEvery { upbit.getOrder("b-nf") } returns Order(uuid = "b-nf", state = "done", executedVolume = "0.01")
        val state = dust()

        manager.buy("KRW-BTC", state, price, "combined")

        assertEquals(0.0101, state.holdVolume, 1e-12)
        // 평단도 가중평균 — 이번 체결가로 덮으면 dust 원가가 빠진다.
        assertEquals((12_000_000.0 * 0.0001 + price * 0.01) / 0.0101, state.avgBuyPrice, 1.0)
    }

    @Test
    fun `balance recovery of an absorbing buy counts only the increase over the dust`() = runTest {
        // 주문 뒤 getOrder 가 죽었다. 계좌의 dust 는 주문 전부터 있던 것 — 증분이 없으니 체결로 보지 않는다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0001"))
        coEvery { upbit.placeOrder(any()) } returns Order(uuid = "b-rec")
        coEvery { upbit.getOrder("b-rec") } throws RuntimeException("getOrder down")
        val state = dust()

        assertNull(manager.buy("KRW-BTC", state, price, "combined"))
        assertEquals(0.0001, state.pendingBuyPriorVolume)

        assertNull(manager.reconcilePendingBuy("KRW-BTC", state, price))
        assertEquals("b-rec", state.pendingBuyUuid)

        // 잔고가 늘었으면 그 증분만 이 주문의 체결이다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.0101", avg = "10019802"))
        val record = manager.reconcilePendingBuy("KRW-BTC", state, price)

        assertEquals(0.0101, record!!.volume, 1e-12)
        assertEquals(0.0101, state.holdVolume, 1e-12)
        assertEquals("combined", state.entryStrategy)
        assertNull(state.pendingBuyUuid)
    }

    @Test
    fun `a stale dust reading does not buy on top of a sellable holding`() = runTest {
        // 기록상 dust 지만 그 사이 앱에서 더 사서 실제로는 10만원어치다 — 그 위에 사면 이중 포지션이다.
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.01", avg = "9500000"))
        val state = dust()

        assertNull(manager.buy("KRW-BTC", state, price, "combined"))

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertEquals(0.01, state.holdVolume)
        assertEquals(9_500_000.0, state.avgBuyPrice)
    }

    @Test
    fun `a sellable holding still blocks a new buy`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(krw(), btc("0.01"))
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 12_000_000.0, holdVolume = 0.01)

        assertNull(manager.buy("KRW-BTC", state, price, "combined"))
        coVerify(exactly = 0) { upbit.placeOrder(any()) }
    }

    @Test
    fun `selling dust sends nothing and warns once, and a later sellable balance sells normally`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0001"))
        val state = dust()

        repeat(3) { assertNull(manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)) }

        coVerify(exactly = 0) { upbit.placeOrder(any()) }
        assertTrue(saved.isEmpty()) // 선기록도 없다
        assertTrue(logsAt(Level.ERROR).isEmpty())
        assertEquals(1, logsAt(Level.WARN).count { "dust" in it.formattedMessage })
        assertTrue(state.position)

        // 사용자 지정가가 풀려 free 가 돌아오면 다음 청산 사유에서 정상으로 판다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.01"))
        coEvery { upbit.placeOrder(any()) } returns Order(uuid = "s-1")
        coEvery { upbit.getOrder("s-1") } returns Order(uuid = "s-1", state = "done", executedVolume = "0.01")
        manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)
        coVerify(exactly = 1) { upbit.placeOrder(any()) }
    }

    @Test
    fun `a dust sell corrects a stale holding size so the engine can see the dust`() = runTest {
        // 동기화 뒤 앱에서 대부분을 팔아 기록(0.01)과 실제(0.0001)가 다르다 — 가드가 실측으로 고쳐야 엔진이 진입을 연다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0001"))
        val state = dust().apply { holdVolume = 0.01 }

        manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

        assertEquals(0.0001, state.holdVolume)
    }

    @Test
    fun `no absorbing buy while a sell from the same tick is still unconfirmed`() = runTest {
        // 기록상 dust 였지만 실제론 팔 수 있어 매도가 나갔고 결과가 불명으로 남았다 — 그 위에 사면 매도 확정이 새 코인과 섞인다.
        coEvery { upbit.getAccounts() } returns listOf(krw())
        val state = dust().apply { pendingSellUuid = "s-unknown"; pendingSellVolume = 0.01 }

        assertNull(manager.buy("KRW-BTC", state, price, "combined"))
        coVerify(exactly = 0) { upbit.placeOrder(any()) }

        manager.releaseDust("KRW-BTC", state, price)
        assertTrue(state.position)
        assertEquals("s-unknown", state.pendingSellUuid)
    }

    @Test
    fun `dust with coins locked by another order is not released`() = runTest {
        // 사용자 지정가가 풀리면 코인이 돌아온다 — 목록 밖이라 내리면 다시 편입할 길이 없다.
        coEvery { upbit.getAccounts() } returns listOf(Account(currency = "BTC", balance = "0.0001", locked = "0.05", avgBuyPrice = "12000000"))
        val state = dust()

        manager.releaseDust("KRW-BTC", state, price)

        assertTrue(state.position)
        assertEquals("old_strategy", state.entryStrategy)
    }

    @Test
    fun `an exchange minimum-order rejection of a swing sell is a warning, not an error`() = runTest {
        // tick 가격과 거래소 판정 가격이 달라 가드를 통과한 경우 — 거래소 판정이 기준이다.
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0005"))
        coEvery { upbit.placeOrder(any()) } throws UpbitApiException(400, "under_min_total_ask", null, "")
        val state = TradingState("KRW-BTC", position = true, avgBuyPrice = 12_000_000.0, holdVolume = 0.0005)

        manager.sell("KRW-BTC", state, price, SellReason.STOP_LOSS)

        assertTrue(logsAt(Level.ERROR).isEmpty())
        assertFalse(state.hasPendingSell())
        assertTrue(state.position)
    }

    @Test
    fun `dust on a ticker that can no longer buy is released after checking the real balance`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.0001"))
        val state = dust()

        manager.releaseDust("KRW-BTC", state, price)

        assertFalse(state.position)
        assertNull(state.entryStrategy)
        assertNull(state.buyDate)
        assertNull(saved.last().entryStrategy) // 재시작 때 잔류로 실리지 않게 durable 에도 내린다
    }

    @Test
    fun `a sellable balance is not released as dust`() = runTest {
        coEvery { upbit.getAccounts() } returns listOf(btc("0.01"))
        val state = dust()

        manager.releaseDust("KRW-BTC", state, price)

        coVerify(exactly = 1) { upbit.getAccounts() } // 기록이 아니라 실잔고로 판정했다
        assertTrue(state.position)
        assertEquals("old_strategy", state.entryStrategy)
    }

    @Test
    fun `a swing buy too small to survive its stop-loss is not placed`() = runTest {
        // 기본 손절 5% — 5,200원으로 사면 손절 시점 가치가 4,940원이라 거래소가 매도를 거부한다.
        coEvery { upbit.getAccounts() } returns listOf(krw("52000"))
        assertNull(manager.buy("KRW-BTC", TradingState("KRW-BTC"), price, "combined"))
        coVerify(exactly = 0) { upbit.placeOrder(any()) }

        coEvery { upbit.getAccounts() } returns listOf(krw("53000")) // 5,300원 × 0.95 = 5,035원
        coEvery { upbit.placeOrder(any()) } returns Order(uuid = "b-2")
        manager.buy("KRW-BTC", TradingState("KRW-BTC"), price, "combined")
        coVerify(exactly = 1) { upbit.placeOrder(any()) }
        assertNotEquals(0, saved.size)
    }
}
