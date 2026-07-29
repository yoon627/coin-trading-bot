package com.trading.bot.kis.marketdata

import com.trading.bot.kis.client.KisClientFactory
import com.trading.bot.marketdata.MarketDataStore
import com.trading.common.domain.CandleInterval
import com.trading.common.domain.Exchange
import com.trading.common.domain.NormalizedTicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * watchlist 종목의 현재가/일봉을 전역(.env) KIS 계정으로 폴링해 [MarketDataStore] 에 채운다 — 엔진의 per-tick REST
 * 호출(rate limit)을 줄인다. 장외엔 skip. 전역 KIS 계정 미설정(defaultClient null)이면 비활성(엔진 REST 폴백).
 */
@Service
class KisMarketDataService(
    private val store: MarketDataStore,
    private val clientFactory: KisClientFactory,
    private val props: KisMarketDataProperties,
    private val calendar: KisMarketCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val priceLock = Mutex()
    private val candleLock = Mutex()

    @Scheduled(fixedDelayString = "\${kis.marketdata.price-poll-seconds:3}000")
    fun pollPricesScheduled() {
        if (props.symbolList().isEmpty() || !calendar.isTradingNow()) return
        scope.launch {
            if (!priceLock.tryLock()) return@launch
            try {
                pollPrices()
            } finally {
                priceLock.unlock()
            }
        }
    }

    @Scheduled(fixedDelayString = "\${kis.marketdata.candle-refresh-seconds:300}000")
    fun pollCandlesScheduled() {
        if (props.symbolList().isEmpty() || !calendar.isTradingNow()) return
        scope.launch {
            if (!candleLock.tryLock()) return@launch
            try {
                pollCandles()
            } finally {
                candleLock.unlock()
            }
        }
    }

    private suspend fun pollPrices() {
        val client = clientFactory.defaultClient() ?: return
        for (symbol in props.symbolList()) {
            try {
                val price = client.getCurrentPrice(symbol)
                store.updateTicker(
                    NormalizedTicker(exchange = Exchange.KIS, market = symbol, price = price.toDouble(), timestamp = Instant.now()),
                )
            } catch (e: Exception) {
                log.debug("KIS price poll failed {}: {}", symbol, e.message)
            }
        }
    }

    private suspend fun pollCandles() {
        val client = clientFactory.defaultClient() ?: return
        val today = LocalDate.now(KST)
        val from = today.minusDays(BACKFILL_DAYS)
        for (symbol in props.symbolList()) {
            try {
                client.getDailyCandles(symbol, from.format(YMD), today.format(YMD))
                    .forEach { store.addCandle(StockCandleAdapter.toNormalized(symbol, it, CandleInterval.D1)) }
            } catch (e: Exception) {
                log.debug("KIS candle poll failed {}: {}", symbol, e.message)
            }
        }
    }

    private companion object {
        const val BACKFILL_DAYS = 100L
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
