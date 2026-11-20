package com.trading.bot.engine

import com.trading.bot.domain.Account
import com.trading.bot.domain.TradingState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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

    suspend fun process(ticker: String, state: TradingState, round: Round)
}

/**
 * 한 종목의 상태를 바꾸는 유일한 곳. 우편함의 바퀴를 차례로 처리하고, 처리하는 동안 온 바퀴는 하나로 합친다(가장 새 거래일·시세가 남는다).
 * 종목끼리는 기다리지 않는다 — 매수만 엔진의 창구에서 줄을 선다.
 */
internal class TickerWorker(
    val ticker: String,
    private val state: TradingState,
    private val work: TickerWork,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mailbox = Channel<Round>(Channel.CONFLATED)
    private var lastResetDate: LocalDate? = null

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

    fun markProgress() {
        progressAt = clock.instant()
    }

    suspend fun run(accounts: List<Account>?) {
        work.sync(ticker, state, accounts)
        markProgress()
        if (release()) return
        for (round in mailbox) {
            markProgress()
            try {
                if (lastResetDate != round.tradingDate) {
                    work.resetDay(state, round.tradingDate)
                    lastResetDate = round.tradingDate
                    // 리셋을 저장한 뒤에 빼야 그 종목의 리셋도 저장된다.
                    if (release()) return
                }
                work.process(ticker, state, round)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 다음 바퀴가 곧 다시 시도한다.
                log.error("Ticker worker {} round failed: {}", ticker, e.message, e)
            }
            markProgress()
        }
    }

    private suspend fun release(): Boolean {
        if (!work.releaseIfSettled(ticker, state)) return false
        released = true
        mailbox.close()
        return true
    }
}
