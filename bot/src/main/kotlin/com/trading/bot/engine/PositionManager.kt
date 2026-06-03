package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.domain.Account
import com.trading.bot.domain.Order
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import com.trading.common.config.TradingProperties
import kotlin.math.floor
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class PositionManager(
    private val upbitClient: UpbitClient,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_ORDER_AMOUNT_KRW = 5000.0
        private const val FILL_POLL_ATTEMPTS = 10
        private const val FILL_POLL_DELAY_MS = 300L
    }

    suspend fun syncPosition(ticker: String, state: TradingState) {
        try {
            val accounts = upbitClient.getAccounts()
            val currency = ticker.substringAfter("-")
            val account = accounts.find { it.currency == currency }
            if (account != null && account.balanceDouble() > 0) {
                state.position = true
                state.avgBuyPrice = account.avgBuyPriceDouble()
                state.holdVolume = account.balanceDouble()
                log.info("Synced existing position for {}: price={}, volume={}", ticker, state.avgBuyPrice, state.holdVolume)
            }
        } catch (e: Exception) {
            log.warn("Failed to sync position for {}: {}", ticker, e.message)
        }
    }

    suspend fun buy(ticker: String, state: TradingState, currentPrice: Double, strategyName: String): TradeRecord? {
        // 재매수 가드: 이미 보유 중이면 신규 매수 금지 (신호 유지 시 잔액 소진 방지).
        if (state.position) {
            log.debug("Skip buy for {}: already holding position", ticker)
            return null
        }
        try {
            val krwAccount = getKrwBalance()
            val investAmount = calculateInvestAmount(krwAccount)
            if (investAmount < MIN_ORDER_AMOUNT_KRW) {
                log.debug("Insufficient funds for {}: investAmount={}", ticker, investAmount)
                return null
            }

            // Upbit market buy: ord_type=price, price=총 투자금액
            val order = upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "bid",
                    ordType = "price",
                    price = floor(investAmount).toLong().toString(),
                )
            )

            // 체결 확인 후에만 상태 변경 (fire-and-forget 금지).
            // C1: 체결 판정은 state 가 아닌 executedVolume 으로 한다. Upbit 시장가 매수는 소액잔량 환불 시
            // state=cancel + executed_volume>0 으로 종료될 수 있고(부분체결), 이때도 실제 코인을 받았으므로
            // 매수로 인정해야 phantom holding(손절·익절 영구 미작동)을 막는다. 실수량/평단은 아래 실잔고로 재확인.
            val filled = awaitFill(order.uuid)
            val executedVolume = filled?.executedVolume?.toDoubleOrNull() ?: 0.0
            if (filled == null || executedVolume <= 0.0) {
                log.warn("Buy not filled for {}: state={}, executedVolume={}", ticker, filled?.state, executedVolume)
                return null
            }

            // 실제 체결 수량/평단을 거래소 계좌에서 재조회 (수수료·슬리피지 반영된 진실).
            val currency = ticker.substringAfter("-")
            val account = upbitClient.getAccounts().find { it.currency == currency }
            val volume = account?.balanceDouble()?.takeIf { it > 0.0 } ?: executedVolume
            val fillPrice = account?.avgBuyPriceDouble()?.takeIf { it > 0.0 } ?: currentPrice
            val totalAmount = fillPrice * volume

            state.markBought(fillPrice, volume, strategyName)
            log.info("BUY {} filled: volume={}, avgPrice={}, amount={}", ticker, volume, fillPrice, totalAmount)

            return TradeRecord(
                ticker = ticker,
                side = TradeSide.BUY,
                price = fillPrice,
                volume = volume,
                totalAmount = totalAmount,
                strategy = strategyName,
            )
        } catch (e: Exception) {
            log.error("Failed to buy {}: {}", ticker, e.message, e)
            return null
        }
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? {
        if (!state.position) return null

        try {
            // 매도 수량은 state.holdVolume(조작 가능)이 아니라 거래소 실잔고를 사용.
            val currency = ticker.substringAfter("-")
            val account = upbitClient.getAccounts().find { it.currency == currency }
            val sellable = account?.balanceDouble() ?: 0.0
            if (sellable <= 0.0) {
                // M4: balance=0 이어도 locked>0 이면 매도 주문이 진행 중(잔고가 locked 로 이동)일 수 있다.
                // locked>0 이면 phantom 이 아니므로 markSold 하지 않고 보류(다음 tick 재시도). balance+locked 가
                // 둘 다 0 일 때만 진짜 phantom 으로 청산. (locked 무한상주 시 미체결주문 취소 후 재매도는 M3 별도 PR.)
                val locked = account?.lockedDouble() ?: 0.0
                if (locked > 0.0) {
                    log.warn("Sell deferred for {}: free balance 0 but locked={} (order in flight) — keeping position", ticker, locked)
                    return null
                }
                log.warn("Sell aborted for {}: no balance on exchange — clearing phantom position", ticker)
                state.markSold()
                return null
            }

            // Upbit market sell: ord_type=market, volume=실보유수량(거래소 원본 문자열)
            val order = upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "ask",
                    ordType = "market",
                    volume = account!!.balance,
                )
            )

            // 체결 확정 전엔 포지션 유지 (다음 tick 재시도). done 일 때만 청산 기록.
            val filled = awaitFill(order.uuid)
            if (filled?.state != "done") {
                log.warn("Sell not confirmed for {}: state={} — keeping position for retry", ticker, filled?.state)
                return null
            }

            val pnl = state.pnlPercent(currentPrice)
            val totalAmount = currentPrice * sellable
            log.info("SELL {} filled: price={}, volume={}, pnl={}%, reason={}", ticker, currentPrice, sellable, "%.2f".format(pnl), reason)

            val record = TradeRecord(
                ticker = ticker,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = sellable,
                totalAmount = totalAmount,
                pnlPercent = pnl,
                reason = reason.name,
            )
            state.markSold()
            return record
        } catch (e: Exception) {
            log.error("Failed to sell {}: {}", ticker, e.message, e)
            return null
        }
    }

    /** 주문 체결 폴링. state 가 done/cancel 이면 즉시 반환, 아니면 최대 FILL_POLL_ATTEMPTS 회 폴링. */
    private suspend fun awaitFill(uuid: String): Order? {
        if (uuid.isBlank()) return null
        var last: Order? = null
        repeat(FILL_POLL_ATTEMPTS) {
            last = upbitClient.getOrder(uuid)
            when (last?.state) {
                "done", "cancel" -> return last
            }
            delay(FILL_POLL_DELAY_MS)
        }
        return last
    }

    fun checkTakeProfit(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) >= tradingProperties.takeProfitPct
    }

    fun checkStopLoss(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        return state.pnlPercent(currentPrice) <= -tradingProperties.maxLossPct
    }

    fun checkTrailingStop(state: TradingState, currentPrice: Double): Boolean {
        if (!state.position) return false
        // Update peak price
        state.updatePeakPrice(currentPrice)
        val pnl = state.pnlPercent(currentPrice)
        // Only activate trailing stop if in profit
        if (pnl <= 0) return false
        val dropFromPeak = state.dropFromPeakPercent(currentPrice)
        return dropFromPeak >= tradingProperties.trailingStopPct
    }

    private suspend fun getKrwBalance(): Double {
        val accounts = upbitClient.getAccounts()
        val krw = accounts.find { it.currency == "KRW" }
        return krw?.balanceDouble() ?: 0.0
    }

    private fun calculateInvestAmount(krwBalance: Double): Double {
        // 잔액의 investRatio 비율만 투자하되 maxInvestAmount 로 상한.
        return minOf(krwBalance * tradingProperties.investRatio, tradingProperties.maxInvestAmount)
    }
}
