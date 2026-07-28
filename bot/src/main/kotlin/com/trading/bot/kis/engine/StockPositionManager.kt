package com.trading.bot.kis.engine

import com.trading.bot.domain.SellReason
import com.trading.bot.kis.client.KisClient
import com.trading.bot.kis.domain.KisOrderType
import com.trading.bot.kis.domain.KisSide
import com.trading.bot.kis.order.StockOrderService
import com.trading.bot.kis.order.StockOrderStatus
import com.trading.bot.kis.order.StockOrderValidationException
import com.trading.bot.kis.order.SubmitOrderCommand
import com.trading.bot.persistence.entity.StockOrderIntentEntity
import com.trading.common.config.TradingProperties
import com.trading.common.strategy.ExitGates
import kotlin.math.floor
import org.slf4j.LoggerFactory

/**
 * 주식 매수/매도 실행 — 항상 WAL([StockOrderService])을 경유(주문유실 방지). 사용자별 1개(엔진이 소유).
 * 청산 게이트는 크립토 PositionManager 와 동일 수식(ExitGates). live/dry-run 분기:
 *  - live: 잔고 기반 보수 sizing(M3), 매도수량=orderableQty(M2), 포지션은 getHoldings 가 진실.
 *  - dry-run: 잔고/보유 조회 없이 메모리 시뮬(C1 — 무한 재매수 방지).
 */
