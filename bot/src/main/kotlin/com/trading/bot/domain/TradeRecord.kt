package com.trading.bot.domain

import java.time.LocalDateTime

/**
 * 이 체결의 수수료를 sink 가 어떻게 다뤄야 하는지.
 *
 * 값 하나(`Double?`)로 "추정하라 / 이 값을 써라 / 모른다"를 표현하면 sentinel 이 주석에만 남아
 * 오독을 부른다. 특히 "모른다"를 `0.0` 으로 쓰면 실제 수수료 0 과 구분되지 않는다.
 */
sealed interface FeeBasis {
    /**
     * sink 가 `totalAmount` 에 요율을 곱해 추정한다.
     * **`totalAmount` 가 실제 체결 대금인 경로에서만** 쓸 것 — 아니면 그만큼 부풀려진다(#133).
     */
    data object Estimate : FeeBasis

    /** 거래소가 청구한 실제 수수료(Upbit 주문 응답의 `paid_fee`). */
    data class Measured(val amount: Double) : FeeBasis

    /**
     * basis 를 알 수 없다 — 추정하면 틀린 값이 되므로 `0`(미기록)으로 남긴다.
     * `0 = 미기록` 은 V21 이 세운 규약이다("fee 는 소급하지 않는다. 이전 행은 0(미기록)으로 남는다").
     */
    data object Unrecorded : FeeBasis
}

/**
 * 체결 이벤트 — `trade_records`·`trade_executions` 두 감사 테이블과 Discord 알림이 함께 쓴다.
 * 테이블 컬럼 집합의 미러가 아니라 "체결 사실"을 담는다.
 *
 * 파생값은 sink 가 유도하는 것이 기본이다. **손익 두 필드는 예외**다: 매도 시점의 평단은 청산과
 * 함께 사라져서 sink 가 되짚을 수 없다. 원가를 대신 실으면 일관되겠지만 `pnlPercent` 를 엔진에서
 * 단언하는 기존 테스트를 흔들어야 해서 그대로 뒀다.
 *
 * [fee] 도 예외다. 2026-08-22 에는 "`fee` 는 `totalAmount` 만 있으면 나온다"는 이유로 여기 두지 않고
 * `saveAudit` 이 계산하게 했는데, **엔진 매수 경로에서 그 전제가 거짓이다** — `totalAmount` 가 그 주문의
 * 체결 대금이 아니라 포지션 전체 원가라 수수료가 부풀려졌다(#133). 체결 후 주문 응답에서 `paid_fee`
 * 실측값을 얻을 수 있으므로, 그 결정의 적용 범위를 수정해 실측을 여기 싣는다.
 *
 * `strategy`·`pnlPercent`·`pnlAmount`·`fee` 에 기본값을 두지 않는다 — 인자를 빠뜨려도 컴파일이 통과하는
 * 바람에 매도 경로가 전략을 통째로 유실했던 전례가 있다. 생성부가 매번 의도를 밝히게 한다.
 */
data class TradeRecord(
    val ticker: String,
    val side: TradeSide,
    val price: Double,
    val volume: Double,
    val totalAmount: Double,
    val pnlPercent: Double?,
    // 실현 손익(원). pnlPercent 와 같은 net 기준. 진입(BUY)은 손익이 없으므로 null.
    val pnlAmount: Double?,
    val strategy: String?,
    /** 이 체결의 수수료를 sink 가 어떻게 다룰지. 경로마다 다르므로 기본값을 두지 않는다. */
    val fee: FeeBasis,
    val reason: String? = null,
    // 거래소 주문 uuid — 재시작 후 reconcile 중복 기록을 막는 멱등 dedup 키(#20). null 이면 dedup 대상 아님.
    val exchangeOrderId: String? = null,
    val userId: Long = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class TradeSide {
    BUY, SELL
}

enum class SellReason {
    TAKE_PROFIT,
    TRAILING_STOP,
    STOP_LOSS,
    CHART_EXIT,
    DAILY_RESET,
    MANUAL,
}
