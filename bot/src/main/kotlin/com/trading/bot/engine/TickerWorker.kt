package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory

/** 공용 시계가 바퀴마다 모든 작업자에게 보내는 신호. 거래일과 라운드 앞에서 묶어 받은 REST 시세를 싣는다. */
internal data class Round(val tradingDate: LocalDate, val rest: RestRound)

/**
 * 라운드 앞에서 묶음으로 물은 종목과 그 시세. [asked] 에 있는데 값이 없으면 묶음이 실패했거나 거래소가 답하지 않은 것이라
 * 그 라운드에 따로 다시 묻지 않는다. [asked] 에 없는 종목(라운드 중 시세가 낡은 종목 등)만 따로 묻는다.
 */
internal class RestRound(private val prices: Map<String, Double?>) {
    fun asked(ticker: String): Boolean = ticker in prices

    fun price(ticker: String): Double? = prices[ticker]
}

/** 작업자가 자기 종목에 하는 일. 엔진이 한 번 만들어 모든 작업자에게 준다 — 작업자는 다른 종목의 상태를 읽지 않는다. */
internal interface TickerWork {
    suspend fun sync(ticker: String, state: TradingState, accounts: List<Account>?)

    /** 거래일이 바뀌었다. 리셋을 저장하지 않으면 재시작 때 boughtToday=true 가 복원돼 당일 진입이 막힌다. */
    suspend fun resetDay(state: TradingState, tradingDate: LocalDate)

    /** 목록 밖이고 더 관리할 것이 없으면 관리에서 빼고 true. */
    suspend fun releaseIfSettled(ticker: String, state: TradingState): Boolean

    /** [lastTrade] 는 작업자가 마지막으로 받은 체결이다(없으면 null). */
    suspend fun process(ticker: String, state: TradingState, round: Round, lastTrade: PriceSample?)

    /** 체결 하나로 판단한다. [orders] 가 이 체결로 낼 수 있는 주문이다 — 고점·관측은 늘 한다. */
    suspend fun onTrade(ticker: String, state: TradingState, trade: PriceSample, orders: TradeOrders): TradeOutcome
}

/** 체결 가격과 체결 시각(거래소 시계). */
internal data class PriceSample(val price: Double, val time: Instant)

internal enum class TradeOrders {
    /** 주문 결과·바퀴보다 먼저 받았거나 결과 없는 주문 뒤의 쉼이다. */
    NONE,

    /** 이 거래일의 리셋이 저장되지 않았다 — 재시작하면 어제의 boughtToday 가 돌아오므로 사지 않는다. */
    EXITS,
    ALL,
}

internal enum class TradeOutcome { NO_ORDER, ORDERED, NO_RESULT }

/** 작업자 우편함의 체결 하나. [seq] 는 작업자가 받은 차례다. */
private data class TradeTick(val sample: PriceSample, val seq: Long)

/**
 * 한 종목의 상태를 바꾸는 유일한 곳. 우편함이 둘이다 — 바퀴는 처리하는 동안 온 것을 하나로 합치고(가장 새 거래일·시세가 남는다),
 * 체결은 받은 순서대로 모두 처리하되 상한을 넘으면 오래된 것부터 버린다. 둘 다 와 있으면 바퀴가 먼저다.
 * 종목끼리는 기다리지 않는다 — 매수만 엔진의 창구에서 줄을 선다.
 */
