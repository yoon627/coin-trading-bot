package com.trading.bot.engine

import com.trading.bot.client.UpbitClient
import com.trading.bot.config.TradingProperties
import com.trading.bot.domain.Account
import com.trading.bot.domain.OrderRequest
import com.trading.bot.domain.SellReason
import com.trading.bot.domain.TradeRecord
import com.trading.bot.domain.TradeSide
import com.trading.bot.domain.TradingState
import org.slf4j.LoggerFactory
import kotlin.math.floor

class PositionManager(
    private val upbitClient: UpbitClient,
    private val tradingProperties: TradingProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_ORDER_AMOUNT_KRW = 5000.0
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

            val volume = investAmount / currentPrice
            state.markBought(currentPrice, volume)
            log.info("BUY {} at price={}, amount={}", ticker, currentPrice, investAmount)

            return TradeRecord(
                ticker = ticker,
                side = TradeSide.BUY,
                price = currentPrice,
                volume = volume,
                totalAmount = investAmount,
                strategy = strategyName,
            )
        } catch (e: Exception) {
            log.error("Failed to buy {}: {}", ticker, e.message)
            return null
        }
    }

    suspend fun sell(ticker: String, state: TradingState, currentPrice: Double, reason: SellReason): TradeRecord? {
        if (!state.position || state.holdVolume <= 0) return null

        try {
            // Upbit market sell: ord_type=market, volume=보유수량
            upbitClient.placeOrder(
                OrderRequest(
                    market = ticker,
                    side = "ask",
                    ordType = "market",
                    volume = state.holdVolume.toString(),
                )
            )

            val pnl = state.pnlPercent(currentPrice)
            val totalAmount = currentPrice * state.holdVolume
            log.info("SELL {} at price={}, pnl={}%, reason={}", ticker, currentPrice, "%.2f".format(pnl), reason)

            val record = TradeRecord(
                ticker = ticker,
                side = TradeSide.SELL,
                price = currentPrice,
                volume = state.holdVolume,
                totalAmount = totalAmount,
                pnlPercent = pnl,
                reason = reason.name,
            )
            state.markSold()
            return record
        } catch (e: Exception) {
            log.error("Failed to sell {}: {}", ticker, e.message)
            return null
        }
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
        return minOf(krwBalance, tradingProperties.maxInvestAmount)
    }
}