class StockPositionManager(
    private val userId: Long,
    private val cano: String,
    private val acntPrdtCd: String,
    private val client: KisClient,
    private val orderService: StockOrderService,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** live 에서 보유 동기화. holdingBySymbol = getHoldings() 결과 1패스 캐시. */
    fun syncFromHoldings(pos: StockPosition, heldQty: Long, avgPrice: Double) {
        pos.syncFromHolding(heldQty, avgPrice)
    }

    suspend fun submitBuy(
        pos: StockPosition,
        currentPrice: Long,
        strategyName: String,
        liveEnabled: Boolean,
    ): StockOrderIntentEntity? {
        if (currentPrice <= 0) return null
        val qty = if (liveEnabled) sizeFromBalance(pos.symbol, currentPrice) else nominalQty(currentPrice)
        if (qty <= 0) {
            log.debug("Skip buy {} — qty=0 (insufficient budget or not buyable)", pos.symbol)
            return null
        }
        val intent = submit(pos.symbol, KisSide.BUY, qty) ?: return null
        if (intent.status == StockOrderStatus.DRY_RUN.name) {
            pos.markBoughtSimulated(currentPrice, strategyName)
        } else if (intent.status !in MISSED_BUY_STATUSES) {
            // 접수됐거나 접수 여부가 불명(UNKNOWN)이면 당일 재매수 차단. 실보유/평단은 다음 패스 getHoldings 가 확정.
            pos.boughtToday = true
            pos.entryStrategy = strategyName
        }
        // 미접수 확정(FAILED/REJECTED)은 boughtToday 를 세우지 않는다 — 브로커가 거부했을 뿐 진입 기회는 남아 있다.
        return intent
    }

    suspend fun submitSell(
        pos: StockPosition,
        reason: SellReason,
        liveEnabled: Boolean,
    ): StockOrderIntentEntity? {
        val qty = if (liveEnabled) {
            val holding = client.getHoldings().find { it.pdno == pos.symbol }
            val orderable = holding?.orderableQty() ?: 0L
            if (orderable <= 0) {
                if ((holding?.heldQty() ?: 0L) > 0L) {
                    // 보유하나 매도불가(당일매수 결제전·매도주문중) — 보류, 다음 tick(M2, 크립토 locked 미러).
                    log.warn("Sell deferred {}: held but orderable=0", pos.symbol)
                }
                return null
            }
            orderable
        } else {
            pos.holdQty.coerceAtLeast(1)
        }
        val intent = submit(pos.symbol, KisSide.SELL, qty) ?: return null
        if (intent.status == StockOrderStatus.DRY_RUN.name) {
            pos.markSoldSimulated()
        }
        // live: 매도 접수 — 보유 청산은 다음 패스 getHoldings 가 반영. WAL 활성-매도 가드가 중복 차단.
        log.info("SELL submitted {} qty={} reason={} status={}", pos.symbol, qty, reason, intent.status)
        return intent
    }

    private suspend fun submit(symbol: String, side: KisSide, qty: Long): StockOrderIntentEntity? {
        val cmd = SubmitOrderCommand(userId, cano, acntPrdtCd, symbol, side, KisOrderType.MARKET, qty, price = null)
        return try {
            orderService.submit(client, cmd)
        } catch (e: StockOrderValidationException) {
            // 활성 주문 존재(WAL 가드)·검증 실패 — 조용히 skip(중복 주문 방지).
            log.debug("Skip {} {} — {}", side, symbol, e.message)
            null
        }
    }

    /**
     * 매수 수량 = min(예산 기준 수량, 브로커 매수가능수량).
     *
     * 예산 기준은 min(예수금, D+2정산) × investRatio 에 maxInvestAmount 상한·슬리피지 버퍼를 적용한 값이지만,
     * 이것만으로는 미수를 막지 못한다 — 예수금 계산에는 종목 증거금률이 반영되지 않고, 한 패스에서 여러 종목이
     * 같은 현금을 각자 배정받기 때문이다. 미수의 실질 방어선은 `inquire-psbl-order` 의 미수없는매수수량이며
     * 그 값을 최종 상한으로 쓴다. 조회 실패는 **fail-closed**(0 반환 → 매수 skip) — 상한을 모르는 채 주문하면
     * 미수가 발생할 수 있다.
     */
    private suspend fun sizeFromBalance(symbol: String, currentPrice: Long): Long {
        val summary = client.getBalance().summary.firstOrNull() ?: return 0
        val availableCash = minOf(summary.depositTotal(), summary.settledD2())
        val budget = minOf(availableCash.toDouble() * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
        val byBudget = floor(budget / (currentPrice * MARKET_SLIPPAGE_BUFFER)).toLong()
        if (byBudget <= 0) return 0

        val buyable = try {
            client.getBuyableQty(symbol, currentPrice)
        } catch (e: Exception) {
            log.warn("Skip buy {} — buyable qty query failed (fail-closed): {}", symbol, e.message)
            return 0
        }
        return minOf(byBudget, buyable)
    }

    private fun nominalQty(currentPrice: Long): Long =
        floor(tradingProperties.maxInvestAmount / (currentPrice * MARKET_SLIPPAGE_BUFFER)).toLong().coerceAtLeast(1)

    fun checkTakeProfit(pos: StockPosition, price: Long): Boolean =
        pos.position && pos.pnlPercent(price) >= tradingProperties.takeProfitPct

    fun checkStopLoss(pos: StockPosition, price: Long): Boolean =
        pos.position && pos.pnlPercent(price) <= -tradingProperties.maxLossPct

    fun checkTrailingStop(pos: StockPosition, price: Long): Boolean {
        if (!pos.position) return false
        pos.updatePeak(price)
        val peakPnlPct = if (pos.avgBuyPrice > 0) ((pos.peakPrice - pos.avgBuyPrice) / pos.avgBuyPrice) * 100.0 else 0.0
        return ExitGates.isTrailingStopTriggered(
            pnlPct = pos.pnlPercent(price),
            peakPnlPct = peakPnlPct,
            dropFromPeakPct = pos.dropFromPeakPercent(price),
            trailingStopPct = tradingProperties.trailingStopPct,
            trailingArmPct = tradingProperties.trailingArmPct,
        )
    }

    private companion object {
        const val MARKET_SLIPPAGE_BUFFER = 1.1

        /** 매수 미접수가 확정된 상태 — 당일 진입 기회를 소모하지 않는다. */
        val MISSED_BUY_STATUSES = setOf(StockOrderStatus.FAILED.name, StockOrderStatus.REJECTED.name)
    }
}
