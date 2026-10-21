package com.trading.bot.kis.client

import java.time.LocalDate

/**
 * KIS 국내주식 REST. 한 인스턴스는 (환경, 키, 계좌) 하나에 묶인다 — [KisClientRegistry] 가 만든다.
 * [KisClientRegistry.evict] 뒤에는 이 인스턴스의 모든 호출이 실패하므로 registry 에서 다시 받아야 한다.
 *
 * 조회는 EGW00201·HTTP 5xx·타임아웃·연결 오류에서 정해진 횟수만 다시 보내고, 소진하면 [KisTransientException] 을 던진다.
 * rt_cd≠0 과 그 밖의 HTTP 4xx 는 [KisRejectedException] 이다(4xx 면 rtCd 가 null 일 수 있다).
 * 필요한 필드가 비었거나 숫자가 아니면 [KisMalformedResponseException] 이다.
 * 연속조회는 끝까지 읽지 못하면 부분 결과 없이 예외다.
 */
interface KisApi {
    /** 0 이하 현재가는 [KisMalformedResponseException] 이다. */
    suspend fun currentPrice(code: String, lane: KisLane): KisQuote

    /** 최신 봉이 [0] 이다. KIS 는 호출당 100행까지만 주므로 기간을 그 안으로 나누는 것은 호출자 몫이다. */
    suspend fun dailyCandles(code: String, from: LocalDate, to: LocalDate, adjusted: Boolean = true, lane: KisLane): List<KisDailyCandle>

    /** 보유 종목 전체(모든 페이지)와 계좌 요약. */
    suspend fun balance(lane: KisLane): KisBalance

    /** 시장가 기준 매수가능 조회. 지정가로 조회하면 종목 증거금률이 반영되지 않는다. */
    suspend fun buyable(code: String, price: Long, lane: KisLane): KisBuyable

    /**
     * 현금 주문. 항상 ORDER 레인이고 어떤 경우에도 다시 보내지 않는다.
     * - [KisRejectedException]: 2xx 에 rt_cd≠0(EGW00201 제외) — 미접수 확정. 토큰 오류면 토큰도 버린다.
     * - [KisOutcomeUnknownException]: 타임아웃·연결 끊김·HTTP 비 2xx(본문의 msg_cd 와 무관, 토큰 오류 포함)·
     *   HTTP 상태와 무관한 EGW00201·2xx 인데 ODNO 없음 — 접수 여부 불명.
     * - [KisTransientException]: 리미터·토큰 단계에서 멈춰 보내지 않았다.
     * - [KisException.endpoint] 가 `token` 인 예외(발급 거절·형식 오류 포함)도 주문을 보내기 전이다.
     * 전송 중에 코루틴이 취소되면 취소 예외가 그대로 나가며, 이때도 접수 여부는 알 수 없다.
     */
    suspend fun placeCashOrder(request: KisCashOrderRequest): KisOrderAck

    /** [date] 하루의 주문·체결 행 전체(모든 페이지). [odno] 를 주면 그 주문만 거른다. */
    suspend fun dailyOrders(date: LocalDate, odno: String? = null, lane: KisLane): List<KisOrderRow>

    /** [baseDate] 부터의 휴장일 조회 첫 페이지 행. KIS 가 하루 1회 호출을 권해 연속조회는 하지 않는다. */
    suspend fun holidays(baseDate: LocalDate, lane: KisLane): List<KisHolidayRow>
}
