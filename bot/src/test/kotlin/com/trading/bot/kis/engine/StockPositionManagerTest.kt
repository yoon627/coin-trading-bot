package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisAccountSummary
import com.trading.bot.kis.domain.KisBalanceResponse
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.kis.order.StockOrderStatus
import com.trading.bot.kis.order.StockOrderValidationException
import com.trading.bot.kis.order.SubmitOrderCommand
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import com.trading.common.config.TradingProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StockPositionManagerTest {
    private lateinit var client: KisClient
    private lateinit var orderService: StockOrderService
    private lateinit var pm: StockPositionManager

    @BeforeEach
    fun setup() {
        client = mockk()
        orderService = mockk()
        pm = StockPositionManager(1L, "12345678", "01", client, orderService, TradingProperties())
    }

    private fun intent(status: StockOrderStatus, side: KisSide, qty: Long) = StockOrderIntentEntity(
        id = 1, userId = 1, clientRef = "r", accountNo = "12345678-01", symbol = "005930",
        side = side.name, orderType = "MARKET", qty = qty, status = status.name, orderDate = "20260615",
    )

    @Test
    fun `dry-run buy marks simulated position (C1 no re-buy)`() = runTest {
        val cmd = slot<SubmitOrderCommand>()
        coEvery { orderService.submit(any(), capture(cmd)) } answers { intent(StockOrderStatus.DRY_RUN, KisSide.BUY, cmd.captured.qty) }
        val pos = StockPosition("005930")

        pm.submitBuy(pos, currentPrice = 70_000, strategyName = "rsi", liveEnabled = false)

        assertTrue(pos.position)
        assertTrue(pos.boughtToday)
        assertEquals(70_000.0, pos.avgBuyPrice)
        assertEquals(KisSide.BUY, cmd.captured.side)
        assertEquals(KisOrderType.MARKET, cmd.captured.orderType)
        assertTrue(cmd.captured.qty >= 1)
        coVerify(exactly = 0) { client.getBalance() } // dry-run 은 잔고조회 안 함
    }

    @Test
    fun `live buy sizes from conservative cash (M3)`() = runTest {
        coEvery { client.getBalance() } returns KisBalanceResponse(
            rtCd = "0", summary = listOf(KisAccountSummary(dncaTotAmt = "1000000", prvsRcdlExccAmt = "800000")),
        )
        coEvery { client.getBuyableQty(any(), any()) } returns 999 // 예산이 제약이 되는 케이스
        val cmd = slot<SubmitOrderCommand>()
        coEvery { orderService.submit(any(), capture(cmd)) } answers { intent(StockOrderStatus.PLACED, KisSide.BUY, cmd.captured.qty) }
        val pos = StockPosition("005930")

        pm.submitBuy(pos, currentPrice = 10_000, strategyName = "rsi", liveEnabled = true)

        // min(100만,80만)=80만 × investRatio 0.1 = 8만; qty = floor(8만 / (10000×1.1)) = 7
        assertEquals(7L, cmd.captured.qty)
        assertTrue(pos.boughtToday)
        assertFalse(pos.position) // live: 체결 확정은 다음 패스 getHoldings
    }

    @Test
    fun `live buy is capped by broker buyable qty (C-C)`() = runTest {
        coEvery { client.getBalance() } returns KisBalanceResponse(
            rtCd = "0", summary = listOf(KisAccountSummary(dncaTotAmt = "1000000", prvsRcdlExccAmt = "800000")),
        )
        // 예산으로는 7주가 나오지만 브로커의 미수없는 매수가능수량이 3주 — 증거금률이 반영된 이 값이 상한이다.
        coEvery { client.getBuyableQty("005930", 10_000) } returns 3
        val cmd = slot<SubmitOrderCommand>()
        coEvery { orderService.submit(any(), capture(cmd)) } answers { intent(StockOrderStatus.PLACED, KisSide.BUY, cmd.captured.qty) }

        pm.submitBuy(StockPosition("005930"), currentPrice = 10_000, strategyName = "rsi", liveEnabled = true)

        assertEquals(3L, cmd.captured.qty)
    }

    @Test
    fun `live buy is skipped when buyable qty query fails (fail-closed)`() = runTest {
        coEvery { client.getBalance() } returns KisBalanceResponse(
            rtCd = "0", summary = listOf(KisAccountSummary(dncaTotAmt = "1000000", prvsRcdlExccAmt = "800000")),
        )
        coEvery { client.getBuyableQty(any(), any()) } throws RuntimeException("psbl-order 5xx")
        val pos = StockPosition("005930")

        val intent = pm.submitBuy(pos, currentPrice = 10_000, strategyName = "rsi", liveEnabled = true)

        // 상한을 모르는 채 주문하면 미수가 날 수 있다 — 주문 자체를 내지 않는다.
        assertNull(intent)
        assertFalse(pos.boughtToday)
        coVerify(exactly = 0) { orderService.submit(any(), any()) }
    }

    @Test
    fun `broker rejection does not consume the daily entry slot (C3)`() = runTest {
        coEvery { client.getBalance() } returns KisBalanceResponse(
            rtCd = "0", summary = listOf(KisAccountSummary(dncaTotAmt = "1000000", prvsRcdlExccAmt = "800000")),
        )
        coEvery { client.getBuyableQty(any(), any()) } returns 999
        coEvery { orderService.submit(any(), any()) } returns intent(StockOrderStatus.FAILED, KisSide.BUY, 7)
        val pos = StockPosition("005930")

        pm.submitBuy(pos, currentPrice = 10_000, strategyName = "rsi", liveEnabled = true)

        // FAILED = 미접수 확정. 진입 기회를 소모하면 당일 재시도가 막힌다.
        assertFalse(pos.boughtToday)
    }

    @Test
    fun `live sell uses orderableQty (M2)`() = runTest {
        coEvery { client.getHoldings() } returns listOf(
            KisHolding(pdno = "005930", hldgQty = "5", ordPsblQty = "5", pchsAvgPric = "100"),
        )
        val cmd = slot<SubmitOrderCommand>()
        coEvery { orderService.submit(any(), capture(cmd)) } answers { intent(StockOrderStatus.PLACED, KisSide.SELL, cmd.captured.qty) }
        val pos = StockPosition("005930").apply { position = true }

        pm.submitSell(pos, SellReason.STOP_LOSS, liveEnabled = true)

        assertEquals(KisSide.SELL, cmd.captured.side)
        assertEquals(5L, cmd.captured.qty)
    }

    @Test
    fun `live sell defers when orderable zero but held positive (M2)`() = runTest {
        coEvery { client.getHoldings() } returns listOf(
            KisHolding(pdno = "005930", hldgQty = "5", ordPsblQty = "0", pchsAvgPric = "100"),
        )
        val pos = StockPosition("005930").apply { position = true }

        val result = pm.submitSell(pos, SellReason.STOP_LOSS, liveEnabled = true)

        assertNull(result)
        coVerify(exactly = 0) { orderService.submit(any(), any()) }
    }

    @Test
    fun `dry-run sell simulates and does not query holdings`() = runTest {
        coEvery { orderService.submit(any(), any()) } returns intent(StockOrderStatus.DRY_RUN, KisSide.SELL, 3)
        val pos = StockPosition("005930").apply { position = true; holdQty = 3; avgBuyPrice = 100.0 }

        pm.submitSell(pos, SellReason.TAKE_PROFIT, liveEnabled = false)

        assertFalse(pos.position)
        coVerify(exactly = 0) { client.getHoldings() }
    }

    @Test
    fun `WAL validation failure skips silently without marking position`() = runTest {
        coEvery { orderService.submit(any(), any()) } throws StockOrderValidationException("active BUY order exists")
        val pos = StockPosition("005930")

        val result = pm.submitBuy(pos, 70_000, "rsi", liveEnabled = false)

        assertNull(result)
        assertFalse(pos.position)
        assertFalse(pos.boughtToday)
    }

    @Test
    fun `exit gates`() {
        val pos = StockPosition("005930").apply { position = true; avgBuyPrice = 100.0 }
        assertTrue(pm.checkTakeProfit(pos, 102)) // +2% >= 2.0
        assertTrue(pm.checkStopLoss(pos, 95)) // -5% <= -5.0
        assertFalse(pm.checkTakeProfit(pos, 101))

        pos.peakPrice = 110.0
        assertTrue(pm.checkTrailingStop(pos, 107)) // drop (110-107)/110=2.7% >= trail 2.0, arm 0
        assertFalse(pm.checkTrailingStop(StockPosition("x"), 100)) // no position
    }
}
