package com.trading.bot.kis.client

import com.trading.bot.kis.domain.KisBalanceResponse
import com.trading.bot.kis.domain.KisCandle
import com.trading.bot.kis.domain.KisCandlePeriod
import com.trading.bot.kis.domain.KisCcldRow
import com.trading.bot.kis.domain.KisHolding
import com.trading.bot.kis.domain.KisOrderAck
import com.trading.bot.kis.domain.KisOrderRequest

/**
 * KIS 국내주식 거래 클라이언트. 사용자별로 생성된다(키/계좌/모의여부 바인딩 — [KisClientFactory]).
 * placeOrder 는 비멱등 — 자동 재시도하지 않는다(WAL reconcile 이 유실을 책임진다, plan D3).
 */
interface KisClient {
    /**
     * 현금주문 송신. 성공(rt_cd=0, ODNO 존재) 시 [KisOrderAck].
     * 브로커 거부(rt_cd!=0) → [KisApiException] (definitiveReject=true).
     * 전송 실패/타임아웃/5xx(접수 여부 불명) → [KisApiException] (definitiveReject=false).
     */
    suspend fun placeOrder(req: KisOrderRequest): KisOrderAck

    /** 당일 주문체결 조회(reconcile 데이터원). date=YYYYMMDD(KST). */
    suspend fun inquireDailyConclusions(date: String): List<KisCcldRow>

    /** 잔고 전체(보유종목 output1 + 계좌요약 output2). */
    suspend fun getBalance(): KisBalanceResponse

    /** 보유종목 잔고(getBalance().holdings 위임). */
    suspend fun getHoldings(): List<KisHolding>

    /** 현재가(원). */
    suspend fun getCurrentPrice(symbol: String): Long

    /** 일/주/월봉. from/to=YYYYMMDD. 시세는 실전도메인 권장(모의 가능여부 스모크 확인). */
    suspend fun getDailyCandles(
        symbol: String,
        from: String,
        to: String,
        period: KisCandlePeriod = KisCandlePeriod.D,
        adjusted: Boolean = false,
    ): List<KisCandle>

    /** 당일 분봉(1분, 호출당 30건). baseTime=HHMMSS 기준 과거로 회수. */
    suspend fun getMinuteCandles(symbol: String, baseTime: String): List<KisCandle>
}

/**
 * @param definitiveReject true = 브로커가 명시 거부(rt_cd!=0) → 주문 미접수 확정(FAILED 가능).
 *                          false = 전송/타임아웃 등 모호 → 접수 여부 불명(UNKNOWN, reconcile 필요).
 */
class KisApiException(
    val definitiveReject: Boolean,
    val statusCode: Int? = null,
    val rtCd: String? = null,
    val msgCd: String? = null,
    val msg: String? = null,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException("KIS API error: reject=$definitiveReject status=$statusCode rt_cd=$rtCd msg=$msg", cause)
