package com.trading.bot.kis.market

import com.trading.bot.domain.TradeSide
import com.trading.bot.exchange.OrderGate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * KRX 주문 창. 매수는 [개장+1분, 폐장−15분), 매도는 [개장+1분, 폐장−10분) 안에서만 낸다.
 * 매도 창을 폐장 10분 전에 닫아 종가 단일가(정규장 15:20–15:30)에 시장가를 내지 않는다.
 * 휴장일 자료 범위 밖의 날에는 신규 매수만 막고, 매도는 평일 규칙으로 연다(보유를 빠져나올 길은 남긴다).
 * 범위 밖 ERROR 의 하루 1회는 이 인스턴스 안에서만 지켜지므로 런타임마다 한 인스턴스를 공유해야 한다.
 */
class KrxOrderGate(private val calendar: KrxTradingCalendar) : OrderGate {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastUncoveredLogDate = AtomicReference<LocalDate?>(null)

    override fun mayPlace(ticker: String, side: TradeSide, now: Instant): Boolean {
        val local = LocalDateTime.ofInstant(now, KrxTradingCalendar.KST)
        val date = local.toLocalDate()
        if (side == TradeSide.BUY && !calendar.covers(date)) {
            logUncoveredOnce(date)
            return false
        }
        val session = calendar.session(date) ?: return false
        val closeMargin = if (side == TradeSide.BUY) BUY_CLOSE_MARGIN_MINUTES else SELL_CLOSE_MARGIN_MINUTES
        return local.toLocalTime().within(session.open.plusMinutes(OPEN_DELAY_MINUTES), session.close.minusMinutes(closeMargin))
    }

    /** 거절에 따른 backoff 는 KIS 오류 코드로 거절을 분류하는 주문 어댑터가 정하므로 여기서는 반응하지 않는다. */
    override fun onDefinitiveRejection(ticker: String, side: TradeSide, now: Instant) = Unit

    private fun logUncoveredOnce(date: LocalDate) {
        if (lastUncoveredLogDate.getAndSet(date) == date) return
        log.error(
            "KRX 휴장일 자료가 {}–{} 만 담고 있어 {} 의 신규 매수를 막는다 — krx-calendar.json 을 갱신해야 열린다",
            calendar.coveredFrom, calendar.coveredThrough, date,
        )
    }

    private fun LocalTime.within(start: LocalTime, endExclusive: LocalTime): Boolean = !isBefore(start) && isBefore(endExclusive)

    companion object {
        const val OPEN_DELAY_MINUTES = 1L
        const val BUY_CLOSE_MARGIN_MINUTES = 15L
        const val SELL_CLOSE_MARGIN_MINUTES = 10L
    }
}