internal class TickerWorker(
    val ticker: String,
    private val state: TradingState,
    private val work: TickerWork,
    private val clock: Clock,
) {
    companion object {
        // 실측한 한 마켓의 초당 최대 체결(BTC 40건) × 60초. 주문이 1분 넘게 걸리면 그보다 오래된 체결은 판단에 쓰지 않는다.
        internal const val TRADE_MAILBOX_CAPACITY = 40 * 60
        private const val DROP_WARN_INTERVAL_MS = 60_000L
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mailbox = Channel<Round>(Channel.CONFLATED)
    private val dropped = AtomicLong()
    private val trades = Channel<TradeTick>(TRADE_MAILBOX_CAPACITY, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
    private val received = AtomicLong()
    private var lastResetDate: LocalDate? = null
    private var tradingDate: LocalDate? = null
    private var lastTrade: PriceSample? = null

    // 이 차례까지 받은 체결은 주문 결과나 상태를 바꾼 바퀴보다 앞서 받은 것이라 새 주문을 내지 않는다.
    private var orderedThrough = 0L

    // 체결로 낸 주문이 결과 없이 끝났다. 다음 바퀴까지 체결로 다시 시도하지 않는다.
    private var coolingDown = false
    private var dropWarnedAtMs: Long? = null

    @Volatile
    var progressAt: Instant = clock.instant()
        private set

    /** 정리돼 정상으로 끝났다. 엔진은 이 작업자의 끝남을 죽음으로 보지 않는다. */
    @Volatile
    var released: Boolean = false
        private set

    fun offer(round: Round) {
        mailbox.trySend(round)
    }

    /** 수집기 하나만 부른다. 정리된 작업자에게 보낸 체결은 버려진다. */
    fun offerTrade(price: Double, tradeTime: Instant) {
        trades.trySend(TradeTick(PriceSample(price, tradeTime), received.incrementAndGet()))
    }

    fun markProgress() {
        progressAt = clock.instant()
    }

    suspend fun run(accounts: List<Account>?) {
        work.sync(ticker, state, accounts)
        markProgress()
        if (release()) return
        while (true) {
            // 시작 가격은 첫 바퀴(저장소·REST)가 정한다. 그 전에 온 체결은 우편함에서 기다린다.
            val next = select<Any?> {
                mailbox.onReceiveCatching { it.getOrNull() }
                if (tradingDate != null) trades.onReceiveCatching { it.getOrNull() }
            }
            when (next) {
                is Round -> if (handleRound(next)) return
                is TradeTick -> handleTrade(next)
                else -> return
            }
        }
    }

    /** 정리돼 끝나야 하면 true. */
    private suspend fun handleRound(round: Round): Boolean {
        markProgress()
        // 리셋이 계속 실패하거나 바퀴가 오래 걸리면 체결을 처리하지 못하므로 여기서도 알린다.
        warnDropped()
        val firstRound = tradingDate == null
        tradingDate = round.tradingDate
        val before = orderState()
        try {
            if (lastResetDate != round.tradingDate) {
                work.resetDay(state, round.tradingDate)
                lastResetDate = round.tradingDate
                // 리셋을 저장한 뒤에 빼야 그 종목의 리셋도 저장된다.
                if (release()) return true
            }
            work.process(ticker, state, round, lastTrade)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 다음 바퀴가 곧 다시 시도한다.
            log.error("Ticker worker {} round failed: {}", ticker, e.message, e)
        }
        // 체결 확인·주문·동기화로 상태가 바뀌었으면 그 앞에 받은 체결로는 주문하지 않는다. 시작 바퀴 앞의 체결도 마찬가지다.
        if (firstRound || orderState() != before) orderedThrough = received.get()
        coolingDown = false
        markProgress()
        return false
    }

    private suspend fun handleTrade(trade: TradeTick) {
        markProgress()
        warnDropped()
        lastTrade = trade.sample
        val orders = when {
            coolingDown || trade.seq <= orderedThrough -> TradeOrders.NONE
            lastResetDate != tradingDate -> TradeOrders.EXITS
            else -> TradeOrders.ALL
        }
        try {
            when (work.onTrade(ticker, state, trade.sample, orders)) {
                TradeOutcome.NO_ORDER -> Unit
                TradeOutcome.ORDERED -> orderedThrough = received.get()
                TradeOutcome.NO_RESULT -> {
                    orderedThrough = received.get()
                    coolingDown = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coolingDown = true
            log.error("Ticker worker {} trade failed: {}", ticker, e.message, e)
        }
        markProgress()
    }

    private fun warnDropped() {
        if (dropped.get() == 0L) return
        val now = clock.millis()
        val last = dropWarnedAtMs
        if (last != null && now - last < DROP_WARN_INTERVAL_MS) return
        dropWarnedAtMs = now
        log.warn("Ticker worker {} dropped {} oldest trades — the mailbox was full while it was busy", ticker, dropped.getAndSet(0))
    }

    private data class OrderState(val position: Boolean, val pendingBuy: String?, val pendingSell: String?, val unsynced: Boolean, val volume: Double)

    private fun orderState() = OrderState(state.position, state.pendingBuyRef(), state.pendingSellRef(), state.unsynced, state.holdVolume)

    private suspend fun release(): Boolean {
        if (!work.releaseIfSettled(ticker, state)) return false
        released = true
        mailbox.close()
        trades.close()
        return true
    }
}
