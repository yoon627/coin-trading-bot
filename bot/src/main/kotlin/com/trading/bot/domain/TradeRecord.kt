package com.trading.bot.domain

import java.time.LocalDateTime

/**
 * 체결 이벤트 — `trade_records`·`trade_executions` 두 감사 테이블과 Discord 알림이 함께 쓴다.
 * 테이블 컬럼 집합의 미러가 아니라 "체결 사실"을 담는다.
 *
 * 파생값은 sink 가 유도하는 것이 기본이다 — `fee` 는 `totalAmount` 만 있으면 나오므로 여기 없고
 * `TradeExecutionService.saveAudit` 이 계산한다. **손익 두 필드는 예외**다: 매도 시점의 평단은 청산과
 * 함께 사라져서 sink 가 되짚을 수 없다. 원가를 대신 실으면 일관되겠지만 `pnlPercent` 를 엔진에서
 * 단언하는 기존 테스트를 흔들어야 해서 그대로 뒀다.
 *
 * `strategy`·`pnlPercent`·`pnlAmount` 에 기본값을 두지 않는다 — 인자를 빠뜨려도 컴파일이 통과하는 바람에
 * 매도 경로가 전략을 통째로 유실했던 전례가 있다. 생성부가 매번 의도를 밝히게 한다.
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
